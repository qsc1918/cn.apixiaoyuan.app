package cn.apixiaoyuan.app.feature.grind

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.apixiaoyuan.app.core.design.component.AppScrollScaffold
import cn.apixiaoyuan.app.core.navigation.AppNavController
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「账号分数 / 任务」二级页（★ 2026-09-30 新增）。
 *
 * ## 需求
 *
 * 用户：「把 pk-node 刷练习界面的刷新方式/任务移植到刷分区的『查看账号分数/任务』，
 * 现在还没有那个页面需要创建」。
 *
 * 对应 pk-node「刷练习」tab 顶部的 **[刷新分数/任务]** 按钮 + `prac-status` 文本区，
 * 但那边是一个 `<pre>` 纯文本块；这里按 miuix 卡片化重排：
 *
 * | 区块 | 内容 |
 * |---|---|
 * | 分数 | 本周经验（★ 刷分读数）/ 今日积分 / 周榜分数（区分标注）|
 * | 打卡 | 连续天数 / 当前排名 / 下档倍率 |
 * | 今日任务 | 列表（标题 + 已完成/进行中 + 经验）|
 *
 * ## 交互
 *
 * 只做一件事：**刷新**（拉取 + 展示）。真正的「刷」在相邻的
 * 「练习刷对局 / 直接刷分」页里 —— 本页是**只读的观测台**，
 * 用途是「刷之前看看基线、刷之后再刷新看涨了多少」。
 */
@Composable
fun AccountStatsScreen(
    navController: AppNavController,
    viewModel: AccountStatsViewModel = viewModel(),
) {
    // ★ 2026-09-30：**进入页面自动拉一次**。
    //   此前只有按钮里调 refresh()，用户进来看到的是全「—」，误以为「分数没加载/显示 0」。
    //   ★ 用户手动点按钮仍可再刷（refresh() 内部会取消上一个 Job，连点安全）。
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refresh() }

    AppScrollScaffold(title = "账号分数 / 任务", onBack = { navController.popBackStack() }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

            Text(
                text = "点下面「刷新」拉取当前读数。这里是只读观测台，用来对比刷分前后的变化。",
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )

            // ---- 刷新按钮 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { viewModel.refresh() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (viewModel.loading) "拉取中…" else "刷新分数 / 任务")
                }
                if (viewModel.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }

            viewModel.error?.let { err ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.errorContainer,
                        contentColor = MiuixTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(text = err, modifier = Modifier.padding(16.dp))
                }
            }

            // ---- 分数区 ----
            StatCard(title = "分数") {
                StatRow("本周经验（curWeekExp）★", viewModel.curWeekExp, "刷分上报加的就是它")
                StatRow("今日获得积分", viewModel.todayPoints, "直接刷分的产物")
                StatRow("周排行榜分数（curWeekScore）", viewModel.curWeekScore, "排行榜口径，与上面不是一回事")
            }

            // ---- 打卡 / 排名区 ----
            StatCard(title = "打卡与排名") {
                StatRow("连续打卡天数", viewModel.continuousDays, null)
                StatRow("当前排名", viewModel.curRank, null)
                StatRow("下档倍率", viewModel.nextMultiplier, "2 = 双倍奖励")
            }

            // ---- 今日任务 ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "今日任务",
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                    )
                    if (viewModel.tasks.isEmpty()) {
                        Text(
                            text = if (viewModel.lastLoadedAt == null) "（还没拉取）" else "（今天没有任务）",
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                    } else {
                        viewModel.tasks.forEach { t ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = t.title,
                                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                                    )
                                    Text(
                                        text = if (t.finished) "已完成" else "进行中",
                                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                    )
                                }
                                Text(
                                    text = "+" + t.exp,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            // ---- 读数说明（把「两个分数不是一个东西」写在用户看得见的地方）----
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
                        text = "读数说明",
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                    )
                    Text(
                        text = "· 「本周经验」是练习/刷分累计的经验，刷分的读数看它。",
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                    Text(
                        text = "· 「周排行榜分数」是排行榜口径，刷分不会改变它 —— 两者别混。",
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                    Text(
                        text = "· 「直接刷分」每个 ruleType 每天只记一次，可记账类型仅 0/1 ⇒ 日上限 400。",
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                }
            }
        }
    }
}

/** 分组卡（与刷分区同风格）。 */
@Composable
private fun StatCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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

/** 指标行：名称 + 值（+ 可选备注）。值未拉到显示 `—`。 */
@Composable
private fun StatRow(label: String, value: Int?, note: String?) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = MiuixTheme.colorScheme.onSurfaceContainer,
            )
            Text(
                text = value?.toString() ?: "—",
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.primary,
            )
        }
        note?.let {
            Text(
                text = it,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )
        }
    }
}