package cn.apixiaoyuan.app.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 练习星级首页（`/leo-star/android/exercise/homepage`）。
 *
 * ## 为什么刷分要读这个而不是 `rank/pre-fetch`
 *
 * 2026-09-27 真机实证（本地复现 + 线上响应逐字段核对）：
 *
 * | 接口 | 含义 | 刷分上报后 |
 * |---|---|---|
 * | `/leo-star/android/exercise/rank/pre-fetch` | **周排行榜**分数（`curWeekScore` / `curRank`） | **不变** |
 * | `/leo-star/android/exercise/homepage` | **练习周经验**（`curWeekExp` / `todayObtainedPoints`） | **涨** |
 *
 * 刷分走 `postSavedExp`（= `rank/login/attend`），它记的是**练习经验**，
 * 所以要看 `homepage.curWeekExp`。此前刷分页读的是 `rank/pre-fetch.curWeekScore`，
 * 属于**读错接口**——上报成功也看不出任何变化（真机症状：「分数显示异常」）。
 *
 * 线上真实响应（2026-09-27，未登录态也可拿到）：
 * ```json
 * {"ver":"1.0","status":200,"data":{
 *   "continuousDays":1,"checkinToday":true,
 *   "rankId":5259435,"curRank":42,
 *   "curWeekExp":0,"preWeekRankLevel":400,"curWeekRankLevel":500,
 *   "rankStat":2,"content":"要加油啊!",
 *   "nextMultiplier":2,"todayObtainedPoints":10,
 *   "timeTillNextRankCycle":37399310,"everInRank":true}}
 * ```
 * 对照原版 `LeoExerciseHomepageData.smali` 逐字段落盘。
 */
@Serializable
data class ExerciseHomepageData(
    /** 连续练习天数。 */
    @SerialName("continuousDays") val continuousDays: Int = 0,
    /** 今天是否已打卡。 */
    @SerialName("checkinToday") val checkinToday: Boolean = false,
    @SerialName("rankId") val rankId: Long = 0L,
    @SerialName("curRank") val curRank: Int = 0,
    /** ★ **本周练习经验**——刷分上报的就是它。 */
    @SerialName("curWeekExp") val curWeekExp: Int = 0,
    @SerialName("preWeekRankLevel") val preWeekRankLevel: Int = 0,
    @SerialName("curWeekRankLevel") val curWeekRankLevel: Int = 0,
    @SerialName("rankStat") val rankStat: Int = 0,
    @SerialName("content") val content: String = "",
    /** 下一档倍率（如 2 = 「双倍奖励」即将生效）。 */
    @SerialName("nextMultiplier") val nextMultiplier: Int = 0,
    /** ★ **今天已获得的积分**——刷分的直接产物。 */
    @SerialName("todayObtainedPoints") val todayObtainedPoints: Int = 0,
    @SerialName("timeTillNextRankCycle") val timeTillNextRankCycle: Long = 0L,
    @SerialName("everInRank") val everInRank: Boolean = false,
)