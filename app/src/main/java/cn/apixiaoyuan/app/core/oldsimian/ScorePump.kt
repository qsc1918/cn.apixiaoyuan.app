package cn.apixiaoyuan.app.core.oldsimian

import cn.apixiaoyuan.app.core.exercise.ExerciseRepository
import cn.apixiaoyuan.app.core.model.LeoTodayExerciseData
import cn.apixiaoyuan.app.core.model.LeoTodayExerciseListData
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

/**
 * 练习经验增量上报（「自定义分数」· 增量模式）。
 *
 * ## 语义（用户拍板：增量模式 B —— 独立增量上报）
 *
 * 逐行取证后确证，原版 `postSavedExp` 就是**增量上报接口**：
 *
 * ```
 * POST /leo-star/android/exercise/rank/login/attend
 * body: {"todayExercises": [{"finishTime": ms, "obtainExp": 增量, "ruleType": 类型}]}
 * ```
 *
 * 每个 `LeoTodayExerciseData` 是一条**增量记录**（`obtainExp` = 本次获得经验，
 * 非总分），服务端累计到周分数。这正对应「给一个增量值直接上报」——
 * **不需要取卷、不需要整卷上传、不需要循环逼近**。
 *
 * 与整卷上传（`uploadExamResult`）的关系：那条是原版真实练习的提交链路
 * （服务端按卷算分），本条是原版「练习完成后的经验记账」链路 ——
 * 两者并存，增量的表达只在后者。
 *
 * ## 实现依据（smali 逐行）
 *
 *  - body 结构：`LeoTodayExerciseListData.smali`（唯一字段 `todayExercises`）
 *    + `LeoTodayExerciseData.smali`（构造 `(JII)V`：finishTime/obtainExp/ruleType）；
 *  - 「当天」过滤：`LeoExerciseCommonDataStore.k()` 用 `ds/b1.K(finishTime)`
 *    判是否今日 —— 上报时 finishTime 必须落在今天，否则会被服务端/原版侧逻辑忽略；
 *  - 单条上限：参考项目 `Score.perItem` 默认 200（服务端单条上限），
 *    大增量拆成多条（每条 ≤200）分批上报。
 *
 * ## ⚠️ 已修（2026-09-27）：原实现「拆条」是错的
 *
 * 旧实现把大增量拆成多条、每条 `obtainExp<=200`（照搬参考项目 cn.nizou.sxd），
 * 这在**协议上是错的**：
 *
 *  - `obtainExp` 的语义是「**本次练习获得的经验**」，不是「一次上报的额度」；
 *    服务端是**按条记账**，拆 N 条 = N 次「完成练习」；
 *  - 真实客户端从不会为了一次练习发两条记录 —— 拆条属于**伪造行为**
 *    （同一 finishTime 多条记录），有被风控识别的风险；
 *  - 单条 `obtainExp` 的 200 是**服务端 clamp 上限**：发 200 与发 9999 同样只算 200，
 *    所以拆条**并不比单条多拿分**，纯属多余且更脏。
 *
 * 现实现改为：**一次上报一条记录**（`obtainExp = delta`），由服务端决定实际入账值；
 * 返回服务端给的真实值（`getCurrentUserExp().curWeekScore` 差值），而不是自报的数。
 * 多个 `ruleType` 可用时，一次批量发**不同 ruleType 的**记录（那才是原版语义）。
 */
object ScorePump {

    /**
     * 单条增量的**参考上限**（服务端 clamp）。
     *
     * 用于 UI 提示：超过这个数的部分服务端不会记账，想多拿分应改用
     * [pumpRuleTypes]（不同 ruleType 各自记账）。
     */
    const val PER_ITEM_MAX = 200

    /**
     * ★ 可记账的 `ruleType`（**pk-node 实测**，2026-09-28）。
     *
     * 全量枚举 0~43 后，**只有 `0` 和 `1` 会让分数真正增加**；
     * 其余（2..16, 20, 33, 41, 43）服务端都返回 **HTTP 200 但静默不记账**。
     *
     * ## 由此推出的日上限：**400**（`200 × 2`）
     *
     * **同一个 `ruleType` 一天只记一次** —— 第二次发返回 `200 {data:true}`
     * 但分数不动（服务端按 ruleType/天去重，静默丢弃）。
     * 所以想多拿分**只能靠不同 ruleType**，把同一次增量拆成多条**完全无用**
     * （这也从侧面印证了 2026-09-27 那次「拆条是错的」判断）。
     */
    val PUMP_RULE_TYPES = listOf(0, 1)

    /**
     * 单次请求 body 里最多放多少条记录（对应「多个 ruleType」场景）。
     * 不再用于「同一增量拆条」。
     */
    const val MAX_ITEMS_PER_BATCH = 50

    /**
     * 上报一次经验增量。
     *
     * @param delta    本次增量（`obtainExp`）。正数；超过 [PER_ITEM_MAX] 的部分
     *                 服务端会 clamp —— 本函数**照发不误**，让服务端决定入账值。
     * @param ruleType 规则类型（`LeoTodayExerciseData.ruleType`）。
     * @return 成功时返回 `Result.success(服务端实际入账的增量)`；
     *         失败/无法确认时 `Result.failure`。
     */
    suspend fun pumpDelta(
        delta: Int,
        ruleType: Int = 0,
        onProgress: (reported: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<Int> {
        require(delta > 0) { "增量必须为正数，收到 $delta" }

        val before = readScore()
        onProgress(0, delta)

        val ok = ExerciseRepository.postSavedExp(
            LeoTodayExerciseListData(
                todayExercises = listOf(
                    LeoTodayExerciseData(
                        finishTime = System.currentTimeMillis(),
                        obtainExp = delta,
                        ruleType = ruleType,
                    ),
                ),
            ),
        )
        if (!ok) return Result.failure(IllegalStateException("上报失败（网络错误或服务端拒绝）"))

        // 以服务端返回为准：读一次最新周分数，差值才是真正入账的增量。
        val after = readScore() ?: return Result.failure(
            IllegalStateException("已上报，但读取最新分数失败（网络错误或登录态失效）"),
        )
        val applied = after - (before ?: after)
        onProgress(applied, delta)
        return Result.success(applied)
    }

    /**
     * 批量上报**多个 ruleType** 的同一次增量（原版语义：不同练习类型各记一笔）。
     *
     * 这是「想多拿分」的正确姿势 —— 不是把一次练习拆成 N 条，而是走不同规则类型。
     *
     * 可用的 ruleType **已确证**：见 [PUMP_RULE_TYPES]（只有 `0` / `1`）。
     * 参数仍由调用方传入（便于将来服务端放开更多类型时无需改这里），
     * 传空/重复值会去重，避免同一规则被重复发（服务端按 ruleType/天去重，重复发也无用）。
     *
     * @param delta     每个 ruleType 的增量。
     * @param ruleTypes 要使用的规则类型集合。
     */
    suspend fun pumpRuleTypes(
        delta: Int,
        ruleTypes: List<Int>,
        onProgress: (reported: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<Int> {
        require(delta > 0) { "增量必须为正数，收到 $delta" }
        val types = ruleTypes.distinct()
        if (types.isEmpty()) return Result.failure(IllegalArgumentException("ruleTypes 不能为空"))

        val total = delta * types.size
        val before = readScore()
        var done = 0

        val ok = ExerciseRepository.postSavedExp(
            LeoTodayExerciseListData(
                todayExercises = types.take(MAX_ITEMS_PER_BATCH).map {
                    LeoTodayExerciseData(
                        finishTime = System.currentTimeMillis(),
                        obtainExp = delta,
                        ruleType = it,
                    )
                },
            ),
        )
        if (!ok) return Result.failure(IllegalStateException("上报失败（网络错误或服务端拒绝）"))
        done = total
        onProgress(done, total)

        val after = readScore() ?: return Result.failure(
            IllegalStateException("已上报，但读取最新分数失败"),
        )
        return Result.success(after - (before ?: after))
    }

    /** 读当前周练**经验**（`homepage.curWeekExp`）；失败返回 null。 */
    private suspend fun readScore(): Int? =
        runCatching { ExerciseRepository.fetchExerciseHomepage()?.curWeekExp }.getOrNull()
}
