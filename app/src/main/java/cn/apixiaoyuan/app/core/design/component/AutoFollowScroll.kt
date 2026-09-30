package cn.apixiaoyuan.app.core.design.component

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
import kotlinx.coroutines.delay

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

    // ② 内容追加 → 平滑滚到底（「丝滑动画」就在这一句）。
    LaunchedEffect(itemCount) {
        if (itemCount > lastCount && autoFollow && itemCount > 0) {
            // 等一帧让新内容参与布局，否则 maxValue 还是旧值、滚不到真正底部。
            delay(32)
            state.animateScrollTo(state.maxValue)
        }
        lastCount = itemCount
    }
}

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
    LaunchedEffect(itemCount) {
        if (itemCount > lastCount && autoFollow && itemCount > 0) {
            lazyState.animateScrollToItem(itemCount - 1)
        }
        lastCount = itemCount
    }
}