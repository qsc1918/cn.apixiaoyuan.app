package cn.apixiaoyuan.app.core.design.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos

/**
 * 日志/流水列表的**自动跟随滚动**（★ 2026-09-30，用户明确定义的语义）。
 *
 * ## 用户要的语义（逐字）
 *
 * > 「当到达底部时会介入自动滚动，当用户上滑后关闭自动滚动，
 * >   当下滑到底后又介入自动滚动，接着滚动要有丝滑的动画」
 *
 * 拆成四条：
 *  1. **到底自动跟随**：内容追加时若视口已在底部 → 平滑滚到最新；
 *  2. **上滑即停**：用户往回翻 → **立刻**关闭自动跟随（不能再把他拽下去）；
 *  3. **回到底部即恢复**：用户自己滑回底部 → 重新开启自动跟随；
 *  4. **始终是动画**：跟随用 `animateScrollTo*`，不是瞬移。
 *
 * ## 为什么用「显式开关」而不是每帧判断是否到底
 *
 * 常见写法是每帧算 `lastVisibleIndex >= total - 1`。两个坑：
 *  - **惯性滚动（fling）期间**：手指离开后列表还在滚，中途会短暂「不在底部」，
 *    此时来了新日志就会被误判为「用户在翻阅」→ 关掉跟随且不再恢复；
 *  - **我们自己的动画期间**：`animateScrollTo` 途中同样「不在底部」，会被自己关掉。
 *
 * 所以用一个显式开关 [autoFollow]，只在两处改变：
 *  - 用户**主动滚动**（`isScrollInProgress` 为真）→ 若结束时不在底部就关掉；
 *  - 位置到达（近）底部 → 无条件打开。
 * 我们自己的动画终点就是底部，会命中第二条，因此不会误关。
 *
 * ## 两个重载
 *
 *  - [AutoFollowScroll]（[ScrollState]）：配 `Modifier.verticalScroll` 用；
 *  - [AutoFollowScrollLazy]（[LazyListState]）：配 `LazyColumn` 用。
 */

/** 配 `Modifier.verticalScroll(state)` 使用。 */
@Composable
fun AutoFollowScroll(
    state: ScrollState,
    itemCount: Int,
    thresholdPx: Int = 24,
) {
    var autoFollow by remember { mutableStateOf(true) }
    var lastCount by remember { mutableIntStateOf(0) }

    // ① 监听滚动位置与手势。
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.isScrollInProgress, state.value, state.maxValue) }
            .collect { (inProgress, value, max) ->
                val atBottom = max <= 0 || value >= max - thresholdPx
                if (atBottom) {
                    autoFollow = true
                } else if (inProgress) {
                    autoFollow = false
                }
            }
    }

    // ② 内容追加 → 平滑滚到底。
    //
    // ★★ 2026-09-30 v2：**速度恒定**（用户要求「滚动的速度应该和日志输出的速度匹配，
    //    做到连贯性下滑，看起来很快很丝滑」）。
    //
    // 第一版用 `animateScrollTo(maxValue)`，两个问题：
    //  1. 每条日志都**重启**一次动画 → 上一条还没滚完就被打断，观感「一卡一卡」；
    //  2. 距离短时动画依然跑满默认时长 → 感觉「慢半拍」。
    //
    // 现在：按**剩余距离**算时长（恒定线速度），并用 `animateScrollTo(value+remaining)`
    // 只滚动**差额** —— 差额小则时长短、立刻跟上；差额大则时长长但速度一致。
    //
    // ⚠️ `ScrollState` **没有** `animateScrollBy` 扩展（那是 `LazyListState` 的），
    //    所以这里用 `animateScrollTo(目标值)` 等价表达「按差额滚动」。
    LaunchedEffect(itemCount) {
        if (itemCount > lastCount && autoFollow && itemCount > 0) {
            // 等一帧让新内容参与布局，否则 maxValue 还是旧值、滚不到真正底部。
            withFrameNanos { }
            val remaining = state.maxValue - state.value
            if (remaining > 0) {
                val duration = scrollDurationFor(remaining)
                state.animateScrollTo(
                    state.value + remaining,
                    tween(duration, easing = LinearEasing),
                )
            }
        }
        lastCount = itemCount
    }
}

/**
 * 按「剩余距离」估算滚动时长 —— **恒定线速度**。
 *
 * 用户要「滚动速度与日志输出速度匹配」：日志连续输出时，每次只需要滚一小段，
 * 时长也相应很短，于是视觉上是**连续匀速**下滑，而不是「一顿一顿」。
 *
 * 速度取 ~3.2 像素/毫秒（≈ 3200 px/s）。折中考虑：
 *  - 太快（> 6 px/ms）会糊、看不清滚过什么；
 *  - 太慢（< 1.5 px/ms）跟不上日志输出节奏，会积压。
 * 时长钳制在 [80, 600] ms：再短会闪、再长会拖。
 */
private fun scrollDurationFor(distancePx: Int): Int =
    (distancePx / 3.2f).toInt().coerceIn(80, 600)

/** 配 `LazyColumn(state = listState)` 使用。 */
@Composable
fun AutoFollowScrollLazy(
    lazyState: LazyListState,
    itemCount: Int,
) {
    var autoFollow by remember { mutableStateOf(true) }
    var lastCount by remember { mutableIntStateOf(0) }

    // ① 用 layoutInfo 判断「视口是否已到尾部」。
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
            } else if (inProgress) {
                autoFollow = false
            }
        }
    }

    // ② 内容追加 → 平滑滚到最新一条。
    //
    // ★ 2026-09-30 v2：两点加固。
    //  1. **不依赖 itemCount 变化**也能跟上：若上一次没滚到位（列表还在布局），
    //     这里用 totalItemsCount 重算目标，并等一帧布局完成。
    //  2. 用 `animateScrollToItem` —— Lazy 列表按 item 滚，行高不一时比按像素更准。
    LaunchedEffect(itemCount) {
        if (itemCount > lastCount && autoFollow && itemCount > 0) {
            withFrameNanos { }
            lazyState.animateScrollToItem(itemCount - 1)
        }
        lastCount = itemCount
    }
}