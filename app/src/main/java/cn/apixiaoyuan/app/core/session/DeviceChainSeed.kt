package cn.apixiaoyuan.app.core.session

import android.content.Context
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 内置设备链种子（`assets/device_chains_seed.json`）。
 *
 * ## 为什么需要内置
 *
 * 设备链 `ks_*` 的官方获取通道（`/leo-auth/android/user-devices`）整段被
 * `solar-encoder` 拦，**纯程序拿不到真设备链** —— 只有这样几条来源：
 *   1. 本机原版 App 数据提取；
 *   2. 用户手工投喂；
 *   3. 设备注册接口偶尔下发。
 *
 * 用户明确要求（2026-09-29）：**把本地已沉淀的 4 条（实为 3 条有效）设备链
 * 内置进 App**，装完即带可用设备链，无需再手工导入。
 *
 * ## 分层与安全
 *
 * 仓库 `sxd91/cn.apixiaoyuan.app` 是 **public**，设备链是**设备凭据**
 * （拿到即可伪装同一台设备）。内置等于公开 —— 这是用户的**显式决策**
 * （2026-09-29，选项 B）。因此这里只做**最小必要暴露**：
 *  - 只内置 `ks_*`（身份 cookie 不内置）；
 *  - 加载时**只补池、不动当前会话** —— 不主动改变登录态，
 *    避免「一装上就用了别人的身份」这种意外。
 *
 * ## 入库策略（幂等 + 不覆盖用户添加）
 *
 * 首次运行时把种子**合并**进 [DeviceChainPool]：
 *  - 按 `ks_deviceid` 去重（池里已有的不重复加，也不覆盖用户后来改的）；
 *  - 用一个 `seed_imported` 标记位保证只跑一次；
 *  - 用户之后通过「导入登录态」添加的链照常保留（不会被种子冲掉）。
 */
object DeviceChainSeed {

    private const val TAG = "DeviceChainSeed"
    private const val PREF_NAME = "leo_device_chains_seed"
    private const val KEY_IMPORTED = "seed_imported_v1"
    private const val ASSET_NAME = "device_chains_seed.json"

    private val json = Json { ignoreUnknownKeys = true }

    /** seed 文件里的一组设备链。 */
    @Serializable
    private data class Group(
        @SerialName("label") val label: String,
        @SerialName("cookies") val cookies: List<Cookie>,
    )

    @Serializable
    private data class Cookie(
        @SerialName("domain") val domain: String = ".yuanfudao.com",
        @SerialName("name") val name: String,
        @SerialName("value") val value: String,
        @SerialName("path") val path: String = "/",
    )

    /**
     * 首次启动时把内置设备链并入池。
     *
     * 幂等：`seed_imported_v1` 标记落盘后不再执行。调用点放在
     * [DeviceChainPool.init] 之后、任何网络请求之前（见 `App.onCreate`）。
     *
     * @param force 忽略标记强制重导（供「恢复内置设备链」入口使用）
     * @return 本次并入的条数
     */
    fun ensureImported(context: Context, force: Boolean = false): Int {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!force && prefs.getBoolean(KEY_IMPORTED, false)) return 0

        val raw = runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrElse {
            Log.w(TAG, "读取内置设备链失败：${it.message}")
            return 0
        }
        val groups = runCatching { json.decodeFromString<List<Group>>(raw) }
            .getOrElse {
                Log.w(TAG, "解析内置设备链失败：${it.message}")
                return 0
            }

        var added = 0
        groups.forEach { group ->
            val cookies = group.cookies.map {
                SessionStore.CookieEntry(
                    domain = it.domain,
                    name = it.name,
                    value = it.value,
                    path = it.path,
                )
            }
            // upsert 内部按 ks_deviceid 去重：已有的不重复加、也不覆盖。
            if (cookies.any { it.name == "ks_deviceid" && it.value.isNotBlank() }) {
                DeviceChainPool.upsert(label = group.label, cookies = cookies)
                added++
            }
        }
        prefs.edit().putBoolean(KEY_IMPORTED, true).apply()
        Log.i(TAG, "内置设备链已并入池：$added 组")
        return added
    }
}