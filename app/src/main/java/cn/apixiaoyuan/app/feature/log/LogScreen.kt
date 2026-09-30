package cn.apixiaoyuan.app.feature.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.apixiaoyuan.app.core.log.AppLogger
import cn.apixiaoyuan.app.core.navigation.AppNavController
import cn.apixiaoyuan.app.core.design.component.AppScaffold
import cn.apixiaoyuan.app.core.design.component.LocalScrollBottomLimit
import cn.apixiaoyuan.app.core.design.component.LocalTopBarInset
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 日志页（Tab 根页）—— **2026-09-27 重做**（待办 4：更详细的日志系统 + 更好用的日志 UI）。
 *
 * ## 旧版的问题
 *
 * 旧版把**整个**运行日志当成一个巨型字符串塞进一个 `Text` 里（`AppScrollScaffold`
 * + `Text(content)`）：
 *  - 几万行一次性测量/绘制，滑动卡顿；
 *  - 只能整段复制，没法按级别/tag/关键字找东西 —— 而排查 417/400 恰恰需要这个；
 *  - 「刷新 / 复制 / 清空」三个按钮摆在最上面，内容一长就要来回滚。
 *
 * ## 新版结构
 *
 * ```
 * 顶栏（AppScaffold）
 * ├ 固定工具条（sticky 在顶栏下方，不随列表滚动）
 * │   ├ 类别：运行 / 崩溃
 * │   ├ 级别：All / E / W / I / D（多选）
 * │   ├ 搜索框：关键字 + tag
 * │   └ 操作：刷新 / 复制(仅当前过滤结果) / 清空 / 跳到最新
 * └ LazyColumn：一条日志一个 item（级别着色 + tag 徽标 + 可长按复制单条）
 * ```
 *
 * 关键取舍：
 *  - **只渲染过滤后的结果**（`LazyColumn` 天然按需测量），默认取最近
 *    [AppLogger.DEFAULT_QUERY_LIMIT] 条，避免一次性吃几万行；
 *  - 过滤在 [AppLogger.query] 里做（纯 Kotlin，不依赖 Compose），
 *    日志页只负责把条件传下去；
 *  - 自动滚到**最新一条**（日志是追加写的，打开时人要看尾部）。
 */
@Composable
fun LogScreen(navController: AppNavController) {
    val context = LocalContext.current

    // 0 = 运行日志，1 = 崩溃日志。
    var kind by remember { mutableIntStateOf(0) }
    // 级别过滤：空集 = 全部。
    var levels by remember { mutableStateOf(emptySet<String>()) }
    var keyword by remember { mutableStateOf("") }
    var tagQuery by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    // 过滤结果。refreshToken 变化触发重新查询。
    var refreshToken by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<AppLogger.Entry>>(emptyList()) }
    var rawCrash by remember { mutableStateOf("") }
    LaunchedEffect(kind, levels, keyword, tagQuery, refreshToken) {
        if (kind == 0) {
            entries = AppLogger.query(levels = levels, tagQuery = tagQuery, keyword = keyword)
            rawCrash = ""
        } else {
            // 崩溃日志量小（每次崩溃一份），不做结构化过滤，整份展示。
            entries = emptyList()
            val f = AppLogger.crashFiles().firstOrNull()
            rawCrash = f?.let { AppLogger.read(it) }.orEmpty().ifBlank { "(暂无崩溃日志)" }
        }
    }

    /*
     * ★ 2026-09-30：运行日志**自动跟随刷新**（原来是纯手动/仅进页时查一次）。
     *
     * 用户要的效果是「发一条新日志 → 列表多一条 → 有一个向下滚动的动画」，
     * 这要求**页面在开着的时候自己发现新日志**。做法：轻量轮询（600ms）。
     *
     * 为什么用轮询而不是 Flow：
     *  - [AppLogger] 是文件追加 + 内存环形缓冲，没有可观察的数据源；
     *  - 给它加 Flow 会牵动日志核心（高频写路径），风险大于收益；
     *  - 日志页是「偶尔开着看看」的页面，600ms 轮询的成本可以忽略。
     *
     * 只在 **kind == 0（运行日志）** 且 **过滤条件未变** 时轮询；
     * 查询只在「条数/最后一条时间戳发生变化」时才写状态，避免无谓重组
     * （否则每 600ms 都会触发一次 LazyColumn 重组，滚动动画会被打断）。
     */
    LaunchedEffect(kind, levels, keyword, tagQuery) {
        if (kind != 0) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(600)
            val fresh = AppLogger.query(levels = levels, tagQuery = tagQuery, keyword = keyword)
            // 仅当「条数变了」或「最后一条变了」才更新 —— 否则跳过，不打扰滚动。
            // （Entry 没有数值时间戳，用 raw 原文比对最稳：它含到毫秒的时间前缀。）
            if (fresh.size != entries.size ||
                (fresh.isNotEmpty() && entries.isNotEmpty() && fresh.last().raw != entries.last().raw)
            ) {
                entries = fresh
            }
        }
    }

    val listState = rememberLazyListState()

    /*
     * ★ 2026-09-30：日志滚动改「丝滑跟随」——不再是瞬间跳到底。
     *
     * 旧行为：`scrollToItem(lastIndex)` 是**瞬移**，新日志一来画面直接跳到最底，
     * 观感生硬（用户形容「直接显示最新」）。
     *
     * 新行为：用 `animateScrollToItem` 做**位移动画** —— 发一条新日志就向下
     * 平滑滚一段。三条准则：
     *
     *  1. **首次进入/切换过滤条件**（条目从 0 → N）用瞬移：此时做动画没有意义，
     *     而且会让用户先看到顶部再滚一大段（很怪）。
     *  2. **增量追加**（N → N+k，比如来了 1 条新日志）用平滑动画：这正是用户要的
     *     「发一条新日志就有向下滚动的动画」。
     *  3. **用户手动滚动时不要抢**：若用户正在往回翻看历史，新日志不该把他拽回去。
     *     判据：只有当列表已接近底部（最后一条可见）时才自动跟随。
     */
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    var lastCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(entries.size, rawCrash.length) {
        val target = if (entries.isNotEmpty()) entries.lastIndex else 0
        val count = if (entries.isNotEmpty()) entries.size else (if (rawCrash.isNotEmpty()) 1 else 0)
        when {
            // ① 清空 / 切换过滤条件（或首次进入）→ 瞬移，不做动画。
            lastCount == 0 || count <= lastCount -> {
                if (count > 0) listState.scrollToItem(target)
            }
            // ② 增量追加，且用户本就停在底部 → 平滑滚到最新（丝滑跟随）。
            atBottom -> listState.animateScrollToItem(target)
            // ③ 用户正在看历史（不在底部）→ 不打扰，只记录条数。
            else -> Unit
        }
        lastCount = count
    }

    AppScaffold(title = "日志", onBack = null) { pad ->
        val topInset = LocalTopBarInset.current
        val scrollLimit = LocalScrollBottomLimit.current
        Column(modifier = Modifier.fillMaxWidth()) {
            // ★ 顶栏占位（2026-09-28 修）：工具条原本紧贴 Column 顶部，被
            //   **透明顶栏**完全盖住。顶栏高度必须作为本 Column 的**首个子项**
            //   占位 —— 只把它加到下面的 LazyColumn 里是没用的，工具条仍在
            //   顶栏之下（这正是本页此前的问题）。
            Spacer(Modifier.height(topInset))
            // ==================== 工具条（不参与滚动）====================
            LogToolbar(
                kind = kind,
                onKindChange = { kind = it },
                levels = levels,
                onLevelsChange = { levels = it },
                keyword = keyword,
                onKeywordChange = { keyword = it },
                tagQuery = tagQuery,
                onTagQueryChange = { tagQuery = it },
                onRefresh = { refreshToken++ ; hint = "已刷新" },
                onCopy = {
                    val text = if (kind == 0) {
                        entries.joinToString("\n") { it.raw }
                    } else {
                        rawCrash
                    }
                    copyToClipboard(context, text)
                    hint = if (kind == 0) {
                        "已复制 ${entries.size} 条（当前过滤结果）"
                    } else {
                        "已复制崩溃日志"
                    }
                },
                onClear = {
                    if (kind == 0) AppLogger.clearRun() else AppLogger.clearCrash()
                    refreshToken++
                    hint = "已清空"
                },
                count = if (kind == 0) entries.size else null,
                hint = hint,
                onDismissHint = { hint = null },
            )

            // ==================== 正文 ====================
            if (kind == 0) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(
                        // 顶栏占位已由外层 Column 的首个 Spacer 完成，此处为 0。
                        top = 0.dp,
                        bottom = pad.calculateBottomPadding() + scrollLimit + 24.dp,
                        start = 12.dp,
                        end = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(entries) { entry -> LogRow(entry, context) }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(
                        // 顶栏占位已由外层 Column 的首个 Spacer 完成，此处为 0。
                        top = 0.dp,
                        bottom = pad.calculateBottomPadding() + scrollLimit + 24.dp,
                        start = 12.dp,
                        end = 12.dp,
                    ),
                ) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.defaultColors(),
                        ) {
                            MiuixText(
                                text = rawCrash,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 固定工具条：类别 / 级别 / 搜索 / 操作。 */
@Composable
private fun LogToolbar(
    kind: Int,
    onKindChange: (Int) -> Unit,
    levels: Set<String>,
    onLevelsChange: (Set<String>) -> Unit,
    keyword: String,
    onKeywordChange: (String) -> Unit,
    tagQuery: String,
    onTagQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    count: Int?,
    hint: String?,
    onDismissHint: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.defaultColors(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 类别 + 操作
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip("运行日志", kind == 0) { onKindChange(0) }
                Chip("崩溃日志", kind == 1) { onKindChange(1) }
                Chip("刷新", false, onDismissHint, onRefresh)
                Chip("复制", false, onDismissHint, onCopy)
                Chip("清空", false, onDismissHint, onClear)
            }

            // 级别（运行日志才有意义）
            if (kind == 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Chip("全部", levels.isEmpty()) { onLevelsChange(emptySet()) }
                    listOf("E", "W", "I", "D").forEach { lv ->
                        Chip(lv, lv in levels) {
                            onLevelsChange(
                                if (lv in levels) levels - lv else levels + lv
                            )
                        }
                    }
                }

                // 关键字 / tag
                TextField(
                    value = keyword,
                    onValueChange = onKeywordChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = "搜索关键字（URL / 状态码 / 文本）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                TextField(
                    value = tagQuery,
                    onValueChange = onTagQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = "按 tag 过滤（如 LeoNet / PkH5Proxy）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )

                count?.let {
                    MiuixText(
                        text = "命中 $it 条（最多显示最近 ${AppLogger.DEFAULT_QUERY_LIMIT} 条）",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                }
            }

            hint?.let {
                MiuixText(
                    text = it,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 单条日志：级别色条 + tag 徽标 + 正文；点一下复制该条。 */
@Composable
private fun LogRow(entry: AppLogger.Entry, context: Context) {
    var copied by remember { mutableStateOf(false) }
    val levelColor = when (entry.level) {
        "E" -> MaterialTheme.colorScheme.error
        "W" -> MaterialTheme.colorScheme.tertiary
        "I" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (copied) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .clickable {
                copyToClipboard(context, entry.raw)
                copied = true
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MiuixText(
            text = entry.level ?: "·",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = levelColor,
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (entry.time.isNotBlank()) {
                    MiuixText(
                        text = entry.time.substringAfter(' '),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                }
                entry.tag?.let {
                    MiuixText(
                        text = it,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = levelColor,
                    )
                }
            }
            MiuixText(
                text = entry.message,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
            )
        }
    }
}

/** 小圆角筛选 chip。 */
@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    onBefore: () -> Unit = {},
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = label,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable { onBefore(); onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("log", text))
    }
}
