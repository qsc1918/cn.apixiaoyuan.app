package cn.apixiaoyuan.app.core.design.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs

/**
 * 日志/流水列表的**自动跟随滚动**（★ 2026-09-30 v4：二阶阻尼弹簧引擎）。
 *
 * ## 用户要的语义（逐字）
 *
 * > 「当到达底部时会介入自动滚动，当用户上滑后关闭自动滚动，
 * >   当下滑到底后又介入自动滚动，接着滚动要有丝滑的动画」
 * >
 * > 「你延迟 1 条日志当缓冲然后按照日志速度匹配滚动速度连贯起来」
 * > 「滚动的速度应该和日志输出的速度匹配，做到连贯性下滑，看起来很快，就和 DeepSeek 一样」
 *
 * ## ★★ v4 的依据：dsh-smooth-stream 的「弹簧跟随引擎」
 *
 * 用户直接给了参考实现的原理：**不要用 tween 插值一段一段滚**，而是用
 * **二阶阻尼弹簧**把「内容高度的离散变化」转成**每帧连续的速度/位移轨迹**：
 *
 * ```
 * 内容高度变化 --> 弹簧跟随引擎(k=130,c=24,m=1) --> 逐帧连续位移
 * ```
 *
 * 核心是**逐帧半隐式欧拉积分**：
 *
 * ```
 * a = (k * (target - x) - c * v) / m     // 弹簧力 - 阻尼力
 * v += a * dt
 * x += v * dt
 * ```
 *
 * 它天然解决 v2/v3 的两个问题：
 *  - **不会一顿一顿**：新内容只是把 `target`（弹簧的静止点）抬高，
 *    速度 `v` 是**连续的**（不会像 tween 那样每次重启都归零）；
 *  - **速度自然匹配输出**：日志连续来 → target 持续抬高 → 弹簧维持一个
 *    稳定的追赶速度，看起来就是「连贯下滑」。
 *
 * ## 掉帧自愈（对齐 dsh）
 *
 * 主线程卡顿时 `dt` 会很大 —— 若直接积分，恢复瞬间会「突进瞬移」。
 * 所以把 `dt` **钳位在 ≤ 32ms**（见 [MAX_DT]）。
 *
 * ## 「1 条缓冲」
 *
 * 内容追加后先 `withFrameNanos {}` 等一帧让布局完成（此时 `maxValue`
 * 才是新值），再把它设为弹簧的新 `target`。这一帧就是用户说的「缓冲」。
 *
 * ## 实现注意：suspend 的边界
 *
 * `withFrameNanos { }` 的 lambda **不是 suspend**，所以**积分在帧内、
 * 应用位移在帧外**（`scrollBy` 是 suspend）。每帧存的位移放在局部变量里，
 * 出 lambda 后再 `scrollBy`。
 *
 * ## 用户主动拖拽 → 关闭跟随
 *
 * 用 [SelfScrollFlag] 区分「我们自己的 `scrollBy`」与「用户手指」：
 * `scrollBy` 期间 `isScrollInProgress` 也为 true，若不区分，
 * **我们自己的跟随会被误判成用户在翻阅而永久关掉** —— 这正是前几版
 * 「滚两下就不滚了」的死因。
 */
private const val SPRING_K = 130f
private const val SPRING_C = 24f
private const val SPRING_M = 1f

/** 掉帧钳位：单帧最多按 32ms 积分（对齐 dsh）。 */
private const val MAX_DT = 0.032f

/** 速度阈值：|v| 小于它就认为弹簧已静止。 */
private const val REST_VELOCITY = 1.0f

/** 首帧 / 兜底 dt（约 60fps）。 */
private const val FALLBACK_DT = 1f / 60f

/** 位移小于它就忽略（亚像素抖动）。 */
private const val MIN_STEP = 0.5f

/**
 * 「当前滚动是我们自己发起的」标记（不参与重组，故不用 Compose 状态）。
 */
private class SelfScrollFlag {
    @Volatile
    var on = false
}

/** 配 `Modifier.verticalScroll(state)` 使用。 */
@Composable
fun AutoFollowScroll(
    state: ScrollState,
    itemCount: Int,
    thresholdPx: Int = 48,
) {
    var autoFollow by remember { mutableStateOf(true) }
    val selfScroll = remember { SelfScrollFlag() }

    // ① 手势 / 位置 → 开关（只在「用户主动拖拽」时关闭跟随）。
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.isScrollInProgress, state.value, state.maxValue) }
            .collect { (inProgress, value, max) ->
                val atBottom = max <= 0 || value >= max - thresholdPx
                if (atBottom) {
                    autoFollow = true
                } else if (inProgress && !selfScroll.on) {
                    autoFollow = false
                }
            }
    }

    // ② 内容追加 → 1 帧缓冲 + 弹簧逐帧跟随。
    LaunchedEffect(itemCount) {
        if (!autoFollow || itemCount <= 0) return@LaunchedEffect
        withFrameNanos { }   // 「1 条缓冲」：等布局完成，maxValue 才是新值
        var target = state.maxValue.toFloat()
        var x = state.value.toFloat()
        var v = 0f
        var lastFrame = 0L
        var guard = 0
        while (autoFollow && guard++ < 100_000) {
            // 每帧重读目标（新日志可能又来了）。
            val newMax = state.maxValue.toFloat()
            if (newMax > target) target = newMax
            var step = 0f
            withFrameNanos { now ->
                val dt = if (lastFrame == 0L) {
                    FALLBACK_DT
                } else {
                    ((now - lastFrame) / 1_000_000_000f).coerceIn(0f, MAX_DT)
                }
                lastFrame = now
                // 半隐式欧拉：a = (k*(target-x) - c*v) / m
                val a = (SPRING_K * (target - x) - SPRING_C * v) / SPRING_M
                v += a * dt
                x += v * dt
                step = x - state.value
            }
            if (abs(step) >= MIN_STEP) {
                selfScroll.on = true
                state.scrollBy(step)
                selfScroll.on = false
            }
            if (target - state.value <= 0.5f && abs(v) < REST_VELOCITY) break
        }
    }
}

/** 配 `LazyColumn(state = listState)` 使用（按「距尾项像素」建模）。 */
@Composable
fun AutoFollowScrollLazy(
    lazyState: LazyListState,
    itemCount: Int,
) {
    var autoFollow by remember { mutableStateOf(true) }
    val selfScroll = remember { SelfScrollFlag() }

    // ① 视口是否到尾项 + 手势 → 开关。
    LaunchedEffect(lazyState) {
        snapshotFlow {
            val info = lazyState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            Triple(
                lazyState.isScrollInProgress,
                last == null || last.index >= info.totalItemsCount - 1,
                info.totalItemsCount,
            )
        }.collect { (inProgress, atBottom, _) ->
            if (atBottom) {
                autoFollow = true
            } else if (inProgress && !selfScroll.on) {
                autoFollow = false
            }
        }
    }

    // ② 内容追加 → 1 帧缓冲 + 弹簧逐帧跟随到尾项。
    LaunchedEffect(itemCount) {
        if (!autoFollow || itemCount <= 0) return@LaunchedEffect
        withFrameNanos { }
        var v = 0f
        var lastFrame = 0L
        var guard = 0
        while (autoFollow && guard++ < 100_000) {
            val info = lazyState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: break
            val atEnd = last.index >= info.totalItemsCount - 1
            // 「剩余距离」= 尾项底边 - 视口底边（没看到尾项时视为一个视口高）。
            val target = if (atEnd) {
                (last.offset + last.size) - info.viewportEndOffset
            } else {
                info.viewportEndOffset
            }
            if (target <= 0) break
            var step = 0f
            withFrameNanos { now ->
                val dt = if (lastFrame == 0L) {
                    FALLBACK_DT
                } else {
                    ((now - lastFrame) / 1_000_000_000f).coerceIn(0f, MAX_DT)
                }
                lastFrame = now
                val a = (SPRING_K * target.toFloat() - SPRING_C * v) / SPRING_M
                v += a * dt
                step = v * dt
            }
            if (step >= MIN_STEP) {
                selfScroll.on = true
                lazyState.scrollBy(step)
                selfScroll.on = false
            }
            // 到尾部且速度归零即停。
            val info2 = lazyState.layoutInfo
            val last2 = info2.visibleItemsInfo.lastOrNull()
            if (last2 != null && last2.index >= info2.totalItemsCount - 1 &&
                (last2.offset + last2.size) <= info2.viewportEndOffset + 1 && abs(v) < REST_VELOCITY
            ) {
                break
            }
        }
    }
}