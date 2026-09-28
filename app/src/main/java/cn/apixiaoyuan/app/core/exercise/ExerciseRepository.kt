package cn.apixiaoyuan.app.core.exercise

import cn.apixiaoyuan.app.core.model.ExamData
import cn.apixiaoyuan.app.core.model.ExerciseEnglishSectionVO
import cn.apixiaoyuan.app.core.model.ExerciseHomepageData
import cn.apixiaoyuan.app.core.model.ExerciseScopeData
import cn.apixiaoyuan.app.core.model.ExerciseType
import cn.apixiaoyuan.app.core.model.LeoCurrentTaskInfo
import cn.apixiaoyuan.app.core.model.LeoTodayExerciseListData
import cn.apixiaoyuan.app.core.model.LeoUserCurrentExpData
import cn.apixiaoyuan.app.core.network.ServiceLocator

/**
 * 练习链路的数据入口。
 *
 * UI 层不直接碰 Retrofit Service —— 这是 DEV-PLAN 里定的分层纪律。
 * 本类把 [ServiceLocator] 里与练习相关的读接口包一层，做三件事：
 *
 *  1. 统一异常兜底：Service 抛的 [ApiException] 系列在这里收敛成 null 或默认值，
 *     UI 只处理「有数据 / 没数据」两态，不处理六种异常分类；
 *  2. 聚合调用：首页要同时拿任务卡与经验值，两个请求并发发出，
 *     避免串行等待；
 *  3. 隔离变化：原版接口返回结构若与推断不符，只改本类，不动 UI。
 *
 * 当前覆盖的都是**读接口**，不涉及 [cn.apixiaoyuan.app.core.network.NeedDecode]
 * 与 `@NeedEncode` —— 那两类接口在练习写链路（提交答案、上报经验）上，
 * 留待 native 解码桥（DEV-PLAN 模块 11）接入后再说。
 *
 * 数据来源（`docs/API-INVENTORY.md`）：
 *  - `GET /leo-star/android/exercise/rank/pre-fetch` → [LeoUserCurrentExpData]
 *  - `GET /leo-star/android/exercise/task/home` → [LeoCurrentTaskInfo]
 *  - `GET /leo-english/android/exercise/{type}` → [ExerciseEnglishSectionVO]
 */
object ExerciseRepository {

    /**
     * 拉首页任务卡。失败返回 null，UI 显示空态。
     *
     * 注意：`LeoCurrentTaskInfo` 的字段是从 smali 推断的（真字段未读出），
     * 若服务端返回的结构与推断不符，kotlinx.serialization 会因为
     * `ignoreUnknownKeys = true` 而不报错，只是字段落空 —— UI 表现为
     * 「拉到了但内容空」，这是可接受的中间态。
     */
    suspend fun fetchTasks(): LeoCurrentTaskInfo? = runCatching {
        ServiceLocator.exerciseLegacy.getCurrentUserTasks()
    }.getOrNull()

    /**
     * 拉当前用户经验值。失败返回 null。
     *
     * ## 这条接口是主域上**唯一实测可用**的（2026-09-25）
     *
     * 主域 `xyks.yuanfudao.com` 上：
     *  - 本接口 `GET /leo-star/android/exercise/rank/pre-fetch` → **HTTP 200 + 真实数据**
     *  - 其余端点（`task/home` / `leo-math` 出题 / `leo-profile` 资料 / 英语 / 作业）
     *    全部 **417**，响应头 `x-block-by: solar-encoder`
     *
     * 已排除的假设（都实测过）：cookie 差异（用原版真机完整 11 条 cookie
     * 仍 417）、设备头（13 种）、query 参数（7 种）、body 形态
     * （json/octet × plain/gzip 四种）、时间戳头（7 种）。
     *
     * `solar-encoder` 在客户端 APK 里**完全不存在**（smali / assets /
     * 所有 so 均已 grep），是服务端中间件自己打的标。服务端只对精确路径
     * `.../rank/pre-fetch` 放行明文 —— 该端点加任意 query 仍 200，
     * 但 `/pre-fetch/extra` 就 417，说明是**路径白名单**而非参数校验。
     *
     * ## 对本项目的意义
     *
     * 刷分链路里**分数读取这一段是真实可用的**（`fetchCurrentScore` 走这里），
     * 卡住的只是取卷与上传。UI 上已如实说明。
     *
     * 这个接口也带 `@NotNullAndValid` —— 服务端若返回空体会走 converter 报错，
     * 被 [runCatching] 兜住。
     */
    suspend fun fetchExp(): LeoUserCurrentExpData? = runCatching {
        ServiceLocator.exerciseLegacy.getCurrentUserExp()
    }.getOrNull()

    /**
     * 拉练习星级首页（`/leo-star/android/exercise/homepage`）。
     *
     * **这是刷分真正该看的分数**：`postSavedExp`（= `rank/login/attend`）记的是
     * **练习经验**，落在 `ExerciseHomepageData.curWeekExp` / `todayObtainedPoints`；
     * 而 [fetchExp] 的 `curWeekScore` 是**周排行榜**分数，刷分不会让它变化。
     *
     * 该端点**不在** solar-encoder 的 417 名单里（真机实测恒 200），所以可用。
     */
    suspend fun fetchExerciseHomepage(): ExerciseHomepageData? = runCatching {
        ServiceLocator.exerciseStar.getHomepage()
    }.getOrNull()

    /**
     * 按类型拉英语练习章节。
     *
     * @param type     练习类型，原版是 Int 枚举（听写 / 书写等），具体取值待真机确认
     * @param grade    年级 ID
     * @param semester 学期（1 上 / 2 下）
     * @param book     教材版本
     *
     * 四个参数全部必填 —— 从 smali 的 `getEnglishExercisesSections(IIII)` 签名读出，
     * 都是原始 int 不是 nullable。若服务端对某个值有范围要求，会返回 4xx，
     * 被 [runCatching] 兜住后返回 null。
     */
    suspend fun fetchEnglishSections(
        type: Int,
        grade: Int,
        semester: Int,
        book: Int,
    ): ExerciseEnglishSectionVO? = runCatching {
        ServiceLocator.englishExercise.getEnglishExercisesSections(
            type = type,
            grade = grade,
            semester = semester,
            book = book,
        )
    }.getOrNull()

    /**
     * 按类型拉数学练习知识点树（出题链路第一步）。
     *
     * GET `/leo-math/android/exams/exercises/type/{type}`。
     *
     * 这是「10 以内加减法 + 题目数量可选」的入口：`type` 传
     * [ExerciseType.exerciseType]（口算练习 = 0），拿到 `ExerciseScopeData`
     * 后从 `sections[].keypoints[]` 里挑一个知识点，再用它的 `id` 作为
     * `keypointId` 进答题页（原版 `QuickExerciseActivity`）。
     *
     * 四个参数都是原始 int（原版签名 `(IIII...)` 无 nullable）：
     *  - type     出题类型，见 [ExerciseType]
     *  - grade    年级 ID
     *  - semester 学期（1 上 / 2 下）
     *  - book     教材版本
     *
     * 失败返回 null，UI 显示空态。
     */
    suspend fun fetchMathScope(
        type: ExerciseType,
        grade: Int,
        semester: Int,
        book: Int,
    ): ExerciseScopeData? = runCatching {
        ServiceLocator.math.getExercisesKeyPoints(
            type = type.exerciseType,
            grade = grade,
            semester = semester,
            book = book,
        )
    }.onFailure {
        // 失败不静默：记入日志页（含 HTTP 状态/异常），便于定位「知识点拉取失败」。
        cn.apixiaoyuan.app.core.log.AppLogger.w(
            "Exercise",
            "知识点拉取失败 type=${type.exerciseType} grade=$grade semester=$semester book=$book: ${it.message}",
            it,
        )
    }.getOrNull()

    /**
     * 出题：按知识点生成一整套练习（出题链路第二步）。
     *
     * POST `/leo-math/android/exams`，FormUrlEncoded。
     *
     * @param keypointId 知识点 ID，来自 [ExerciseScopeKeypoint.id]
     * @param limit      题目数量，取自 [ExerciseType.chooseNumArray]
     *
     * 失败返回 null，UI 显示错误。
     */
    suspend fun fetchExam(
        keypointId: Int,
        limit: Int,
    ): ExamData? = fetchExamRaw(keypointId.toString(), limit)

    /**
     * 出题（字符串形态知识点 ID）。
     *
     * 与 [fetchExam] 同一条接口，只是不做 `Int` 转换 —— 刷分链路的知识点 ID
     * 来自配置项（字符串，可为空串表示自动扫描），且扫描时是 1..2^15 的整数
     * 序列，直接以字符串传更贴合接口签名（原版 `@Field("keypointId") String`）。
     *
     * 失败返回 null。
     */
    suspend fun fetchExamRaw(
        keypointId: String,
        limit: Int,
    ): ExamData? = runCatching {
        ServiceLocator.oral.getExamInfo(
            keypointId = keypointId,
            limit = limit.toString(),
        )
    }.getOrNull()

    /**
     * 出题（带 **429 频控重试**）—— 刷对局专用。
     *
     * ## ★ 为什么需要它（2026-09-28，对齐 pk-node 的 `runPractice`）
     *
     * 服务端对出题有**账号级冷却**（实测 ≈62s）。刷对局是按轮循环的，
     * 一旦配速估偏就会撞上 **429**：
     *  - [fetchExamRaw] 走 `runCatching` 把异常吞成 null，调用方**分不出**
     *    「限流」还是「网络错」，只能一律当失败 —— 那会白白浪费一轮；
     *  - 本方法把 429 单独识别出来，按 [ExercisePumpEngine.RETRY_INTERVAL_MS]
     *    重试，累计超过 [ExercisePumpEngine.RETRY_MAX_MS] 才放弃。
     *
     * 非 429 的失败（如 417/400）不重试 —— 那是协议/身份问题，等多久都没用。
     *
     * @param onRetry 每次重试前回调（给 UI 显示「限流等待中」）
     * @return 成功的 [ExamData]；限流未解除或其它错误返回 null。
     */
    suspend fun fetchExamWithRetry(
        keypointId: Int,
        limit: Int,
        onRetry: (String) -> Unit = {},
    ): ExamData? {
        val start = System.currentTimeMillis()
        var tries = 0
        while (true) {
            tries++
            val outcome = runCatching {
                ServiceLocator.oral.getExamInfo(
                    keypointId = keypointId.toString(),
                    limit = limit.toString(),
                )
            }
            outcome.getOrNull()?.let { return it }

            val t = outcome.exceptionOrNull()
            val code = (t as? retrofit2.HttpException)?.code()
            val rateLimited = code == 429 || (t?.message?.contains("频繁") == true)
            if (!rateLimited) {
                // 非限流：等也没用，直接放弃（调用方会把它记成一轮失败）。
                cn.apixiaoyuan.app.core.log.AppLogger.w(
                    "Exercise",
                    "出题失败（非限流）code=$code keypointId=$keypointId: ${t?.message}",
                    t,
                )
                return null
            }

            val waited = System.currentTimeMillis() - start
            if (waited >= ExercisePumpEngine.RETRY_MAX_MS) {
                onRetry(
                    "出题持续频控（已试 $tries 次 / ${waited / 1000}s）—— 放弃本轮",
                )
                return null
            }
            onRetry(
                "出题被限流（HTTP 429），${ExercisePumpEngine.RETRY_INTERVAL_MS / 1000}s 后重试" +
                    "（已等 ${waited / 1000}s）",
            )
            kotlinx.coroutines.delay(ExercisePumpEngine.RETRY_INTERVAL_MS)
        }
    }

    /**
     * 提交练习结果（出题链路第四步）。
     *
     * PUT `/leo-math/android/exams/v2/{examId}`，body 带 `@NeedEncode`。
     *
     * 提交前调用方须把 [ExamData.questions] 里每题填好
     * `userAnswer` / `status` / `costTime`（**下限 5ms**），
     * 以及整卷的 `correctCnt` / `costTime`。
     *
     * 失败返回 null。
     */
    suspend fun uploadExam(body: ExamData): ExamData? = runCatching {
        val examId = body.idString ?: return@runCatching null
        ServiceLocator.oral.uploadExamResult(examId = examId, body = body)
    }.getOrNull()

    /**
     * 增量上报经验值（`postSavedExp` · attend 端点）。
     *
     * body 结构已从 smali 逐行确证（`todayExercises: [增量记录]`），
     * 每个 `obtainExp` 是**本次获得的经验（增量）**，服务端累计到周分数 ——
     * 这是「自定义分数 = 增量模式」的协议基础（详见 [ScorePump] 的 KDoc）。
     *
     * 失败返回 false（含 417 solar-encoder —— sign 未破前必被拦）。
     */
    suspend fun postSavedExp(body: LeoTodayExerciseListData): Boolean = runCatching {
        ServiceLocator.exerciseLegacy.postSavedExp(body)
        true
    }.getOrDefault(false)
}
