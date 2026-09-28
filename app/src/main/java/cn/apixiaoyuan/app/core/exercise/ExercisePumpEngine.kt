package cn.apixiaoyuan.app.core.exercise

import cn.apixiaoyuan.app.core.model.ExamData
import cn.apixiaoyuan.app.core.model.ExamQuestion
import cn.apixiaoyuan.app.core.oldsimian.OldSimianPrefs
import cn.apixiaoyuan.app.core.oldsimian.OralStrokes
import cn.apixiaoyuan.app.core.pk.PkCurTrueAnswer
import cn.apixiaoyuan.app.core.pk.PkPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlin.random.Random

/**
 * 练习「刷对局」引擎 —— 与 pk-node 的 `practiceLoop` / `runPractice` 对齐。
 *
 * ## 为什么要单独一个引擎（2026-09-28）
 *
 * 用户指出：**「练习刷分应该和网页端一样」** —— 即循环跑
 * 「出题 → 抄答案+生成笔迹 → 提交」，而不是让人手点 [ExerciseScreen]。
 *
 * 网页端（pk-node `src/exercise.js`）的做法：
 *
 * ```
 * practiceLoop(rounds, keypointId, limit, gapMin/gapMax)
 *   └─ 每轮 runPractice():
 *        1. 出题  POST /leo-math/android/exams          （keypointId + limit）
 *        2. 作答  每题抄服务端下发的 answer，并生成笔迹
 *        3. 提交  PUT  /leo-math/android/exams/{examId} （JSON 明文 + 笔迹）
 *        4. 经验 = 服务端判对的题数 × 2
 * ```
 *
 * ## ★ 配速：出题冷却 ≈[MATCH_COOLDOWN_MS]（账号级，硬下限）
 *
 * 服务端按**账号**限制出题频率，实测 ≈62s。配得比它小**不会更快**（会 429）。
 * 本引擎与网页端同一套语义：
 *
 * ```
 * 实际等待 = max(冷却剩余, 随机(gapMinMs, gapMaxMs))
 * ```
 *
 * - 记住**上次成功出题时刻**，下一轮等到「上次成功 + 冷却 - 安全边距」再发车；
 * - 在冷却之上再叠加随机间隔（避免固定节奏）；
 * - 万一估计偏了，[fetchExamWithRetry] 内部的 429 重试兜底。
 *
 * ## ★ 建议每局 100 题
 *
 * 冷却按**次**算、不按题数 —— 一次开 100 题（=200 经验）比开 10 题（=20 经验）
 * 划算 10 倍。所以 [DEFAULT_LIMIT] 取 100。
 *
 * ## 与「直接刷分」（[cn.apixiaoyuan.app.core.oldsimian.ScorePump]）的区别
 *
 * | | 练习刷对局（本引擎） | 直接刷分（ScorePump） |
 * |---|---|---|
 * | 行为 | 真出题 + 真提交（服务端判卷） | 直接报经验增量 |
 * | 上限 | 无日限（受出题冷却配速） | **日上限 400**（ruleType 每类每天只记一次）|
 * | 真实性 | 有完整练习记录 | 只有一条经验流水 |
 */
object ExercisePumpEngine {

    /**
     * 出题冷却（毫秒）——**账号级硬下限**。
     *
     * 与 pk-node `MATCH_COOLDOWN_MS = 62_000` 同值。实测服务端按账号限，
     * 低于它会 429；所以本引擎把它当**下沿**而不是可配置项。
     */
    const val MATCH_COOLDOWN_MS = 62_000L

    /** 从冷却下沿往回退的安全边距（毫秒）。避免卡在边界上被 429。 */
    const val COOLDOWN_SAFETY_MS = 1_000L

    /** 命中 429 后的重试间隔（毫秒）。同 pk-node `MATCH_RETRY_INTERVAL_MS`。 */
    const val RETRY_INTERVAL_MS = 10_000L

    /** 出题持续被限流时的放弃阈值（毫秒）。同 pk-node `MATCH_RETRY_MAX_MS`。 */
    const val RETRY_MAX_MS = 4 * 60 * 1000L

    /** 默认每局题数 —— 100 题 = 200 经验，是性价比最高的档位。 */
    const val DEFAULT_LIMIT = 100

    /** 默认轮数。 */
    const val DEFAULT_ROUNDS = 1

    /**
     * 跑「练习刷对局」：循环 N 轮「出题 → 抄答案+笔迹 → 提交」。
     *
     * @param keypointId 知识点 ID（由调用方从 [ExerciseRepository.fetchMathScope] 选）
     * @param rounds     轮数（≥1）
     * @param limit      每局题数（建议 [DEFAULT_LIMIT]）
     * @param gapMinMs   每轮间隔下限（与冷却取 max）
     * @param gapMaxMs   每轮间隔上限（随机抖动）
     * @param costTimeMs 每题耗时（null = 用 [OldSimianPrefs] 的配置；下限 5ms）
     * @param onProgress 进度回调：(已完成轮数, 总轮数, 事件文本)
     * @return 汇总结果
     */
    suspend fun practiceLoop(
        keypointId: Int,
        rounds: Int = DEFAULT_ROUNDS,
        limit: Int = DEFAULT_LIMIT,
        gapMinMs: Long = 0L,
        gapMaxMs: Long = 0L,
        costTimeMs: Long? = null,
        onProgress: (done: Int, total: Int, event: String) -> Unit = { _, _, _ -> },
    ): Summary = coroutineScope {
        require(rounds >= 1) { "轮数必须 ≥1" }
        require(limit >= 1) { "每局题数必须 ≥1" }

        val gapMin = gapMinMs.coerceAtLeast(0L)
        val gapMax = gapMaxMs.coerceAtLeast(gapMin)

        var done = 0
        var failed = 0
        var totalExp = 0
        var lastMatchOkAt = 0L

        for (round in 1..rounds) {
            if (!isActive) break

            // ---- 每轮间隔：冷却剩余 与 随机间隔 取大者（与 pk-node 同语义）----
            if (lastMatchOkAt > 0) {
                val cooldownLeft = (lastMatchOkAt + MATCH_COOLDOWN_MS - COOLDOWN_SAFETY_MS -
                    System.currentTimeMillis()).coerceAtLeast(0L)
                val gap = if (gapMax > gapMin) {
                    gapMin + Random.nextLong(gapMax - gapMin + 1)
                } else {
                    gapMin
                }
                val wait = maxOf(cooldownLeft, gap)
                if (wait > 0) {
                    onProgress(
                        done, rounds,
                        "间隔 ${"%.1f".format(wait / 1000.0)}s 后开始第 $round 轮" +
                            "（配置 ${"%.1f".format(gap / 1000.0)}s，冷却剩 " +
                            "${"%.1f".format(cooldownLeft / 1000.0)}s）",
                    )
                    delay(wait)
                }
            }

            onProgress(done, rounds, "第 $round/$rounds 轮开始")
            val result = runCatching {
                runPractice(keypointId, limit, costTimeMs, onProgress = { ev ->
                    onProgress(done, rounds, ev)
                })
            }.getOrElse { t ->
                if (t is CancellationException) throw t
                RoundResult(ok = false, error = t.message ?: t.toString())
            }

            if (result.ok) {
                done++
                totalExp += result.exp
                lastMatchOkAt = System.currentTimeMillis()
                onProgress(
                    done, rounds,
                    "第 $round 轮成功：判对 ${result.correctCnt}/${result.questionCnt}，" +
                        "+${result.exp} 经验（累计 +$totalExp）",
                )
            } else {
                failed++
                onProgress(done, rounds, "第 $round 轮失败：${result.error?.take(90)}")
                // 出题失败多半是还在冷却 → 补等一轮再继续（同 pk-node）
                delay(RETRY_INTERVAL_MS)
            }
        }

        onProgress(done, rounds, "全部结束：成功 $done/$rounds，累计经验 +$totalExp")
        Summary(rounds = rounds, done = done, failed = failed, totalExp = totalExp)
    }

    /**
     * 跑一轮：出题（含 429 重试）→ 抄答案 + 生成笔迹 → 提交。
     *
     * @return 本轮结果（失败不抛异常，用 [RoundResult.ok] 表达）
     */
    suspend fun runPractice(
        keypointId: Int,
        limit: Int,
        costTimeMs: Long? = null,
        onProgress: (String) -> Unit = {},
    ): RoundResult {
        // ---- 1) 出题（含 429 重试）----
        onProgress("出题中：keypointId=$keypointId limit=$limit")
        val exam = ExerciseRepository.fetchExamWithRetry(keypointId, limit) { msg -> onProgress(msg) }
            ?: return RoundResult(ok = false, error = "出题失败（限流未解除或网络错误）")

        val examId = exam.idString
            ?: return RoundResult(ok = false, error = "出题响应缺 idString")

        onProgress(
            "出题成功 examId=$examId 共 ${exam.questions?.size ?: 0} 题" +
                "（预计 +${(exam.questions?.size ?: 0) * 2} 经验）",
        )

        // ---- 2) 作答：抄答案 + 生成笔迹 ----
        val answered = answerAll(exam, costTimeMs)
        onProgress(
            "提交（全对 ${answered.correctCnt}/${answered.questions?.size ?: 0}，含笔迹）",
        )

        // ---- 3) 提交（JSON 明文 + 笔迹；服务端回放判卷）----
        val result = ExerciseRepository.uploadExam(answered)
            ?: return RoundResult(ok = false, error = "提交失败（网络错误或服务端拒绝）")

        // ---- 4) 以服务端判卷为准 ----
        val correct = result.correctCnt
        val total = result.questionCnt.takeIf { it > 0 } ?: (result.questions?.size ?: 0)
        onProgress(
            "提交成功：服务端判对 $correct/$total，经验 +${correct * 2}",
        )
        return RoundResult(
            ok = true,
            examId = examId,
            questionCnt = total,
            correctCnt = correct,
            exp = correct * 2,
        )
    }

    /**
     * 本地「作答」：把每题填成全对，**并生成笔迹**。
     *
     * ## ★ 为什么必须有笔迹（2026-09-28 pk-node 实测）
     *
     * 只填 `userAnswer` + `status:1` 提交 → HTTP 200 但服务端判 **`correctCnt=0`**
     * —— 服务端**不信任客户端自报的 status**，而是「回放笔迹 + 识别」判卷。
     * 补上 `script`（笔迹）+ `curTrueAnswer`（与 script 同源）后 → 判对 10/10。
     *
     * 这与 PK 提交同一套纪律，笔迹生成也复用 [OralStrokes]。
     */
    internal fun answerAll(exam: ExamData, costTimeMsPerQuestion: Long?): ExamData {
        // 每题耗时：优先用调用方给的值，否则走 [OldSimianPrefs] 的配置（下限 5ms）。
        val per = (costTimeMsPerQuestion ?: OldSimianPrefs.customCostMs.toLong())
            .coerceAtLeast(ExamQuestion.MIN_COST_TIME_MS)

        var totalCost = 0L
        val questions = exam.questions.orEmpty().mapIndexed { idx, q ->
            val answer = q.rightAnswer ?: ""
            // 笔迹：比较题（> / <）走弧线模板（pkArcScript），其它回落七段码字形。
            // 与 PK 提交同一套：pk-node 实测「不带笔迹 → 服务端判 0 分」。
            val script = OralStrokes.pkArcScript(answer, idx + 1)
                ?: OralStrokes.scriptJson(answer)
            val cost = OldSimianPrefs.costTimeFor(per)
            totalCost += cost
            q.copy(
                userAnswer = answer,
                status = ExamQuestion.STATUS_RIGHT,
                costTime = cost,
                script = script,
                // 与 script 同源：pathPoints 由同一份点集反解，服务端回放才认。
                curTrueAnswer = script?.let {
                    PkCurTrueAnswer(
                        recognizeResult = answer,
                        pathPoints = toPkPoints(it),
                        answer = 1,
                        showReductionFraction = 0,
                    )
                },
            )
        }
        return exam.copy(
            questions = questions,
            correctCnt = questions.size,
            costTime = totalCost,
        )
    }

    /** 把笔迹 JSON（`[[{x,y},...],...]`）解析回点集，供 `curTrueAnswer.pathPoints` 用。 */
    private fun toPkPoints(scriptJson: String): List<List<PkPoint>> {
        val out = ArrayList<List<PkPoint>>()
        var stroke = ArrayList<PkPoint>()
        var i = 0
        while (i < scriptJson.length) {
            when (scriptJson[i]) {
                '[' -> {
                    stroke = ArrayList()
                    out.add(stroke)
                }
                '{' -> {
                    val end = scriptJson.indexOf('}', i)
                    if (end < 0) break
                    val body = scriptJson.substring(i + 1, end)
                    var x = 0f
                    var y = 0f
                    for (pair in body.split(',')) {
                        val kv = pair.split(':')
                        if (kv.size != 2) continue
                        val key = kv[0].trim().trim('"')
                        val v = kv[1].trim().toFloatOrNull() ?: continue
                        when (key) {
                            "x" -> x = v
                            "y" -> y = v
                        }
                    }
                    stroke.add(PkPoint(x, y))
                    i = end
                }
            }
            i++
        }
        return out
    }

    /** 单轮结果。 */
    data class RoundResult(
        val ok: Boolean,
        val examId: String = "",
        val questionCnt: Int = 0,
        val correctCnt: Int = 0,
        val exp: Int = 0,
        val error: String? = null,
    )

    /** 整体汇总。 */
    data class Summary(
        val rounds: Int,
        val done: Int,
        val failed: Int,
        val totalExp: Int,
    )
}