package cn.apixiaoyuan.app.feature.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.apixiaoyuan.app.core.design.component.AppScrollScaffold
import cn.apixiaoyuan.app.core.exercise.ExercisePumpEngine
import cn.apixiaoyuan.app.core.exercise.ExerciseRepository
import cn.apixiaoyuan.app.core.model.ExerciseScopeKeypoint
import cn.apixiaoyuan.app.core.navigation.AppNavController
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「练习刷对局」页 —— 与网页端（pk-node）同一套做法。
 *
 * ## 流程（对齐 pk-node `practiceLoop` / `runPractice`）
 *
 * ```
 * 1. 拉知识点（GET /leo-math/android/exams/exercises/type/{type}）→ 选一个
 * 2. 循环 N 轮：
 *      出题  POST /leo-math/android/exams（含 429 重试）
 *      作答  每题抄服务端答案 + 生成笔迹
 *      提交  PUT  /leo-math/android/exams/{examId}（JSON 明文 + 笔迹）
 *      经验 = 服务端判对题数 × 2
 *   配速：实际等待 = max(出题冷却剩余, 随机[最小, 最大])
 * ```
 *
 * ## ★ 两个关键点（都在界面上写明，避免用户误判为 bug）
 *
 * 1. **出题冷却 ≈62s 是账号级硬下限** —— 配得比它小**不会更快**（会 429）。
 * 2. **建议每局 100 题** —— 冷却按「次」算不按题数，100 题 = 200 经验，
 *    比 10 题（20 经验）划算 10 倍。
 */
@Composable
fun ExercisePumpScreen(navController: AppNavController) {
    val vm: ExercisePumpViewModel = viewModel()
    AppScrollScaffold(title = "练习刷对局", onBack = { navController.popBackStack() }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

            Text(
                text = "练习刷对局",
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = "循环「出题 → 抄答案+笔迹 → 提交」，与网页端一致。",
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )

            // ---- 知识点 ----
            SectionCard(title = "知识点") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { vm.loadKeypoints() },
                        enabled = !vm.running && !vm.loadingKeypoints,
                    ) {
                        Text(if (vm.loadingKeypoints) "拉取中…" else "获取知识点")
                    }
                    Text(
                        text = vm.keypointStatus,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                }
                if (vm.keypoints.isNotEmpty()) {
                    // 知识点列表：点一个即选中（高亮当前选中项）。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        vm.keypoints.forEach { kp ->
                            KeypointPickRow(
                                kp = kp,
                                selected = kp.id == vm.selectedKeypointId,
                                onClick = { vm.selectKeypoint(kp.id) },
                            )
                        }
                    }
                }
            }

            // ---- 参数 ----
            SectionCard(title = "参数") {
                NumberField(
                    label = "轮数",
                    value = vm.rounds.toString(),
                    enabled = !vm.running,
                    onCommit = { vm.rounds = it.coerceAtLeast(1) },
                )
                NumberField(
                    label = "每局题数（建议 100 —— 冷却按次算，题多更划算）",
                    value = vm.limit.toString(),
                    enabled = !vm.running,
                    onCommit = { vm.limit = it.coerceIn(1, 1000) },
                )
                NumberField(
                    label = "每轮间隔下限 ms（出题冷却 ≈62000 是硬下限，配更小不会更快）",
                    value = vm.gapMinMs.toString(),
                    enabled = !vm.running,
                    onCommit = { vm.gapMinMs = it.coerceAtLeast(0) },
                )
                NumberField(
                    label = "每轮间隔上限 ms",
                    value = vm.gapMaxMs.toString(),
                    enabled = !vm.running,
                    onCommit = { vm.gapMaxMs = it.coerceAtLeast(0) },
                )
            }

            // ---- 运行 ----
            SectionCard(title = "运行") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { vm.start() },
                        enabled = !vm.running && vm.selectedKeypointId > 0,
                    ) {
                        Text("开始")
                    }
                    Button(
                        onClick = { vm.stop() },
                        enabled = vm.running,
                    ) {
                        Text("停止")
                    }
                    Text(
                        text = vm.status,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                }
            }

            // ---- 日志 ----
            SectionCard(title = "日志") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 360.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (vm.logs.isEmpty()) {
                            Text(
                                text = "（暂无）",
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                fontSize = 11.sp,
                            )
                        } else {
                            vm.logs.forEach { line ->
                                Text(
                                    text = line,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 刷对局页的状态机。 */
class ExercisePumpViewModel : ViewModel() {

    var keypoints by mutableStateOf<List<ExerciseScopeKeypoint>>(emptyList())
        private set
    var selectedKeypointId by mutableStateOf(0)
        private set
    var loadingKeypoints by mutableStateOf(false)
        private set
    var keypointStatus by mutableStateOf("未拉取")

    var rounds by mutableStateOf(1)
    var limit by mutableStateOf(ExercisePumpEngine.DEFAULT_LIMIT)
    var gapMinMs by mutableStateOf(0)
    var gapMaxMs by mutableStateOf(0)

    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("待开始")
        private set

    val logs = mutableStateListOf<String>()

    private var job: Job? = null

    /**
     * 拉知识点。
     *
     * 用 `ExerciseType.ORAL`（口算）+ 当前年级 —— 与练习页同一入口
     * （`GET /leo-math/android/exams/exercises/type/{type}`）。
     */
    fun loadKeypoints() {
        if (loadingKeypoints || running) return
        loadingKeypoints = true
        keypointStatus = "拉取中…"
        viewModelScope.launch {
            val type = cn.apixiaoyuan.app.core.model.ExerciseType.ORAL
            val grade = cn.apixiaoyuan.app.core.session.SessionStore.grade() ?: 1
            val scope = ExerciseRepository.fetchMathScope(
                type = type,
                grade = grade,
                semester = 1,
                book = 1,
            )
            loadingKeypoints = false
            if (scope == null) {
                keypointStatus = "拉取失败（看日志页）"
                append("知识点拉取失败 grade=$grade")
                return@launch
            }
            keypoints = scope.allKeypoints
            keypointStatus = "共 ${keypoints.size} 个知识点"
            append("知识点拉取成功：${keypoints.size} 个（grade=$grade）")
            if (selectedKeypointId == 0 && keypoints.isNotEmpty()) {
                // 默认选第一个 —— 原版也是「点一个才开始」，这里省一次点击。
                selectedKeypointId = keypoints.first().id
            }
        }
    }

    fun selectKeypoint(id: Int) {
        selectedKeypointId = id
        val name = keypoints.firstOrNull { it.id == id }?.name ?: "知识点 $id"
        status = "已选：$name"
    }

    /** 开始刷。 */
    fun start() {
        if (running || selectedKeypointId <= 0) return
        running = true
        status = "运行中…"
        logs.clear()
        val kp = selectedKeypointId
        val n = rounds
        val l = limit
        val gMin = gapMinMs.toLong()
        val gMax = gapMaxMs.toLong()
        append("开始：keypointId=$kp 轮数=$n 每局=$l 题 间隔=[$gMin,$gMax]ms")
        job = viewModelScope.launch {
            val summary = runCatching {
                ExercisePumpEngine.practiceLoop(
                    keypointId = kp,
                    rounds = n,
                    limit = l,
                    gapMinMs = gMin,
                    gapMaxMs = gMax,
                    onProgress = { done, total, ev ->
                        append(ev)
                        status = "$done/$total 轮"
                    },
                )
            }.getOrElse { t ->
                append("异常：${t.message ?: t}")
                ExercisePumpEngine.Summary(n, 0, n, 0)
            }
            running = false
            status = "完成：成功 ${summary.done}/${summary.rounds}，累计 +${summary.totalExp} 经验"
            append("===== ${status} =====")
        }
    }

    /** 停止（取消协程；当前轮若是网络请求中途会被取消，不影响已提交的轮次）。 */
    fun stop() {
        job?.cancel()
        job = null
        running = false
        status = "已停止"
        append("已手动停止")
    }

    /** 只保留最近 300 行，避免长跑爆内存。 */
    private fun append(line: String) {
        logs.add(line)
        if (logs.size > 300) logs.removeAt(0)
    }
}

/** 知识点选择行（选中态高亮）。 */
@Composable
private fun KeypointPickRow(
    kp: ExerciseScopeKeypoint,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 交互式卡片：官方文档 Card 属性表里 `onClick` 存在（onClick/onLongPress 属交互式）。
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.defaultColors(
            color = if (selected) {
                MiuixTheme.colorScheme.primaryContainer
            } else {
                MiuixTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = (kp.name ?: "知识点 ${kp.id}") + if (selected) "  ✓" else "",
                color = if (selected) {
                    MiuixTheme.colorScheme.onPrimaryContainer
                } else {
                    MiuixTheme.colorScheme.onSurfaceContainer
                },
            )
            Text(
                text = "id=${kp.id} · 已练 ${kp.practiceCnt} 次",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )
        }
    }
}

/** 分组卡片：标题 + 若干行（与功能页同写法）。 */
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.surfaceContainer,
                contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                content()
            }
        }
    }
}

/** 数字输入行（提交即回写）。 */
@Composable
private fun NumberField(
    label: String,
    value: String,
    enabled: Boolean,
    onCommit: (Int) -> Unit,
) {
    var text by androidx.compose.runtime.remember(value) {
        androidx.compose.runtime.mutableStateOf(value)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
        )
        TextField(
            value = text,
            onValueChange = { v ->
                text = v.filter { it.isDigit() }
                text.toIntOrNull()?.let(onCommit)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            // enabled 官方文档确认存在（TextField 属性表）。
            enabled = enabled,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            ),
        )
    }
}