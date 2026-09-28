package cn.apixiaoyuan.app.core.pk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * PK 秒结算 + 循环 + 多玩法并发的引擎。
 *
 * ## 语义（用户拍板，2026-09-26）
 *
 * - 「秒结算」：出题 → 解码 → 每题答案填对 → 直接提交（不等真实作答）。
 * - 「循环 PK」：每个玩法连续刷 N 轮（N = 用户设的轮数）。
 * - 「多玩法并发」：勾选 math / multi / final / 语文 几个玩法，就**同时**起
 *   几个协程各自循环 —— **不是同一玩法并发多局**。
 * - 「失败重试」：每轮出题/提交失败按退避策略重试（上限可配）。
 *
 * ## 为什么 Engine 与 ViewModel 分开
 *
 * 与练习线 [ScorePump] 的分层一致：算法（循环/并发/退避）在 Engine，
 * ViewModel 只做「起/停一个 Job + 把进度转文案」。这样 Engine 可单测、
 * 不依赖 Compose 状态。
 */
object PkBattleEngine {

    /** 默认失败重试上限。 */
    const val DEFAULT_MAX_RETRY = 3

    /** 默认重试基础退避（毫秒）。 */
    const val DEFAULT_RETRY_BASE_MS = 800L

    /** 默认每局间隔（毫秒），0 = 不等待。 */
    const val DEFAULT_ROUND_INTERVAL_MS = 0L

    /**
     * 命中**频控**时的退避基数（毫秒）。
     *
     * ## 为什么频控要单独一套退避（2026-09-27，待办 16）
     *
     * 真机实测：提交接口（`PUT .../math/pk/submit`）的频控**独立于出题接口**，
     * 窗口约 10 分钟级。旧实现把 403 当普通失败，按 `retryBaseMs << n`
     * （默认 800ms → 1.6s → 3.2s）重试 —— 三个回合全在窗口内，
     * 等于**白打三次**，还会把窗口推得更长（真机日志里那一串
     * `match → 400 请求过于频繁` 就是这么来的）。
     *
     * 所以频控改用大得多的基数并**限次**：默认最多等 2 次、每次
     * 60s / 120s。等不到的就不必继续烧请求了 —— 那属于「服务端让你停」，
     * 不是「客户端要重试」。
     */
    const val RATE_LIMIT_BASE_MS = 60_000L

    /** 频控最多退避重试次数。 */
    const val RATE_LIMIT_MAX_WAIT = 2

    /**
     * 跑一轮 PK 战斗（多玩法并发，各自循环）。
     *
     * @param rounds           每个玩法要刷的轮数（≥1）
     * @param modes            要并发的玩法集合（勾选哪些跑哪些）
     * @param pointId          知识点 ID（PK 首页 pointList 提供，默认 1）
     * @param maxRetry         每轮出题/提交失败的重试上限（≥1）
     * @param retryBaseMs      重试基础退避毫秒（每次失败按 2^n 倍退避，加随机抖动）
     * @param rateLimitBaseMs  命中频控时的退避基数（默认 [RATE_LIMIT_BASE_MS]）
     * @param roundIntervalMs  每轮之间的固定间隔（毫秒）
     * @param costTimeMs       每局提交的整卷耗时；null = 由题数 × 下限推导
     * @param onProgress       (玩法, 已完成轮数, 总轮数, 事件文本) —— UI 显示进度
     * @return 各玩法最终完成轮数（含失败导致的不足 rounds 的情况）
     */
    suspend fun runBattle(
        rounds: Int,
        modes: Set<PkMode>,
        pointId: Int,
        maxRetry: Int = DEFAULT_MAX_RETRY,
        retryBaseMs: Long = DEFAULT_RETRY_BASE_MS,
        rateLimitBaseMs: Long = RATE_LIMIT_BASE_MS,
        roundIntervalMs: Long = DEFAULT_ROUND_INTERVAL_MS,
        costTimeMs: Long? = null,
        submitDelayMs: Long = 0L,
        strokeMode: PkStrokeMode = PkStrokeMode.ARC,
        onProgress: (PkMode, Int, Int, String) -> Unit = { _, _, _, _ -> },
    ): Map<PkMode, Int> = coroutineScope {
        require(rounds >= 1) { "轮数必须 ≥1" }
        require(modes.isNotEmpty()) { "至少勾选一个玩法" }

        modes.associateWith { mode ->
            async {
                var done = 0
                for (round in 1..rounds) {
                    val ok = runOneRound(
                        mode = mode,
                        pointId = pointId,
                        maxRetry = maxRetry,
                        retryBaseMs = retryBaseMs,
                        rateLimitBaseMs = rateLimitBaseMs,
                        costTimeMs = costTimeMs,
                        submitDelayMs = submitDelayMs,
                        strokeMode = strokeMode,
                        onEvent = { ev -> onProgress(mode, done, rounds, ev) },
                    )
                    if (ok) {
                        done++
                        onProgress(mode, done, rounds, "第 $round/$rounds 轮完成")
                    } else {
                        onProgress(mode, done, rounds, "第 $round/$rounds 轮失败（已达重试上限）")
                        // 失败不中断整个玩法循环，继续下一轮 —— 用户要的是「失败重试」
                        // 不是「失败即停」。
                    }
                    if (round < rounds && roundIntervalMs > 0) {
                        delay(roundIntervalMs)
                    }
                }
                done
            }
        }.mapValues { (_, d) -> d.await() }
    }

    /**
     * 跑一局：出题 → 组装全对 body → 提交，带失败重试。
     *
     * ## 两类失败，两套策略（2026-09-27，待办 16）
     *
     *  - **频控**（[PkHttpException.isRateLimited]）：退避 [RATE_LIMIT_BASE_MS] × 2^n，
     *    最多 [RATE_LIMIT_MAX_WAIT] 次。频控是「服务端让你停」，密集重试只会
     *    延长窗口（真机已实测到这个后果），所以等的时间必须比普通重试大两个数量级。
     *  - **其他失败**：原有指数退避（`retryBaseMs << n` + 抖动），最多 [maxRetry] 次。
     *
     * 计数分开：频控等待不计入 [maxRetry]，否则「1 次 403 + 2 次普通重试」
     * 会在窗口还没过时就宣告整局失败。
     *
     * @return true = 本局成功（出题+提交都成功）；false = 重试耗尽仍失败。
     */
    private suspend fun runOneRound(
        mode: PkMode,
        pointId: Int,
        maxRetry: Int,
        retryBaseMs: Long,
        rateLimitBaseMs: Long,
        costTimeMs: Long?,
        submitDelayMs: Long,
        strokeMode: PkStrokeMode,
        onEvent: (String) -> Unit,
    ): Boolean {
        var attempt = 0
        var rateLimitWaits = 0
        while (true) {
            try {
                onEvent("出题中…")
                val match = PkBattleRepository.fetchMatch(mode, pointId)
                onEvent("出题成功（${match.examVO?.questions?.size ?: 0} 题），组装提交…")
                if (submitDelayMs > 0) {
                    onEvent("等待 ${submitDelayMs}ms 后提交…")
                    delay(submitDelayMs)
                }
                val body = PkBattleRepository.buildSubmitBody(match, costTimeMs, strokeMode)
                onEvent("提交中…")
                PkBattleRepository.submit(mode, body)
                // ★ 提交 200 ≠ 已结算（2026-09-28，对齐 pk-node）：
                //   提交被 403 的局，服务端同样留 {correctCnt:0, questions:null} 的占位记录。
                //   必须再查一次结算明细，以服务端结算为准 —— 否则会把「没算上」报成成功。
                val pkIdStr = body.pkIdStr
                val settle = if (pkIdStr.isNullOrBlank()) {
                    null
                } else {
                    onEvent("提交成功，核对结算…")
                    PkBattleRepository.fetchHistoryDetail(pkIdStr)
                }
                when {
                    settle == null -> {
                        // 查询失败（网络/超时）：不能断言失败 —— 真机上可能只是历史还没落库。
                        onEvent("提交成功（结算明细未取到，无法确认是否计入）")
                    }
                    settle.settled -> {
                        onEvent("已结算：答对 ${settle.correctCnt}/${settle.questionCnt} 题")
                    }
                    else -> {
                        onEvent(
                            "⚠ 服务端未结算（correctCnt=${settle.correctCnt}, " +
                                "questions=${if (settle.questions == null) "null" else settle.questions.size}）" +
                                "—— 这局没算上"
                        )
                        return false
                    }
                }
                return true
            } catch (c: CancellationException) {
                throw c
            } catch (rl: PkHttpException) {
                if (!rl.isRateLimited) {
                    onEvent("提交被拒（HTTP ${rl.code}）：${rl.body.take(120)}")
                    return false
                }
                if (rateLimitWaits >= RATE_LIMIT_MAX_WAIT) {
                    onEvent(
                        "频控未解除（HTTP ${rl.code}，已等待 $rateLimitWaits 次）—— " +
                            "停止本局。该接口频控窗口约十分钟量级，请稍后再试。"
                    )
                    return false
                }
                rateLimitWaits++
                val wait = rateLimitBaseMs * (1L shl (rateLimitWaits - 1))
                onEvent(
                    "命中频控（HTTP ${rl.code}），等待 ${wait / 1000}s 后重试" +
                        "（第 $rateLimitWaits/$RATE_LIMIT_MAX_WAIT 次）"
                )
                delay(wait)
            } catch (t: Throwable) {
                attempt++
                if (attempt >= maxRetry) {
                    onEvent("失败：${t.message ?: t}（已重试 $attempt 次）")
                    return false
                }
                // 指数退避 + 随机抖动，避免多玩法并发时同频重试。
                val backoff = retryBaseMs * (1L shl (attempt - 1))
                val jitter = Random.nextLong(0, backoff.coerceAtLeast(1) + 1)
                onEvent("失败：${t.message ?: t}，第 $attempt 次重试（${backoff + jitter}ms 后）")
                delay(backoff + jitter)
            }
        }
    }
}