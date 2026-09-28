package cn.apixiaoyuan.app.core.session

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 设备链池：多来源 `ks_*` 的存放与轮换（对齐 pk-node 的 `device_chains` 表）。
 *
 * ## 为什么需要「池」而不是「一条链」
 *
 * 用户明确要求（2026-09-28）：**采用多来源设备链池（多点轮换）更安全**。
 * 单个设备链用久了有被风控标记的风险；池里放多份不同来源的链、按请求轮换，
 * 能把「同一个链被高频使用」的痕迹摊薄。
 *
 * 另有现实约束：设备链的**官方获取通道** `/leo-auth/android/user-devices`
 * 整段被 `solar-encoder` 拦（pk-node 实测，连不存在的子路径都 417），
 * 所以**纯程序拿不到真设备链** —— 只能靠
 *   ① 本机原版 App 数据里提取（`mmkv/cookie_store`）；
 *   ② 用户手工投喂；
 *   ③ 登录时自动补链并**入池**。
 * 池化正是把「① ② ③ 得到的东西」沉淀下来、轮换使用的机制。
 *
 * ## 与 pk-node 的对应关系
 *
 * | pk-node | 本项目 |
 * |---|---|
 * | `device_chains` 表 | 本对象 + SharedPreferences（JSON 列表） |
 * | `ks_deviceid` 明文存 `device_id` 列（用于显示/去重，**非凭据**） | [Item.deviceId] 同样明文 |
 * | `cookies_json`（value 加密） | [DeviceChainCipher] 加密后存 |
 * | `listDeviceChains(true)` 只取 enabled | [list] 只返回 enabled 的 |
 * | `applyDeviceChain` 池内轮换 | [pick] / [applyTo] |
 *
 * ## 存储形态
 *
 * SharedPreferences 里一串 JSON 数组，每项：
 * ```
 * {"id":1,"label":"本机","cookies":"<加密后的 CookieEntry JSON>","deviceId":"350266477","enabled":true}
 * ```
 * `cookies` 字段本身是**整份加密后的字符串**（用 [DeviceChainCipher]），
 * 所以即便 `device_id` 明文可见，真正的链值也读不出来 —— 与 pk-node 的分层一致
 * （`ks_deviceid` 明文可读，其余 `ks_*` 加密）。
 */
object DeviceChainPool {

    private const val TAG = "DeviceChainPool"
    private const val PREF_NAME = "leo_device_chains"
    private const val KEY_ITEMS = "chains_json"

    /** 设备链的标识名（用于去重），与 [SessionStore] 的加密前缀同一来源。 */
    private const val DEVICE_ID_COOKIE = "ks_deviceid"

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var appContext: Context? = null

    /**
     * 池里的一份设备链。
     *
     * @param id        自增序号（删除/选择时定位用）
     * @param label     人类可读的名字，如「本机」「小号A」
     * @param cookies   加密后的 `CookieEntry` 列表 JSON（**不是**明文 ——
     *                  用 [DeviceChainCipher.decrypt] 才能还原）
     * @param deviceId  `ks_deviceid` 的明文值，仅用于**显示与去重**
     *                  （对齐 pk-node：它是设备级标识，不是凭据）
     * @param enabled   是否参与轮换
     */
    @kotlinx.serialization.Serializable
    data class Item(
        @kotlinx.serialization.SerialName("id") val id: Long,
        @kotlinx.serialization.SerialName("label") val label: String,
        @kotlinx.serialization.SerialName("cookies") val cookies: String,
        @kotlinx.serialization.SerialName("deviceId") val deviceId: String? = null,
        @kotlinx.serialization.SerialName("enabled") val enabled: Boolean = true,
    ) {
        /** 取回明文 cookie 列表（解密）。 */
        fun decodeCookies(): List<SessionStore.CookieEntry> {
            val plain = DeviceChainCipher.decrypt(cookies) ?: return emptyList()
            return runCatching {
                json.decodeFromString<List<SessionStore.CookieEntry>>(plain)
            }.getOrDefault(emptyList())
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs(): SharedPreferences {
        val ctx = appContext ?: error("DeviceChainPool.init() 未调用")
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // ---- 读写 ----

    /** 全部设备链（**含** disabled 的），供管理界面显示。 */
    fun listAll(): List<Item> {
        val raw = prefs().getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Item>>(raw) }.getOrDefault(emptyList())
    }

    /** 参与轮换的设备链（只含 enabled）。 */
    fun list(): List<Item> = listAll().filter { it.enabled }

    /** 池是否为空。 */
    val isEmpty: Boolean get() = list().isEmpty()

    private fun save(items: List<Item>) {
        prefs().edit().putString(KEY_ITEMS, json.encodeToString(items)).apply()
    }

    /**
     * 加入（或按 `deviceId` 去重更新）一份设备链。
     *
     * ## 去重规则（对齐 pk-node 的 `upsertDeviceChain`）
     *
     * `ks_deviceid` 相同即视为**同一台设备**，只更新内容、不新增条目 ——
     * 否则反复导入同一台机器会灌出一堆重复项。
     *
     * @param cookies 明文 cookie 列表（本方法内部加密后存）
     * @return 落库后的条目
     */
    fun upsert(label: String, cookies: List<SessionStore.CookieEntry>): Item {
        val deviceId = cookies.firstOrNull { it.name == DEVICE_ID_COOKIE }?.value
            ?.takeIf { it.isNotBlank() }
        val encoded = DeviceChainCipher.encrypt(
            json.encodeToString(cookies),
        ) ?: json.encodeToString(cookies)

        val items = listAll().toMutableList()
        val index = if (deviceId != null) {
            items.indexOfFirst { it.deviceId == deviceId }
        } else {
            -1
        }
        return if (index >= 0) {
            val updated = items[index].copy(
                label = label,
                cookies = encoded,
                deviceId = deviceId,
            )
            items[index] = updated
            save(items)
            Log.i(TAG, "设备链已更新 label=$label deviceId=$deviceId")
            updated
        } else {
            val nextId = (items.maxOfOrNull { it.id } ?: 0L) + 1L
            val item = Item(id = nextId, label = label, cookies = encoded, deviceId = deviceId)
            items += item
            save(items)
            Log.i(TAG, "设备链已入池 label=$label deviceId=$deviceId")
            item
        }
    }

    fun remove(id: Long): Boolean {
        val items = listAll()
        val next = items.filterNot { it.id == id }
        if (next.size == items.size) return false
        save(next)
        return true
    }

    fun setEnabled(id: Long, enabled: Boolean): Boolean {
        val items = listAll().toMutableList()
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return false
        items[i] = items[i].copy(enabled = enabled)
        save(items)
        return true
    }

    // ---- 选择与套用 ----

    /**
     * 从池里挑一份设备链。
     *
     * @param pick 指定下标（对池长取模）；null 则**随机** —— 随机即是「多点轮换」，
     *             避免每次都用同一份。
     * @return 选中的条目；池空返回 null。
     */
    fun pick(pick: Int? = null): Item? {
        val pool = list()
        if (pool.isEmpty()) return null
        val idx = if (pick != null) {
            ((pick % pool.size) + pool.size) % pool.size
        } else {
            kotlin.random.Random.nextInt(pool.size)
        }
        return pool[idx]
    }

    /**
     * 若当前会话**缺设备链**，则从池里取一份补上。
     *
     * 这是「登录自动补链」的落点：登录只下发 `sid`，`ks_*` 需要设备注册或池子。
     *
     * @param current 当前会话 cookie
     * @return 补链后的 cookie 列表；**未补链时原样返回**（调用方可据
     *         [Result.applied] 决定是否回写）
     */
    fun applyToIfMissing(current: List<SessionStore.CookieEntry>): Result {
        val has = current.any { it.name == DEVICE_ID_COOKIE && it.value.isNotBlank() }
        if (has) return Result(current, applied = false, from = "self")
        val item = pick() ?: return Result(current, applied = false, from = "pool-empty")
        return applyTo(current, item)
    }

    /**
     * 把指定设备链**套用**到一份 cookie 列表上。
     *
     * 做法：移除原有的 `ks_*`，再把池里那份的 `ks_*` 合并进去 ——
     * 只覆盖设备链相关项，**不动** `sid` / `sess` / `userid` 等身份凭据。
     * 这是「用 A 的设备链跑 B 的账号」的关键：身份用 B 的，设备指纹用 A 的。
     *
     * @return [Result]（cookies 为合并结果）
     */
    fun applyTo(
        current: List<SessionStore.CookieEntry>,
        item: Item,
    ): Result {
        val chain = item.decodeCookies()
        if (chain.isEmpty()) return Result(current, applied = false, from = "decode-empty")
        // 只挑设备链项（ks_*），避免把池里的 sid/userid 也灌进来 —— 那会**改坏身份**。
        val chainKs = chain.filter { it.name.startsWith("ks_") }
        if (chainKs.isEmpty()) return Result(current, applied = false, from = "no-ks")
        val kept = current.filterNot { it.name.startsWith("ks_") }
        return Result(
            cookies = kept + chainKs,
            applied = true,
            from = "pool#${item.id}" + (item.deviceId?.let { "($it)" } ?: ""),
        )
    }

    /** [applyToIfMissing] / [applyTo] 的返回。 */
    data class Result(
        val cookies: List<SessionStore.CookieEntry>,
        val applied: Boolean,
        val from: String,
    )

    /**
     * 池内条目数 / 摘要，供 UI 显示。
     */
    fun summary(): String {
        val all = listAll()
        if (all.isEmpty()) return "池为空"
        val enabled = all.count { it.enabled }
        val ids = all.mapNotNull { it.deviceId }.joinToString(", ")
        return "${all.size} 份（启用 $enabled）" + if (ids.isNotEmpty()) " · deviceId: $ids" else ""
    }
}