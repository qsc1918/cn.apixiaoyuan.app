package cn.apixiaoyuan.app.core.pk

import cn.apixiaoyuan.app.core.log.AppLogger
import cn.apixiaoyuan.app.core.oldsimian.OralStrokes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * PK 秒结算的数据入口：出题 → 解密 → 组装提交 body → 加密提交 → 结算核对。
 *
 * ## 2026-10-02 移植自 pk-node（commit `804c8ac`）：三处关键变化
 *
 * | 项 | 旧 | 新 |
 * |---|---|---|
 * | 出口 | Retrofit [cn.apixiaoyuan.app.core.network.RetrofitFactory] | [PkRawApi]（裸 OkHttp，PK 专用协议表） |
 * | 出题 | 明文 `match`（`631` + `_appId=6` + `3.141.1`） | **`match/v2`** + `611`（无 `_appId`）+ `3.143.1`，响应**加密** |
 * | 响应 | 直接 `Json.parse` | 先 [PkProtocol.decodeEncrypted]（XOR → gunzip）再 parse |
 *
 * 换到 v2 之后**不再撞「每账号 ≈60 秒」的出题频控** —— 那个 400 是
 * 「631 + 明文 match」这套异构请求被服务端按频控处理的产物，不是真的账号级冷却。
 * 详见 [PkProtocol] 的类 KDoc。
 *
 * ## 笔迹：`script` 与 `curTrueAnswer.pathPoints` 必须同源
 *
 * [OralStrokes] 只能产出「`[[{x,y},...],...]` 的 JSON 字符串」（供 `script` 字段），
 * 而 `curTrueAnswer.pathPoints` 是结构化 `[[{x,y},...],...]`。这里从生成的
 * 笔迹字符串反解回结构，保证两处**完全一致** —— 服务端回放时
 * `script`（文字）与 `pathPoints`（结构）对不上会显得可疑。
 */
object PkBattleRepository {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    /** 每题 costTime 下限（对齐练习 ExamQuestion.MIN_COST_TIME_MS 的真机边界）。 */
    const val MIN_COST_TIME_MS = 5L

    /**
     * 拉数学 PK 首页（对局类型列表 + 分数）。
     *
     * `GET /leo-game-pk/android/math/pk/home?grade=N` 返回明文 JSON。
     *
     * @param grade 年级（从 SessionStore 读，默认 2）
     */
    suspend fun fetchMathHome(grade: Int): PkMathHome = withContext(Dispatchers.IO) {
        val r = PkRawApi.home(grade)
        if (r.status != 200) throw PkHttpException(r.status, r.text)
        json.decodeFromString<PkMathHome>(r.text)
    }

    /**
     * 出题（按玩法，走 `match/v2`）。
     *
     * 响应是密文，先 [PkProtocol.decodeEncrypted] 解成明文 JSON 再 parse。
     *
     * @param mode     玩法
     * @param pointId  知识点 ID（PK 首页 pointList 提供，默认 1）
     * @return 出题响应；失败抛 [PkHttpException]（由上层 Engine 决定重试）
     */
    suspend fun fetchMatch(mode: PkMode, pointId: Int): PkMatchResponse = withContext(Dispatchers.IO) {
        val r = PkRawApi.match(mode, pointId)
        if (r.status != 200) throw PkHttpException(r.status, r.text).also { logHttp("出题", mode, it) }
        val plain = PkProtocol.decodeEncrypted(r.body)?.toString(Charsets.UTF_8) ?: r.text
        val trimmed = plain.trim()
        if (!trimmed.startsWith("{")) {
            // 解不开也不是明文 JSON：八成是风控页/空响应，带原文抛出去便于定位
            throw PkHttpException(r.status, plain.take(200))
        }
        json.decodeFromString<PkMatchResponse>(trimmed)
    }

    /**
     * 提交一局（按玩法）。
     *
     * body 明文 JSON → gzip → `libContentEncoder` → octet-stream（见 [PkProtocol.encodeSubmitBody]）。
     *
     * ## 错误必须带响应体
     *
     * 403 的 body 是 `{"status":403,"message":"error"}`、400 的 body 可能是
     * `请求过于频繁` —— 判决信息只在 body 里。这里统一转成 [PkHttpException]，
     * 上层的重试策略才能据此区分「等一等」与「别等了」。
     *
     * @return 提交响应原始文本；失败抛 [PkHttpException]（HTTP 非 2xx）。
     */
    suspend fun submit(mode: PkMode, body: PkSubmitBody): String = withContext(Dispatchers.IO) {
        val plain = json.encodeToString(PkSubmitBody.serializer(), body).toByteArray(Charsets.UTF_8)
        val cipher = PkProtocol.encodeSubmitBody(plain)
            ?: throw IllegalStateException("内容编码器不可用（libContentEncoder.so 未加载）—— 拒绝发明文提交")
        val r = PkRawApi.submit(mode, cipher)
        if (r.status != 200) throw PkHttpException(r.status, r.text).also { logHttp("提交", mode, it) }
        r.text
    }

    /**
     * 把「状态码 + body + 是否频控」落进日志页。
     *
     * 判决信息只在响应体里：`400 请求过于频繁` / `403 {"status":403,...}`。
     * 不打出来，界面就只能看到「HTTP 400」，分不清该等还是该停。
     */
    private fun logHttp(what: String, mode: PkMode, e: PkHttpException) {
        AppLogger.w(
            "PkBattle",
            "$what[${mode.displayName}] HTTP ${e.code}（${if (e.isRateLimited) "频控/风控" else "内容被拒"}）body=${e.body.take(200)}",
        )
    }

    /**
     * 核对结算：`GET /leo-game-pk/android/math/pk/history/detail?pkIdStr=X`。
     *
     * ## ★ 为什么提交成功后还要求一次
     *
     * **提交返回 200 ≠ 这局已结算。** 提交被 403 的局，服务端同样留一条
     * `{correctCnt:0, questions:null}` 的**占位记录** —— 只看提交结果会把
     * 「其实没算上」报成成功。这是最难查的一类假阳性（日志说成功、分数没涨）。
     *
     * 本接口就是结算页 `result.html?pkIdStr=X` 的主数据源，以它为准才对得上真机。
     *
     * @return 结算明细；网络失败返回 null（**不代表没结算**，只是没查到 ——
     *         调用方应据此降级提示，而不是直接判失败）。
     */
    suspend fun fetchHistoryDetail(pkIdStr: String): PkHistoryDetail? = withContext(Dispatchers.IO) {
        runCatching {
            val r = PkRawApi.historyDetail(pkIdStr)
            if (r.status != 200) null else json.decodeFromString<PkHistoryDetail>(r.text)
        }.onFailure {
            AppLogger.w("PkBattle", "结算核对失败 pkIdStr=$pkIdStr: ${it.message}", it)
        }.getOrNull()
    }

    /**
     * 组装「全对秒结算」提交 body。
     *
     * 结构与真机 ground truth 逐字段对齐（2026-09-26 用户实打一轮 PK 落盘的
     * localStorage `exerciseResult`）：
     * - 顶层直接展开 examVO 字段（pkIdStr/pointId/pointName/ruleType/
     *   questionCnt/correctCnt/costTime/questions），无 examVO 嵌套、无 userInfos；
     * - 每题保留完整 question 字段（id/examId/content/answer/userAnswer/answers/
     *   status/script/wrongScript/ruleType/errorState）+ curTrueAnswer 四字段；
     * - `script` 与 `curTrueAnswer.pathPoints` 同源（同一份笔迹）；
     * - `correctCnt` = 对题数（全对 = 题目数）；
     * - `costTime` = 整卷耗时（毫秒）。
     *
     * 判对错只看 `userAnswer`，笔迹只回放 —— 所以秒结算语义 = 每题答案填对即可。
     *
     * @param match        出题响应
     * @param costTimeMs   整卷耗时（毫秒）。默认按题数 × [MIN_COST_TIME_MS] 给一个
     *                     合理下限，避免 0ms 明显不自然。
     * @return 组装好的提交 body；出题响应缺 pkIdStr 或题目列表时抛异常。
     */
    fun buildSubmitBody(
        match: PkMatchResponse,
        costTimeMs: Long? = null,
        strokeMode: PkStrokeMode = PkStrokeMode.ARC,
    ): PkSubmitBody {
        val pkIdStr = match.pkIdStr
            ?: error("出题响应缺 pkIdStr")
        val examVO = match.examVO
            ?: error("出题响应缺 examVO")
        val questions = examVO.questions
            ?: error("出题响应缺 examVO.questions")

        val submitQuestions = questions.mapIndexed { idx, q ->
            val answer = q.rightAnswer ?: ""
            // PK 笔迹：默认 ARC（比较题 `>` / `<` 用密集弧线，否则被服务端判作弊 403）；
            // SEVEN_SEGMENT 时回落到七段码字形。seed 用题号，保证每题笔迹不同。
            val script: String = if (strokeMode == PkStrokeMode.ARC) {
                OralStrokes.pkArcScript(answer, seed = idx)
                    ?: (OralStrokes.scriptJson(answer) ?: "[]")
            } else {
                OralStrokes.scriptJson(answer) ?: "[]"
            }
            val pathPoints = parsePathPoints(script)
            PkSubmitQuestion(
                id = q.id,
                examId = q.examId,
                content = q.content,
                answer = q.answer,
                userAnswer = answer,
                answers = q.answers,
                status = PkSubmitQuestion.STATUS_RIGHT,
                script = script,
                wrongScript = null,
                ruleType = q.ruleType,
                errorState = q.errorState,
                curTrueAnswer = PkCurTrueAnswer(
                    recognizeResult = answer,
                    pathPoints = pathPoints,
                    answer = PkSubmitQuestion.STATUS_RIGHT,
                    showReductionFraction = 0,
                ),
            )
        }

        val questionCnt = submitQuestions.size
        val cost = costTimeMs
            ?: (questionCnt.toLong() * MIN_COST_TIME_MS).coerceAtLeast(MIN_COST_TIME_MS)

        return PkSubmitBody(
            pkIdStr = pkIdStr,
            pointId = examVO.pointId,
            pointName = examVO.pointName,
            ruleType = examVO.ruleType,
            questionCnt = questionCnt,
            correctCnt = questionCnt,
            costTime = cost,
            questions = submitQuestions,
        )
    }

    /**
     * 把 `OralStrokes` 产出的 script JSON 反解成结构化 pathPoints。
     *
     * script 形如 `[[{"x":1,"y":2},...],...]`，反解结果与 [PkCurTrueAnswer.pathPoints]
     * 的序列化形态对齐。解析失败返回空列表（宁可少笔迹，不抛异常打断整局）。
     */
    private fun parsePathPoints(script: String): List<List<PkPoint>> = runCatching {
        val root = json.parseToJsonElement(script).jsonArray
        root.map { strokeEl ->
            strokeEl.jsonArray.map { ptEl ->
                val obj = ptEl.jsonObject
                PkPoint(
                    x = obj["x"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
                    y = obj["y"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
                )
            }
        }
    }.getOrDefault(emptyList())
}
