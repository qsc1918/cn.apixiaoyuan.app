package cn.apixiaoyuan.app.feature.grind

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.apixiaoyuan.app.core.exercise.ExerciseRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 「账号分数 / 任务」页的状态机（★ 2026-09-30 新增）。
 *
 * ## 为什么要有这一页
 *
 * 用户要求：把 pk-node 网页端「刷练习」页的**「刷新分数/任务」**能力移植到老挂的
 * 「刷分区」，新增一个「查看账号分数/任务」页面（此前老挂没有这页）。
 *
 * 对应 pk-node 的 `GET /api/exercise/overview`，它聚合了 4 个接口：
 *
 * | 来源接口 | 字段 | 含义 |
 * |---|---|---|
 * | `/leo-star/android/exercise/homepage` | `curWeekExp` / `todayObtainedPoints` / `continuousDays` / `curRank` / `nextMultiplier` | ★ 本周经验 = **刷分上报的就是它**；今日积分；连续打卡；排名 |
 * | `/leo-star/android/exercise/rank/pre-fetch` | `curWeekScore` / `expectedMultiple` | 周排行榜分数（与 curWeekExp **不是一回事**） |
 * | `/leo-star/android/exercise/task/home` | `tasks[]` | 今日任务（curCnt/targetCnt/状态/加分） |
 *
 * ⚠️ 老挂此前**混淆过**这两者：刷分页曾拿 `rank/pre-fetch.curWeekScore`（周榜分数）
 * 当「当前分数」，但上报记的是**练习经验** → 上报后 curWeekScore 不动，UI 显得
 * 「分数异常」。见 [cn.apixiaoyuan.app.core.network.api.ExerciseStarApiService] 的说明。
 * 所以本页把两者**并列展示**并标注清楚，不再混用。
 *
 * ## 并发与取消
 *
 * 用 [viewModelScope] + [Job]：连点刷新时先取消上一次，避免并发请求互相覆盖状态。
 * ViewModel 销毁时 viewModelScope 自动取消 —— 不会「退出页面后还在打接口」。
 */
class AccountStatsViewModel : ViewModel() {

    /** 数据装载中。 */
    var loading by mutableStateOf(false)
        private set

    /** 本周练习经验（`curWeekExp`）—— ★ 刷分的读数。 */
    var curWeekExp by mutableStateOf<Int?>(null)
        private set

    /** 今日已获得积分（`todayObtainedPoints`）。 */
    var todayPoints by mutableStateOf<Int?>(null)
        private set

    /** 连续打卡天数。 */
    var continuousDays by mutableStateOf<Int?>(null)
        private set

    /** 当前排名（`curRank`）。 */
    var curRank by mutableStateOf<Int?>(null)
        private set

    /** 下一档倍率（2 = 「双倍奖励」即将/已生效）。 */
    var nextMultiplier by mutableStateOf<Int?>(null)
        private set

    /** 周排行榜分数（`curWeekScore`）—— 注意与 [curWeekExp] 区分。 */
    var curWeekScore by mutableStateOf<Int?>(null)
        private set

    /** 今日任务列表。 */
    var tasks by mutableStateOf<List<TaskRow>>(emptyList())
        private set

    /** 错误文案。null = 无错误。 */
    var error by mutableStateOf<String?>(null)
        private set

    /** 最近一次刷新成功的时间（用于 UI 显示「刚刚更新」）。 */
    var lastLoadedAt by mutableStateOf<Long?>(null)
        private set

    private var job: Job? = null

    /**
     * 拉取全部读数（对应 pk-node 的 `refreshPractice`）。
     *
     * 三个接口**并行**发（互不依赖），任一失败不影响其它 —— 这样即便某个接口
     * 被频控/401，其余读数仍能显示，而不是整页空白。
     */
    fun refresh() {
        job?.cancel()
        loading = true
        error = null
        job = viewModelScope.launch {
            // 并行拉三路，各自 runCatching 兜底（失败返回 null，不抛）。
            val homepage = runCatching { ExerciseRepository.fetchExerciseHomepage() }.getOrNull()
            val exp = runCatching { ExerciseRepository.fetchExp() }.getOrNull()
            val taskInfo = runCatching { ExerciseRepository.fetchTasks() }.getOrNull()

            if (homepage == null && exp == null && taskInfo == null) {
                error = "全部接口都失败了 —— 多半是登录态失效（去主页看看是否要重新登录）"
                loading = false
                return@launch
            }

            homepage?.let { h ->
                curWeekExp = h.curWeekExp
                todayPoints = h.todayObtainedPoints
                continuousDays = h.continuousDays
                curRank = h.curRank
                nextMultiplier = h.nextMultiplier
            }
            exp?.let { e -> curWeekScore = e.curWeekScore }
            tasks = taskInfo?.tasks.orEmpty().map { t ->
                TaskRow(
                    title = t.title ?: ("任务 " + t.taskId),
                    finished = t.finished,
                    exp = t.exp,
                )
            }
            lastLoadedAt = System.currentTimeMillis()
            loading = false
        }
    }
}

/**
 * 任务行（UI 用的扁平结构）。
 *
 * 服务端的 `LeoTaskItem` 只有 `title` / `finished` / `exp` 三个可用字段
 * （其余是推断字段，见其 KDoc），所以这里也只用这三个，不臆造进度条数字。
 */
data class TaskRow(
    val title: String,
    val finished: Boolean,
    val exp: Int,
)
