package cn.apixiaoyuan.app.feature.pk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.apixiaoyuan.app.core.account.AccountRepository
import cn.apixiaoyuan.app.core.account.SubAccountItem
import cn.apixiaoyuan.app.core.pk.PkBattleEngine
import cn.apixiaoyuan.app.core.pk.PkBattleRepository
import cn.apixiaoyuan.app.core.pk.PkMode
import cn.apixiaoyuan.app.core.pk.PkPointItem
import cn.apixiaoyuan.app.core.pk.PkStrokeMode
import cn.apixiaoyuan.app.core.session.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PkGrindViewModel : ViewModel() {

    var points by mutableStateOf<List<PkPointItem>?>(null)
        private set

    var loadError by mutableStateOf<String?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    var totalWinCount by mutableStateOf<Int?>(null)
        private set

    var weekWinCount by mutableStateOf<Int?>(null)
        private set

    var currentUserId by mutableStateOf<Long?>(null)
        private set

    var subAccounts by mutableStateOf<List<SubAccountItem>?>(null)
        private set

    /** 子账号列表加载失败原因。null = 无错误（可能只是没有小号）。 */
    var subAccountsError by mutableStateOf<String?>(null)
        private set

    var running by mutableStateOf(false)
        private set

    var progress by mutableStateOf("")
        private set

    var message by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    init {
        refreshAll()
    }

    fun refreshAll() {
        if (loading) return
        loading = true
        loadError = null
        subAccountsError = null
        currentUserId = SessionStore.yfdU
        viewModelScope.launch {
            try {
                val grade = SessionStore.grade() ?: DEFAULT_GRADE
                val home = PkBattleRepository.fetchMathHome(grade)
                points = home.pointList
                totalWinCount = home.totalWinCount
                weekWinCount = home.weekWinCount
                runCatching { AccountRepository.fetchSubAccounts() }
                    .onSuccess { subAccounts = it.getOrNull() }
                    .onFailure { t ->
                        subAccounts = null
                        subAccountsError = "子账号需设备链登录态：${t.message ?: t}"
                    }
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    loadError = "拉取对局类型失败：${t.message ?: t}"
                }
            } finally {
                loading = false
            }
        }
    }

    fun switchSubAccount(item: SubAccountItem) {
        if (running) return
        viewModelScope.launch {
            try {
                message = null
                progress = "切换账号中…"
                AccountRepository.switchTo(item)
                progress = ""
                message = "已切换到 ${item.nickname}"
                refreshAll()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                progress = ""
                message = "切换失败：${t.message ?: t}"
            }
        }
    }

    /**
     * 开始刷局。
     *
     * 默认值全部对齐 pk-node（2026-10-02 移植）：
     * **不再预防性等 60 秒出题冷却** —— 换到 `match/v2` + `611` 之后出题立刻就通，
     * 那个 400 是旧协议被服务端按频控处理的产物。撞到 400/403 才按
     * [matchRetryIntervalMs] 自动重试；[roundIntervalMs] 只是下限，默认 0。
     */
    fun start(
        rounds: Int,
        pointId: Int,
        costTimeMs: Long?,
        submitDelayMinMs: Long = PkBattleEngine.DEFAULT_SUBMIT_DELAY_MIN_MS,
        submitDelayMaxMs: Long = PkBattleEngine.DEFAULT_SUBMIT_DELAY_MAX_MS,
        roundIntervalMs: Long = PkBattleEngine.DEFAULT_ROUND_INTERVAL_MS,
        rateLimitWaitMs: Long = PkBattleEngine.RATE_LIMIT_BASE_MS,
        matchRetryIntervalMs: Long = PkBattleEngine.MATCH_RETRY_INTERVAL_MS,
        matchRetryMaxMs: Long = PkBattleEngine.MATCH_RETRY_MAX_MS,
        strokeMode: PkStrokeMode,
    ) {
        if (running) return
        if (rounds <= 0) {
            message = "对局数必须 ≥ 1"
            return
        }
        if (pointId <= 0) {
            message = "请先选择对局类型"
            return
        }

        running = true
        message = null
        progress = "开始：刷 $rounds 局（知识点 $pointId）"

        job = viewModelScope.launch {
            try {
                // 记下最后一条事件：失败时把它带进结果文案。
                // 否则「完成 0/10 局」这种结果完全没有信息量 —— 用户看不到
                // 是 417 还是频控还是编码器没装载（2026-10-02 的排查教训）。
                var lastEvent = ""
                val result = PkBattleEngine.runBattle(
                    rounds = rounds,
                    modes = setOf(PkMode.MATH),
                    pointId = pointId,
                    costTimeMs = costTimeMs,
                    submitDelayMinMs = submitDelayMinMs,
                    submitDelayMaxMs = submitDelayMaxMs,
                    roundIntervalMs = roundIntervalMs,
                    rateLimitBaseMs = rateLimitWaitMs,
                    matchRetryIntervalMs = matchRetryIntervalMs,
                    matchRetryMaxMs = matchRetryMaxMs,
                    strokeMode = strokeMode,
                    onProgress = { _, done, total, ev ->
                        lastEvent = ev
                        progress = "[数学] $ev（$done/$total）"
                    },
                )
                running = false
                progress = ""
                val done = result[PkMode.MATH] ?: 0
                message = if (done >= rounds) {
                    "结束：完成 $done/$rounds 局"
                } else {
                    "结束：完成 $done/$rounds 局｜最后一条：$lastEvent"
                }
                refreshScoreOnly()
            } catch (c: CancellationException) {
                running = false
                progress = ""
                message = "已停止。"
                throw c
            } catch (t: Throwable) {
                running = false
                progress = ""
                message = "异常：${t.message ?: t}"
            }
        }
    }

    private fun refreshScoreOnly() {
        viewModelScope.launch {
            runCatching {
                val grade = SessionStore.grade() ?: DEFAULT_GRADE
                PkBattleRepository.fetchMathHome(grade)
            }.onSuccess { home ->
                totalWinCount = home.totalWinCount
                weekWinCount = home.weekWinCount
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    companion object {
        private const val DEFAULT_GRADE = 2
    }
}
