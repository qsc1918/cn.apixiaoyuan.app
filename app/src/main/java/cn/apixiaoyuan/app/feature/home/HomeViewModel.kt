package cn.apixiaoyuan.app.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.apixiaoyuan.app.core.account.AccountRepository
import cn.apixiaoyuan.app.core.account.SubAccountItem
import cn.apixiaoyuan.app.core.session.SessionStore
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 主页状态机：子账号卡片列表的加载与切换。
 *
 * ## 为什么主页需要一个 ViewModel
 *
 * 用户新需求（2026-09-26）：子账号卡片直接显示在主页「已登录卡片」下方，
 * 点哪个切哪个。这需要：
 *  1. 登录后 / 进入主页时拉一次子账号列表（`batchGet`，无参）；
 *  2. 切换账号（调 switch + 写回 userid cookie）后刷新列表。
 *
 * 这些是异步网络操作，不能塞进 Composable。与账号页 [AccountViewModel] 的关系：
 * 两个页面各自维护一份列表状态，但**切换逻辑共用** [AccountRepository.switchTo]，
 * 不会行为漂移。
 *
 * ## 刷新时机（关键）
 *
 * 监听 [SessionStore.stateRevision]（会话版本戳）—— 登录 / 登出 / 切号 /
 * 导入 cookie 都会自增。主页在 `init` 里用 `viewModelScope` 观察它，
 * 每次变化都重拉列表。这样「在账号页切换后返回主页」卡片能立即更新，
 * 不需要手动通知。
 */
class HomeViewModel : ViewModel() {

    /** 子账号列表。空 = 未登录或没有子账号。 */
    var subAccounts by mutableStateOf<List<SubAccountItem>>(emptyList())
        private set

    /** 是否正在拉取子账号列表。 */
    var loadingAccounts by mutableStateOf(false)
        private set

    /** 切换/拉取的提示。null = 无。 */
    var message by mutableStateOf<String?>(null)
        private set

    /** 拉取子账号失败的原因（含可行动引导）。null = 无错误。 */
    var accountsError by mutableStateOf<String?>(null)
        private set

    /** 是否正在切换账号。 */
    private var switching by mutableStateOf(false)

    /**
     * 正在切换的**目标账号 userId**（null = 没在切换）。
     *
     * ★ 2026-09-30：用户反馈「切换账号没有动画并且还有延迟」。
     * UI 需要知道「点的是哪一张卡」才能在该卡上转圈 —— 否则点击后
     * 界面上**看不出任何变化**（没有任何进行中状态），直到列表刷新完成，
     * 感知上就是「卡住/没反应」。
     */
    var switchingUserId by mutableStateOf<Int?>(null)
        private set

    init {
        refreshAccounts()
        // 会话版本戳变化（登录 / 登出 / 切号 / 导入 cookie）→ 重拉列表。
        //
        // ## 为什么用 snapshotFlow 而不是 while(true) + delay 忙轮询
        //
        // 此前是 `while (true) { if (revision != last) refresh(); delay(200) }` ——
        // 每 200ms 空转一次，且一次拉取失败（如 401）后状态反复翻转，
        // UI 上表现为「一直在闪」。snapshotFlow 只在状态**真正变化**时发射，
        // 无变化时协程挂起（不占 CPU），也天然避免了失败→重拉的抖动。
        viewModelScope.launch {
            snapshotFlow { SessionStore.stateRevision }
                .drop(1) // 首次值由上面的 refreshAccounts() 消费，跳过
                // 去抖 250ms：即便上游因竞态连续变化多次，也只重拉一次。
                // 这是对上面那些「写入幂等」修复的补充保险 —— 单靠幂等已能断环，
                // 但并发窗口下仍可能瞬间变化两次，去抖避免多余的网络往返。
                .debounce(250L)
                .collect { refreshAccounts() }
        }
    }

    /** 拉取子账号列表（未登录时直接清空，不发请求）。 */
    fun refreshAccounts() {
        if (loadingAccounts) return
        if (!SessionStore.isLoggedIn) {
            subAccounts = emptyList()
            accountsError = null
            return
        }
        loadingAccounts = true
        accountsError = null
        viewModelScope.launch {
            AccountRepository.fetchSubAccounts()
                .onSuccess { subAccounts = it }
                .onFailure { t ->
                    subAccounts = emptyList()
                    // 401 = 主域认证失败（缺设备链 sid/ks_*），不是「没有小号」。
                    // 给可行动的引导，而不是让用户对着空列表猜。
                    val msg = t.message.orEmpty()
                    accountsError = if (msg.contains("401")) {
                        "拉取失败 401：主域需要设备链登录态（sid + ks_*）。请到「账号」页导入登录态后重试。"
                    } else {
                        "拉取失败：${t.message ?: t}"
                    }
                }
            loadingAccounts = false
        }
    }

    /** 切换到指定子账号。 */
    fun switchTo(item: SubAccountItem) {
        if (switching || item.isCurrent) return
        switching = true
        switchingUserId = item.userId          // ★ 让对应卡片立刻转圈（即时反馈）
        message = null
        viewModelScope.launch {
            val result = runCatching { AccountRepository.switchTo(item) }
            switching = false
            switchingUserId = null
            result.onSuccess { newId ->
                message = "已切换到「${item.nickname}」"
                // ★ 2026-09-30：切换成功后**立刻重拉一次**，不等 stateRevision 的 250ms 去抖。
                //   用户反馈「切换有延迟」—— 那 250ms 去抖 + 一轮网络往返就是延迟来源。
                //   这里主动刷新，感知上「点完马上更新」。
                refreshAccounts()
            }.onFailure {
                message = "切换失败：${it.message ?: it}"
            }
        }
    }

    fun clearMessage() {
        message = null
    }
}