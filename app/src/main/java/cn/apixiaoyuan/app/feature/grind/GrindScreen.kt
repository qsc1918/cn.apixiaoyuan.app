package cn.apixiaoyuan.app.feature.grind

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.apixiaoyuan.app.core.design.component.AppScrollScaffold
import cn.apixiaoyuan.app.core.navigation.AppNavController
import cn.apixiaoyuan.app.core.navigation.RouteExercise
import cn.apixiaoyuan.app.core.navigation.RoutePkGrind
import cn.apixiaoyuan.app.core.navigation.RouteScorePump
import cn.apixiaoyuan.app.core.oldsimian.OldSimianPrefs
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 刷分区（二级页，从主页进入）。
 *
 * ## 定位（2026-09-28 用户要求）
 *
 * 把刷分相关入口**集中到主页一个区**，点进去就是「和 pk-node 差不多的功能」。
 * 三个选项：
 *
 * | 选项 | 路由 | 说明 |
 * |---|---|---|
 * | PK 刷对局 | [RoutePkGrind] | 纯 API 刷局（出题 → 弧线笔迹 → 提交 → **结算核对**）|
 * | 练习刷对局 | [RouteExercise] | 练习闭环（知识点 → 出题 → 提交笔迹 → 经验上报）|
 * | 直接刷分 | [RouteScorePump] | 直接报经验增量（日上限 400，见 ScorePump）|
 *
 * ## 为什么用「卡 + 行」而不是并排三宫格
 *
 * 每个选项都需要一句**说明**（尤其「直接刷分」有日上限、「PK 刷对局」有频控），
 * 并排卡片放不下这些字；竖向三行既放得下说明，也和 miuix 列表风格一致。
 *
 * ## 与「功能」tab 的关系
 *
 * 功能页（`OldSimianScreen`）原本也有「分数」段与 PK 刷局入口 —— 那些是重复的。
 * 本区建立后，功能页的分数段应视为历史入口（保留兼容，但用户主路径走这里）。
 */
@Composable
fun GrindScreen(navController: AppNavController) {
    AppScrollScaffold(title = "刷分区", onBack = { navController.popBackStack() }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

            Text(
                text = "刷分",
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = "三条链路，按需选择。参数在各二级页里设置，设置会持久化。",
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )

            // ---- 三个入口 ----
            SectionCard(title = "选择链路") {
                EntryRow(
                    title = "PK 刷对局",
                    summary = "出题 → 弧线笔迹 → 提交 → 结算核对（提交 200 不代表已结算）",
                    onClick = { navController.navigate(RoutePkGrind) },
                )
                EntryRow(
                    title = "练习刷对局",
                    summary = "知识点 → 出题 → 提交（带笔迹）→ 经验上报；每题自带答案",
                    onClick = { navController.navigate(RouteExercise) },
                )
                EntryRow(
                    title = "直接刷分",
                    summary = "直接上报经验增量，不需要出题做题。注意：同一 ruleType 每天只记一次，" +
                        "可记账类型仅 0/1 ⇒ 日上限 400",
                    onClick = { navController.navigate(RouteScorePump) },
                )
            }

            // ---- 开关：直接从功能页迁来，保持同一份持久化 ----
            SectionCard(title = "开关") {
                SwitchRow(
                    title = "自定义分数（刷分）",
                    summary = "开启后「直接刷分」可用（旧入口在功能页，这里同一份配置）",
                    checked = OldSimianPrefs.customScoreEnabled,
                    onCheckedChange = {
                        OldSimianPrefs.customScoreEnabled = it
                        OldSimianPrefs.persist()
                    },
                )
            }

            // ---- 纪律提示（把实测约束写在用户看得见的地方）----
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "实测约束（来自 pk-node）",
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                    )
                    Text(
                        text = "· PK 出题有账号级冷却（约 60s+），配更小不会更快，会 429/400。",
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                    Text(
                        text = "· PK 提交与出题的频控是分开的，命中后需大退避（分钟级）。",
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                    Text(
                        text = "· 练习提交必须带笔迹（script + curTrueAnswer），否则服务端判 0 分。",
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                }
            }
        }
    }
}

/** 分组卡（与功能页同风格）。 */
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
            )
            content()
        }
    }
}

/** 可点行：标题 + 说明 + 右箭头。 */
@Composable
private fun EntryRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, color = MiuixTheme.colorScheme.onSurfaceContainer)
            Text(text = summary, color = MiuixTheme.colorScheme.onSurfaceContainerVariant)
        }
        Text(text = "›", color = MiuixTheme.colorScheme.primary)
    }
}

/** 开关行。 */
@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, color = MiuixTheme.colorScheme.onSurfaceContainer)
            Text(text = summary, color = MiuixTheme.colorScheme.onSurfaceContainerVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}