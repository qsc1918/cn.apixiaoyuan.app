package cn.apixiaoyuan.app.core.session

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.apixiaoyuan.app.core.network.AuthInterceptor
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 登录会话存储。
 *
 * R2 已由真机确证：登录凭据不是响应体里的字段，而是服务端通过
 * `Set-Cookie` 下发的 Cookie 集合。原版把整个 cookie 列表以 JSON 存进
 * MMKV 的 `cookie_store`，键为 `cookieJsonListKey`。
 *
 * 这里用 SharedPreferences 落地等价结构 —— 不引入 MMKV 依赖，
 * 存的是同一份 JSON（[CookieEntry] 列表），字段名与原版逐字对齐，
 * 这样真机上抓到的 cookie 字符串可以直接喂进来，也能原样导出。
 *
 * 真机实测的关键 cookie（Redmi onyx / Android 16）：
 *  - `sid`    = 7012770506069852030，expiresAt=253402300799999（永不过期）
 *  - `userid` = 1066052990，等于 UserVO.userId，就是 YFD_U 要注入的值
 *  - `sess` / `g_sess` / `ks_sess` = 会话 token
 *  - `ks_persistent` / `ks_r` / `ks_u` / `ks_deviceid` = 设备指纹与风控链
 */
object SessionStore {

    private const val PREF_NAME = "leo_session"
    private const val KEY_COOKIES = "cookieJsonListKey"
    private const val KEY_YFD_U = "yfd_u"
    private const val KEY_GRADE = "grade"
    private const val KEY_NICKNAME = "current_nickname"
    private const val KEY_AVATAR = "current_avatar_url"

    /**
     * 设备链 cookie 的名字前缀（`ks_r` / `ks_u` / `ks_persistent` / `ks_deviceid` …）。
     *
     * 这类 cookie 的 **value 落盘前要加密**（用户要求，2026-09-28）。
     * `ks_deviceid` 是**设备级**标识（跨账号同一台设备不变），泄漏面比普通
     * 会话 cookie 更大 —— 拿到它就能在别处伪装成同一台设备。
     */
    private const val DEVICE_CHAIN_PREFIX = "ks_"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var appContext: Context? = null

    /**
     * 会话状态修订号 —— 任何「会改变登录态/当前身份」的写入都自增一次。
     *
     * 主页的子账号卡片列表需要**随登录 / 切换账号即时刷新**，但 [SessionStore]
     * 底层是 SharedPreferences（非 Compose 可观察）。这里用一个 Compose 可观察
     * 计数器当「版本戳」：UI 读它进入重组，重新拉取子账号列表。
     *
     * 只做**粗粒度**通知（登录 / 登出 / 切号 / 导入 cookie 都 +1），
     * 不承载具体语义 —— 具体状态仍以 [SessionStore] 各 getter 为准。
     */
    var stateRevision by mutableStateOf(0)
        private set

    private fun bump() {
        stateRevision++
    }

    /**
     * 解密后的 cookie 列表**内存缓存**。
     *
     * ## 为什么需要（2026-09-29 掉帧真因）
     *
     * [loadCookies] 的工作量不轻：读 SharedPreferences 字符串 → JSON 解析 →
     * 对每条 `ks_*` 走 **Android Keystore** 做 AES-GCM 解密。Keystore 是
     * 系统服务，每次调用都是跨进程 IPC（毫秒级）。
     *
     * 而它此前被**在 Compose 组合期间反复调用**：
     *  - `HomeScreen.SessionCard` 一次组合里调 3 次（`isLoggedIn` → `cookie("userid")`、
     *    `yfdU`、显式取值各一次）；
     *  - `HomeScreen.SubAccountsSection` 每帧再调一次；
     *  - `AccountScreen` 设备链池对每条链的 `decodeCookies()` 也在组合里。
     *
     * 表现为「账号列表一加载出来，界面就一卡一卡」—— 每次重组都在主线程做
     * 几十次 Keystore IPC，直接掉帧。
     *
     * ## 缓存策略
     *
     * **写时失效**：所有写入路径（[saveCookies] / [clear]）都会重置本缓存。
     * 由于本项目所有 cookie 写入都收口在 [saveCookies]（`PersistentCookieJar`
     * 落盘、[importCookieHeader]、[upsertCookie]、设备链补链都走它），
     * 因此缓存不会与磁盘不一致。
     *
     * 缓存的是**明文**列表（[loadCookies] 的返回形态），不是落盘密文。
     */
    @Volatile
    private var cachedCookies: List<CookieEntry>? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs(): SharedPreferences {
        val ctx = appContext ?: error("SessionStore.init() 未调用")
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 单个 cookie。字段名对齐原版 MMKV 里的 JSON。
     */
    @Serializable
    data class CookieEntry(
        @SerialName("domain") val domain: String,
        @SerialName("name") val name: String,
        @SerialName("value") val value: String,
        @SerialName("path") val path: String = "/",
        @SerialName("expiresAt") val expiresAt: Long = 0L,
        @SerialName("hostOnly") val hostOnly: Boolean = false,
        @SerialName("httpOnly") val httpOnly: Boolean = true,
        @SerialName("persistent") val persistent: Boolean = true,
        @SerialName("secure") val secure: Boolean = false,
    )

    /**
     * 写入完整 cookie 列表（登录成功后调用）。
     *
     * ## 设备链 `ks_*` 的值在**落盘前加密**（2026-09-28）
     *
     * 用户要求：设备链不得明文存库。[DeviceChainCipher] 用 Android Keystore
     * 的 AES-GCM 只加密 **value**（`name/domain/path` 保持明文，便于筛选）。
     * 幂等：已是密文的不重复加密，因此反复保存不会套娃。
     */
    fun saveCookies(cookies: List<CookieEntry>) {
        val toStore = cookies.map { it.encryptedForStorage() }
        // ★★ 2026-09-30：**幂等守卫** —— 「日志一直刷屏」的真凶就在这一句。
        //
        // ## 自激环（铁证：真机日志里 user-info/context/batchGet 每 ~300ms 一轮，永不停）
        //
        // `PersistentCookieJar` 在**每个 HTTP 响应**返回时都会调本方法（把响应的
        // Set-Cookie 合并进来）。而 cookie 绝大多数时候**没有任何变化** ——
        // 原实现在这种情况下依然 `bump()`，于是：
        //
        //     bump() → HomeViewModel 的 snapshotFlow 发射 → refreshAccounts()
        //           → fetchSubAccounts() → 多个主域请求
        //           → 每个响应又触发 PersistentCookieJar.saveCookies()
        //           → bump() → ……（转不停，每轮 ≈ debounce 250ms + 请求耗时）
        //
        // 表现就是：① 日志页被 `LeoNet` 刷屏；② 主页/列表一直在重组。
        //
        // 修法：**内容与已存的一致就什么都不做**（不写盘、不 bump）。
        // 判据用「落盘形态的 JSON 字符串」比较 —— 它同时覆盖了 name/value/domain/
        // path/expires 等全部字段，且与 [loadCookies] 的缓存形态同源，最稳。
        val encoded = json.encodeToString(toStore)
        if (prefs().getString(KEY_COOKIES, null) == encoded) return
        prefs().edit().putString(KEY_COOKIES, encoded).apply()
        // 缓存失效必须在写盘之后、bump 之前：bump 会让 UI 立刻重组并
        // 读 loadCookies()，若此时缓存还指向旧值就会闪一帧旧数据。
        // 存的是「loadCookies() 会返回的形态」（对落盘形态解回来），
        // 这样即便调用方传入的 value 已是密文（幂等场景）也不会缓存错。
        cachedCookies = toStore.map { it.decryptedFromStorage() }
        // sid 或 userid 存在即视为有登录态（userid 不加密，直接读原值）
        cookies.firstOrNull { it.name == "userid" }?.value?.toLongOrNull()?.let {
            prefs().edit().putLong(KEY_YFD_U, it).apply()
        }
        bump()
    }

    /**
     * 单独写入 `YFD_U`（登录态兜底）。
     *
     * 正常路径下 `userid` cookie 由 [saveCookies] 一并写入；本方法供
     * 直连版登录在「响应体解析成功、但响应未带 Set-Cookie」时兜底，
     * 避免解析成功却被判为未登录。
     */
    fun saveYfdU(value: Long) {
        // 幂等：值没变就不写、也不 bump。
        //
        // ## 为什么必须幂等（2026-09-29 掉帧/自激真因）
        //
        // [bump] 会让 [stateRevision] 变化，而 `HomeViewModel` 监听它 → 重拉
        // 子账号列表；重拉过程又会回写 YFD_U / 昵称 / 年级 —— 若这里无条件
        // bump，就形成「刷新 → 回写 → bump → 再刷新」的**自激循环**，
        // 表现为主页周期性卡顿。值相同即无事发生，天然断环。
        if (yfdU == value) return
        prefs().edit().putLong(KEY_YFD_U, value).apply()
        bump()
    }

    /**
     * 从标准 `Cookie` 请求头字符串导入 cookie（合并，同名覆盖）。
     *
     * ## 2026-09-27 更正：主域**确实需要**设备链（sid + ks_*）
     *
     * 早先（2026-09-26）误判「主域不需要设备链」，依据是探针
     * `GET /leo-star/android/exercise/rank/pre-fetch` 去掉 sid/ks_* 仍 200。
     * **该探针是服务端路径白名单特例**（既不走认证也不走编码），不能代表主域。
     *
     * 用真实业务端点（`batchGet` / `switch`）实测：
     *  - 缺 ks_* → 401 `x-block-by: leo-auth`（认证层挡）
     *  - 有 ks_* → 417 `x-block-by: solar-encoder`（认证已过，卡传输层）
     *
     * 而本项目登录只下发 `sid`，`ks_*` 需先调设备注册
     * （`POST /leo-auth/android/user-devices`，见 [cn.apixiaoyuan.app.core.auth.DeviceRegistrar]）
     * 由 Set-Cookie 下发。本方法保留为**应急通道**（例如切换环境 / 排障时
     * 手工灌 cookie），也可用于从原版导入设备链。
     *
     * 域统一按 `yuanfudao.com` 写入（与原版 MMKV 形态一致），
     * 这样对 `ape-api` 与 `xyks` 两个子域同时生效。导入只覆盖同名项，
     * 不会清掉已登录拿到的 cookie。
     *
     * @param header `name=value; name2=value2` 形态的串
     * @return 实际解析出的条目数（0 表示格式不对）
     */
    fun importCookieHeader(header: String): Int {
        val parsed = header.split(';')
            .mapNotNull { part ->
                val kv = part.trim()
                if (kv.isEmpty()) return@mapNotNull null
                val eq = kv.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val name = kv.substring(0, eq).trim()
                val value = kv.substring(eq + 1).trim()
                if (name.isEmpty() || value.isEmpty()) return@mapNotNull null
                name to value
            }
        if (parsed.isEmpty()) return 0

        val merged = linkedMapOf<String, CookieEntry>()
        loadCookies().forEach { merged[it.name] = it }
        parsed.forEach { (name, value) ->
            merged[name] = CookieEntry(
                domain = "yuanfudao.com",
                name = name,
                value = value,
                path = "/",
                // 导入的 cookie 不设过期时刻：原版这些字段的 expiresAt 是远未来
                // （253402300799999），但导入时无从得知，留 0 让 CookieJar
                // 按 session cookie 处理 —— 至少不会因过期立刻失效。
                expiresAt = 0L,
                hostOnly = false,
                httpOnly = true,
                persistent = true,
                secure = false,
            )
        }
        saveCookies(merged.values.toList())
        // ★ 含设备链就自动入池（2026-09-28，对齐 pk-node「导入即入池」）——
        //   导入是从原版/别处取设备链的主通道，不沉淀下来就白导了。
        val list = merged.values.toList()
        if (list.any { it.name.startsWith(DEVICE_CHAIN_PREFIX) }) {
            DeviceChainPool.upsert(label = "导入", cookies = list)
        }
        return parsed.size
    }

    /**
     * 读出全部 cookie；无会话时返回空表。
     *
     * **`ks_*` 的值在此透明解密** —— 调用方拿到的永远是明文，
     * 无需关心存储形态。历史明文数据 [DeviceChainCipher.decrypt] 会原样返回，
     * 所以不需要额外的迁移步骤。
     */
    fun loadCookies(): List<CookieEntry> {
        // 命中缓存直接返回 —— 避免每次重组都重做「JSON 解析 + N 次 Keystore 解密」。
        cachedCookies?.let { return it }
        val raw = prefs().getString(KEY_COOKIES, null)
        if (raw == null) {
            cachedCookies = emptyList()
            return emptyList()
        }
        val list = runCatching { json.decodeFromString<List<CookieEntry>>(raw) }
            .getOrDefault(emptyList())
        val decrypted = list.map { it.decryptedFromStorage() }
        cachedCookies = decrypted
        return decrypted
    }

    /** 取某个 cookie 的值，没有返回 null。 */
    fun cookie(name: String): String? = loadCookies().firstOrNull { it.name == name }?.value

    /**
     * 按名字写入一条 cookie（已存在则改值，不存在则新增）。
     *
     * ## 为什么需要它（切换子账号，待办 #6）
     *
     * 用户明确要求：「切换子账号的 cookie 应该使用登录产生的」。
     *
     * 切换成功后，服务端会通过 `Set-Cookie` 下发新的 `userid`（PersistentCookieJar
     * 通常会自动落盘），但**主域请求是否带得上，取决于这条 cookie 的 domain/path
     * 是否被正确改写**。若切换响应没带 `Set-Cookie`（或带了但 jar 未落盘），
     * 后续主域请求仍用旧身份 —— 表现就是「切换了但没生效」。
     *
     * 因此这里提供一条显式的写路径：切换成功后把新 userId 写回 `userid` cookie，
     * **沿用原条目的 domain/path/过期**（不改这些属性，避免把 cookie 挪错域），
     * 只换 value。没有同名条目时按主域新建一条。
     *
     * @param name   cookie 名（如 `userid`）
     * @param value  新值
     * @param domain 无同名条目时使用的域
     * @return 是否写入成功
     */
    fun upsertCookie(name: String, value: String, domain: String = ".yuanfudao.com"): Boolean {
        val current = loadCookies().toMutableList()
        val index = current.indexOfFirst { it.name == name }
        if (index >= 0) {
            val old = current[index]
            current[index] = old.copy(value = value)
        } else {
            current += CookieEntry(
                domain = domain,
                name = name,
                value = value,
                path = "/",
                expiresAt = 0L,
                hostOnly = false,
                httpOnly = true,
                persistent = true,
                secure = false,
            )
        }
        saveCookies(current)
        return true
    }

    /**
     * 落盘前的形态：`ks_*` 的 value 加密，其余原样。
     *
     * 只对 `ks_` 前缀加密 —— 用户要求的是「设备链加密」，不是「全部 cookie 加密」；
     * 其余 cookie（sid / sess / userid 等）体积小、变动频繁且与存储层其它逻辑
     * 耦合较多，贸然全量加密会扩大改动面。
     */
    private fun CookieEntry.encryptedForStorage(): CookieEntry =
        if (name.startsWith(DEVICE_CHAIN_PREFIX)) {
            copy(value = DeviceChainCipher.encrypt(value) ?: value)
        } else {
            this
        }

    /** 读出形态：`ks_*` 的 value 解密（非密文原样返回，兼容历史明文）。 */
    private fun CookieEntry.decryptedFromStorage(): CookieEntry =
        if (name.startsWith(DEVICE_CHAIN_PREFIX)) {
            copy(value = DeviceChainCipher.decrypt(value) ?: value)
        } else {
            this
        }

    /**
     * 当前登录用户 ID（`YFD_U` 的取值）。
     *
     * 真机确证：`userid` cookie 与 `UserVO.userId` 同值。
     */
    val yfdU: Long?
        get() {
            val v = prefs().getLong(KEY_YFD_U, -1L)
            return if (v == -1L) cookie("userid")?.toLongOrNull() else v
        }

    /**
     * 当前登录用户的年级 ID。
     *
     * PK 入口数据（`/leo-game-pk/android/game/homepage`）按年级取，
     * 用错年级会拉到别的年级的入口。真机来源是 `UserVO.grade`。
     *
     * 会话里没存过（未登录 / 老会话）时返回 null，调用方自行回退。
     */
    fun grade(): Int? {
        val v = prefs().getInt(KEY_GRADE, -1)
        return if (v == -1) null else v
    }

    /** 保存年级 ID（登录 / 拉到 UserVO 后调用）。 */
    fun saveGrade(grade: Int) {
        // 幂等：值没变就不写（不 bump —— saveGrade 本来也不 bump，
        // 但省掉一次无意义的 SharedPreferences 写）。
        if (this.grade() == grade) return
        prefs().edit().putInt(KEY_GRADE, grade).apply()
    }

    /**
     * 当前用户的昵称 / 头像缓存。
     *
     * 来源：主页子账号列表（`batchGet` 的 `UserVO`）在 `isCurrent` 项上回填；
     * PK H5 的 `getUserInfo` 桥能力读这两个值 —— H5 首屏用户卡的名字头像
     * 主要来源就是它（没有桥时首屏空白，切年级才从 homepage 响应兜底）。
     */
    val currentNickname: String?
        get() = prefs().getString(KEY_NICKNAME, null)

    val currentAvatarUrl: String?
        get() = prefs().getString(KEY_AVATAR, null)

    /** 回填当前用户昵称 / 头像（拉到 UserVO 后调用）。 */
    fun saveCurrentUserInfo(nickname: String?, avatarUrl: String?) {
        // ★ 幂等守卫 —— 这是「主页一卡一卡」的直接元凶（2026-09-29）。
        //
        // [bump] 会触发 `HomeViewModel` 重拉子账号列表（它监听 stateRevision），
        // 而重拉链路（`fetchSubAccounts` → `refreshCurrentUserProfile` 以及
        // 标 `isCurrent` 那一步）**每次都会无条件调用本方法**。于是形成自激：
        //
        //     重拉 → saveCurrentUserInfo → bump → 重拉 → …
        //
        // 只要昵称/头像没变就直接返回，环即断开。这是必要的修复，而非优化。
        if (currentNickname == nickname && currentAvatarUrl == avatarUrl) return
        prefs().edit()
            .putString(KEY_NICKNAME, nickname)
            .putString(KEY_AVATAR, avatarUrl)
            .apply()
        bump()
    }

    /** 是否已登录：`userid` cookie 存在即视为已登录。
     *
     * ## 判定口径为什么不是 `sid`
     *
     * 2026-09-25 实测：直连版 `POST /accounts/android/safe/login`
     * 成功时下发的是 `sess` / `userid` / `g_sess` / `__sub_user_infos__`
     * / `g_loc` / `persistent`，**从不含 `sid`**。
     *
     * `sid` 出现在真机原版（走运营商一键登录等其它通道）的 cookie 表里，
     * 与本项目走的短信直连通道不是同一条。早先按真机 cookie 表把 `sid`
     * 写成必要条件，会让「直连版登录成功」被判为未登录 —— 登录页跳转后
     * 首页登录态卡仍显示未登录。
     *
     * 取 `userid` 作唯一判据的原因：它同时是 [yfdU] 的取值来源，
     * 也就是本项目后续所有鉴权请求真正依赖的那个值。它存在即有登录态。
     */
    val isLoggedIn: Boolean
        get() = cookie("userid") != null

    /** 清空登录态（登出）。 */
    fun clear() {
        prefs().edit().clear().apply()
        cachedCookies = emptyList()
        bump()
    }

    /**
     * 组装成 `Cookie` 请求头（`name=value; name2=value2`）。
     *
     * 供 [AuthInterceptor] 注入用。
     */
    fun cookieHeader(): String? {
        val list = loadCookies()
        if (list.isEmpty()) return null
        return list.joinToString("; ") { "${it.name}=${it.value}" }
    }

    /** 生成给 [AuthInterceptor] 用的鉴权快照。 */
    fun snapshot(): AuthInterceptor.AuthSnapshot? {
        val cookie = cookieHeader() ?: return null
        return AuthInterceptor.AuthSnapshot(yfdU = yfdU, cookie = cookie)
    }
}

/**
 * OkHttp 持久化 CookieJar。
 *
 * R2 落地收口：登录响应的 `Set-Cookie` 由 OkHttp 在响应返回时交给这里，
 * 写入 [SessionStore]；之后每个请求的 `Cookie` 头也由这里从 [SessionStore] 组装。
 * 这比在 [AuthInterceptor] 里手动拼 cookie 更贴近原版行为 —— 原版就是
 * 靠一个 CookieJar 把整份 cookie 列表持久化到 MMKV 的 `cookie_store`。
 *
 * 与 [AuthInterceptor] 的分工：
 *  - [AuthInterceptor] 负责 `YFD_U` 查询参数注入（那是业务参数，不是 cookie）；
 *  - 本类负责 cookie 的读写。两者都读 [SessionStore]，数据源单一。
 *
 * 注意：httpOnly 的 cookie（sid / sess / g_sess 都是）在真机 MMKV 里
 * 是以明文 JSON 存的，本工程同样明文落 SharedPreferences —— 与原版一致。
 */
object PersistentCookieJar : CookieJar {

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val merged = linkedMapOf<String, SessionStore.CookieEntry>()
        // 先放入已有的，同名新 cookie 覆盖
        SessionStore.loadCookies().forEach { merged[it.name] = it }
        cookies.forEach { c ->
            // 服务端删除指令：value 为空且已过期（典型形态 Set-Cookie: ks_sess=;Max-Age=0）。
            //
            // 这种响应不是「下发一个空值 cookie」，是「让这个 cookie 失效」。
            //
            // ## 保护范围（2026-09-25 修正）
            //
            // **只对「用户导入的设备链」放行，不删** —— `sid` / `ks_*` 系列
            // 在本项目里只能靠用户从原版 App 导入，而服务端在风控拒绝时
            // 会连发三行清除指令（实测 HTTP 层可见）。若照单删除，用户刚
            // 导入的设备链会在第一次 401 后被服务端指令抹掉，功能当场失效
            // 且用户完全不知道为什么。
            //
            // 其余 cookie（`sess` / `g_sess` / `persistent` 等，本项目自己
            // 登录拿到的）**照常删除** —— 那是服务端登出的正常语义，
            // 拦下来会导致「服务端已登出、本地仍带旧 token」。
            //
            // 取舍理由：设备链是**只读凭据**（本项目拿不到也刷不了），
            // 留着最坏情况是请求被拒（可见错误）；删掉则是静默失效。
            // 宁可让用户看到明确失败，也不要静默把配置吃掉。
            if (c.value.isEmpty() && (c.expiresAt <= 0L || !c.persistent)) {
                if (!isImportedDeviceCredential(c.name)) merged.remove(c.name)
            } else {
                merged[c.name] = SessionStore.CookieEntry(
                    domain = c.domain,
                    name = c.name,
                    value = c.value,
                    path = c.path,
                    expiresAt = c.expiresAt,
                    hostOnly = c.hostOnly,
                    httpOnly = c.httpOnly,
                    persistent = c.persistent,
                    secure = c.secure,
                )
            }
        }
        SessionStore.saveCookies(merged.values.toList())
    }

    /**
     * 是否是「只能由用户导入、不可由服务端清除」的设备凭据。
     *
     * 名单来自真机原版 MMKV `cookie_store` 的实测集合
     * （`sid` / `ks_sess` / `ks_deviceid` / `ks_persistent` / `ks_r` / `ks_u`）。
     */
    private fun isImportedDeviceCredential(name: String): Boolean =
        name == "sid" || name.startsWith("ks_")

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return SessionStore.loadCookies().mapNotNull { entry ->
            // 兜底：value 为空的条目不发出。历史版本可能已经把空值写进磁盘，
            // 这里再拦一道，避免污染请求。
            if (entry.value.isEmpty()) return@mapNotNull null
            runCatching {
                Cookie.Builder()
                    .path(entry.path)
                    .name(entry.name)
                    .value(entry.value)
                    .apply {
                        // 域口径必须与 hostOnly 一致，否则 cookie 发不出去。
                        //
                        // **这里此前写反了**：无论 hostOnly 真假都调 `hostOnlyDomain(domain)`，
                        // 而 OkHttp 的 `hostOnlyDomain()` 会把 cookie 标记为「精确主机匹配」
                        // （只匹配 `yuanfudao.com`，不匹配 `xyks.yuanfudao.com`）。
                        // 结果就是服务端下发的 `.yuanfudao.com` 域 cookie（前导点 =
                        // 含子域）被错误锁死，主域请求根本带不上 —— 与主域 401 直接相关。
                        //
                        // 正确做法：
                        //  - hostOnly = false（前导点 / 显式 domain）→ `domain()`，含子域；
                        //  - hostOnly = true → `hostOnlyDomain()`，仅精确主机。
                        if (entry.hostOnly) hostOnlyDomain(entry.domain)
                        else domain(entry.domain)
                        if (entry.expiresAt > 0L) expiresAt(entry.expiresAt)
                        if (entry.httpOnly) httpOnly()
                        if (entry.secure) secure()
                    }
                    .build()
            }.getOrNull()
        }
    }
}
