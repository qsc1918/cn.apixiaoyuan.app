package cn.apixiaoyuan.app.feature.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.apixiaoyuan.app.core.design.component.AppScrollScaffold
import cn.apixiaoyuan.app.core.log.AppLogger
import cn.apixiaoyuan.app.core.navigation.AppNavController
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text

/**
 * 日志页（Tab 根页）。
 *
 * ## 定位
 *
 * 替代原「请求台」（Repl）。结构上对齐参考项目「老挂戏老叟」的日志页
 * （运行日志 / 崩溃日志两类 + 刷新 / 复制 / 清空），但**内容是本 App 自身**的
 * 运行日志（网络请求摘要、登录、设备注册等，见 [AppLogger]），
 * UI 全部用 miuix 组件。
 *
 * 日志来源见 [AppLogger]（文件 + 内存快照），崩溃由
 * [cn.apixiaoyuan.app.core.log.CrashCatcher] 捕获。
 */
@Composable
fun LogScreen(navController: AppNavController) {
    val context = LocalContext.current

    // 0 = 运行日志，1 = 崩溃日志。
    var kind by remember { mutableIntStateOf(0) }
    var content by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }

    fun load() {
        val text = if (kind == 0) {
            // 运行日志：优先读文件（含历史），无文件时退回内存快照。
            val f = AppLogger.runFiles().firstOrNull()
            if (f != null) AppLogger.read(f) else AppLogger.snapshot().joinToString("\n")
        } else {
            val f = AppLogger.crashFiles().firstOrNull()
            if (f != null) AppLogger.read(f) else ""
        }
        content = text.ifBlank { if (kind == 0) "(暂无运行日志)" else "(暂无崩溃日志)" }
    }

    LaunchedEffect(kind) {
        hint = null
        load()
    }

    AppScrollScaffold(title = "日志", onBack = null) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 分类切换
            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { kind = 0 }, enabled = kind != 0) { Text("运行日志") }
                    Button(onClick = { kind = 1 }, enabled = kind != 1) { Text("崩溃日志") }
                }
            }

            // 工具栏
            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { load(); hint = "已刷新" }) { Text("刷新") }
                    Button(onClick = {
                        copyToClipboard(context, content)
                        hint = "已复制到剪贴板"
                    }) { Text("复制") }
                    Button(onClick = {
                        if (kind == 0) AppLogger.clearRun() else AppLogger.clearCrash()
                        load()
                        hint = "已清空"
                    }) { Text("清空") }
                }
                hint?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(top = 8.dp),
                        fontSize = 12.sp,
                    )
                }
            }

            // 日志正文
            SectionCard {
                Text(
                    text = content,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("log", text))
    }
}