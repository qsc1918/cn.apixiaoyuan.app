package cn.apixiaoyuan.app.core.pk

import cn.apixiaoyuan.app.core.log.AppLogger
import cn.apixiaoyuan.app.core.native.ContentBridge
import cn.apixiaoyuan.app.core.network.NetworkConfig
import cn.apixiaoyuan.app.core.sign.SignComputer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * PK 链路的**真机协议表**（2026-10-02 移植自 `D:\pk-node` 最新一版，commit
 * `804c8ac fix: PK 60秒频控 请求400/417/403`）。
 *
 * ## 为什么必须单独一张表，而不是复用主域那套（[cn.apixiaoyuan.app.core.network.CommonQueryInterceptor]）
 *
 * 本项目此前 PK 用 `_productId=631&_appId=6&version=3.141.1` + 主域公共参数
 * （`android<本机SDK>` / `vendor=UC` / `webviewVersion=150` / `whRatio=2.17`），
 * 出题走**旧版明文 `match`**。pk-node 逐项实测后的结论是：
 *
 * | 项 | 旧（本项目） | 新（真机逐字） | 后果 |
 * |---|---|---|---|
 * | `_productId` / `_appId` | `631` + `6` | **`611`，不带 `_appId`** | 631 走的是另一套鉴权链，出题频控窗口被压到 ≈60s/账号 |
 * | `version` | `3.141.1` | **`3.143.1`**（App 实际版本号，非服务端放行版） | — |
 * | `platform` | `android<本机SDK>` | **`android35`** | 真机 PK 抓包逐字 |
 * | `vendor` | `UC` | **`fenbi`** | 同上 |
 * | `webviewVersion` / `whRatio` | `150` / `2.17` | **`110` / `1.78`** | 同上 |
 * | `isBackground` | `0`（主域拦截器补） | **不带** | 真机 PK 出题 URL 没有它 |
 * | 出题接口 | 明文 `match` | **`match/v2`**（加密响应） | v1 明文接口风控极严；v2 才是原版 App 在用的 |
 *
 * 换到 v2 后**出题不再有「每账号 ≈60 秒」的硬等待**：原来那一串
 * `400 请求过于频繁` 其实是「631 + 明文 match」这套异构请求被服务端按频控处理，
 * 不是真的账号级冷却。这是本次移植提速的根本原因。
 *
 * ⚠️ PK 与主域（练习）**各自一张表，千万不要互相套用**：
 * 主域必须 `3.140.1` + `android37`，PK 必须 `3.143.1` + `android35`。
 *
 * ## 加密响应怎么解
 *
 * `match/v2` 返回的是密文（不是 JSON、也不是真 gzip）：
 *
 * ```
 * 密文 --libContentEncoder 的 c()（= 与固定密钥流逐位 XOR，对称）--> gzip 字节 --gunzip--> 明文 JSON
 * ```
 *
 * [ContentBridge.encode] 是**对称**的同一入口，所以直接拿它解即可
 * （pk-node 侧等价实现是 `keystream.xorEncode`，两者逐字节一致）。
 */
object PkProtocol {

    private const val TAG = "PkProtocol"

    /** 主域（PK 挂在主域）。 */
    val BASE: String = NetworkConfig.leoBaseUrl()

    /** PK H5 页面目录（拼 Referer 用）。 */
    private const val H5_DIR = "/bh5/leo-web-oral-pk/"

    // ---- 公共查询参数（真机 PK 出题请求逐字）----

    /**
     * 产品线 id。真机 PK 出题请求用的是 **611**，且**不带 `_appId`**
     * （`_appId=6` + `631` 是本项目此前的口径，见类 KDoc）。
     */
    const val PRODUCT_ID = "611"

    /** App **实际版本号**（不是主域放行版 `3.140.1`）。 */
    const val VERSION = "3.143.1"

    /** 真机抓包逐字：`android35`（**不是**本机 SDK 号）。 */
    const val PLATFORM = "android35"

    const val VENDOR = "fenbi"
    const val AV = "5"
    const val DEVICE_CATEGORY = "phone"
    const val WEBVIEW_VERSION = "110"
    const val WH_RATIO = "1.78"

    /**
     * PK 端点的公共查询参数（顺序与 pk-node `buildUrl` 一致：`_productId` 必须最前）。
     *
     * ⚠️ **这是全工程唯一一份 PK 公共参数表**。H5 代理
     * （[cn.apixiaoyuan.app.core.oldsimian.PkH5Proxy]）也直接取用它 ——
     * 以前两边各写一份，改一边忘一边就会「原生通了、H5 还是 401」。
     */
    val COMMON_QUERY: List<Pair<String, String>> = listOf(
        "_productId" to PRODUCT_ID,
        "platform" to PLATFORM,
        "version" to VERSION,
        "vendor" to VENDOR,
        "av" to AV,
        "deviceCategory" to DEVICE_CATEGORY,
        "webviewVersion" to WEBVIEW_VERSION,
        "whRatio" to WH_RATIO,
    )

    // ---- UA（两套，别混）----

    /**
     * 出题用的 H5 WebView UA（真机抓包逐字）。
     *
     * ⚠️ 与主域「App 原生」UA（`Leo/...`）不是一套：PK 出题由 H5 页面发起，
     * 用错 UA 会被判异构。
     */
    val PK_WEBVIEW_UA: String =
        "Mozilla/5.0 (Linux; Android 15; DCO-AL00 Build/V417IR; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/110.0.5481.154 Mobile Safari/537.36 " +
            "YuanSouTiKouSuan/$VERSION"

    /**
     * 提交 / 结算 / 首页 用的默认 UA（pk-node `src/http.js` 的 `DEFAULT_UA`）。
     *
     * 这几个端点 pk-node 没有显式覆盖 UA，走的就是它；OkHttp 不能留默认的
     * `okhttp/4.x`（会被判非浏览器/非 App 请求）。
     */
    const val DEFAULT_UA: String =
        "Mozilla/5.0 (Linux; Android 17; onyx Build/AP3A) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/130.0.0.0 Mobile Safari/537.36 " +
            "YuanSouTiKouSuan/3.141.1"

    // ---- 端点路径 ----

    /** 出题（`match/v2`）与提交的路径 + 方法，按玩法分流。 */
    enum class Endpoint(
        /** 出题路径（v2，加密响应）。 */
        val matchPath: String,
        /** 提交路径。 */
        val submitPath: String,
        /** 提交方法。 */
        val submitMethod: String,
    ) {
        MATH(
            matchPath = "/leo-game-pk/android/math/pk/match/v2",
            submitPath = "/leo-game-pk/android/math/pk/submit",
            submitMethod = "PUT",
        ),
        MULTI(
            matchPath = "/leo-game-pk/android/math/pk/multi/match/v2",
            submitPath = "/leo-game-pk/android/math/pk/multi/submit",
            submitMethod = "POST",
        ),
        FINAL(
            matchPath = "/leo-game-pk/android/final/pk/match/math/v2",
            submitPath = "/leo-game-pk/android/final/pk/submit/math",
            submitMethod = "POST",
        ),
        ENGLISH(
            matchPath = "/leo-game-pk/android/english/pk/match/v2",
            submitPath = "/leo-game-pk/android/english/pk/submit",
            submitMethod = "POST",
        ),
    }

    /** 玩法 → 端点。 */
    fun endpoint(mode: PkMode): Endpoint = when (mode) {
        PkMode.MATH -> Endpoint.MATH
        PkMode.MULTI -> Endpoint.MULTI
        PkMode.FINAL -> Endpoint.FINAL
        PkMode.ENGLISH -> Endpoint.ENGLISH
    }

    // ---- URL ----

    /**
     * 组装 PK 请求的完整 URL：`_productId` 领先，公共参数随后，业务参数最后，`sign` 收尾。
     *
     * `sign` 的输入是 encodedPath（不含 query），因此顺序不影响它本身；
     * 顺序只是为了让抓包日志与真机逐字一致。
     *
     * @param path   端点路径，如 `/leo-game-pk/android/math/pk/match/v2`
     * @param params 业务参数（如 `pointId` / `triggerPeakMatch` / `pkIdStr` / `grade`）
     */
    fun buildUrl(path: String, params: Map<String, String?> = emptyMap()): HttpUrl {
        val builder = BASE.toHttpUrl().newBuilder()
            .addPathSegments(path.trimStart('/'))
        for ((k, v) in COMMON_QUERY) builder.addQueryParameter(k, v)
        for ((k, v) in params) {
            if (v == null) continue
            builder.addQueryParameter(k, v)
        }
        // sign 缺失时主域 solar-encoder 会拦（417）。这里**必须**用 PK 版资产
        // （`version=3.143.1` 对应 libRequestEncoderPk.so）—— 用练习版资产算出的
        // sign 会被判版本不符，同样 417。见 [SignComputer.Variant]。
        val sign = SignComputer.signForPath(path)
        if (sign == null) {
            // 不静默：没带上 sign 的 PK 请求必然 417，日志里必须留下这一行，
            // 否则排查时会误以为是「参数不对」而不是「so 没装载」。
            AppLogger.w(TAG, "PK 请求未带 sign（签名资产不可用）：$path —— 服务端会判 417")
        } else {
            builder.addQueryParameter("sign", sign)
        }
        return builder.build()
    }

    /** 出题 URL：`pointId` + `triggerPeakMatch=0`（真机逐字，缺了会被判非对局页发起）。 */
    fun matchUrl(mode: PkMode, pointId: Int): HttpUrl =
        buildUrl(
            endpoint(mode).matchPath,
            mapOf("pointId" to pointId.toString(), "triggerPeakMatch" to "0"),
        )

    /** 提交 URL（无业务参数）。 */
    fun submitUrl(mode: PkMode): HttpUrl = buildUrl(endpoint(mode).submitPath)

    /** 结算明细 URL。真机上这是结算页 `result.html?pkIdStr=X` 的数据源。 */
    fun historyDetailUrl(pkIdStr: String): HttpUrl =
        buildUrl("/leo-game-pk/android/math/pk/history/detail", mapOf("pkIdStr" to pkIdStr))

    /** PK 首页 URL（对局类型 + 分数）。 */
    fun homeUrl(grade: Int): HttpUrl =
        buildUrl("/leo-game-pk/android/math/pk/home", mapOf("grade" to grade.toString()))

    // ---- 请求头 ----

    /**
     * 风控头（`X-XYKS-REQ-TIMESTAMP` / `X-XYKS-REQ-NETWORK-ENV` / `x-shepherd-sessionid`）。
     *
     * 提交、结算、首页三个端点都带这一组（对齐 pk-node `riskHeaders()`）。
     */
    fun riskHeaders(): Map<String, String> = mapOf(
        "X-XYKS-REQ-TIMESTAMP" to System.currentTimeMillis().toString(),
        "X-XYKS-REQ-NETWORK-ENV" to "mobile",
        "x-shepherd-sessionid" to "0",
    )

    /**
     * 出题的 H5 请求头（真机抓包逐字）。
     *
     * ⚠️ **故意不写 `Accept-Encoding`**（真机抓包里有，但这里必须省）：
     * 一旦显式声明，OkHttp 就**关掉透明 gunzip**。而我们要的正是「拿到服务端
     * 实际发出的那些字节」：
     *
     *  - 服务端没压 → 拿到原始密文 → [decodeEncrypted] 直接 XOR；
     *  - 服务端声明了 `Content-Encoding: gzip` → OkHttp 透明解开 → 拿到的仍是密文。
     *
     * 两条路都得到密文，而**错误响应**（400 `请求过于频繁` 之类）也能被
     * 正常解成可读文本 —— 这对判「是不是频控」是必需的。反过来说，
     * 若显式声明了 gzip，错误响应会变成压缩乱码，`isRateLimited` 就判不出来。
     *
     * @param page Referer 指向的 H5 页名，对局页是 `exercise.html`
     */
    fun pkH5Headers(page: String = "exercise.html"): Map<String, String> = mapOf(
        "User-Agent" to PK_WEBVIEW_UA,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
        "Content-Type" to "application/x-www-form-urlencoded",
        "Origin" to BASE,
        "X-Requested-With" to "com.fenbi.android.leo",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Dest" to "empty",
        "Referer" to BASE + H5_DIR + page,
    )

    /**
     * 提交头：octet-stream + 对局页 Referer + 风控头。
     *
     * ⚠️ **不显式写 `Accept-Encoding`**：这几个端点的响应是明文 JSON，
     * 交给 OkHttp 自己加 `Accept-Encoding: gzip` 并**透明 gunzip** 最省事
     * （一旦我们显式声明，OkHttp 就会关掉透明解压，返回压缩字节 —— 那时
     * `text` 就是乱码）。pk-node 那边是「显式 gzip + 手动 gunzip」，等效。
     *
     * 只有出题（[pkH5Headers]）需要显式声明，因为我们要拿**原始密文字节**。
     */
    fun submitHeaders(): Map<String, String> = mapOf(
        "User-Agent" to DEFAULT_UA,
        "Content-Type" to "application/octet-stream",
        "Accept" to "application/json, text/plain, */*",
        "Referer" to BASE + H5_DIR + "pk.html",
    ) + riskHeaders()

    /** 结算明细头：结算页 Referer（`result.html?pkIdStr=X`）+ 风控头。 */
    fun historyHeaders(pkIdStr: String): Map<String, String> = mapOf(
        "User-Agent" to DEFAULT_UA,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to BASE + H5_DIR + "result.html?pkIdStr=$pkIdStr",
    ) + riskHeaders()

    /** 首页头。 */
    fun homeHeaders(): Map<String, String> = mapOf(
        "User-Agent" to DEFAULT_UA,
        "Accept" to "application/json, text/plain, */*",
    ) + riskHeaders()

    // ---- 编解码 ----

    /**
     * 解密「加密响应」：密文 → `c()`（XOR）→ gunzip → 明文 JSON。
     *
     * 判定与 pk-node `leo.decodeEncryptedResponse` 同一套（改动务必同步），
     * 外加一层「HTTP 层又压了一层」的兜底：
     *
     * | 响应开头 | 处理 |
     * |---|---|
     * | `{` / `[` | 已是明文 JSON → 返回 null（调用方按原样处理） |
     * | `1f 8b` | 真 gzip → 先 gunzip；解出来是 JSON 就直接用，是密文再走 XOR |
     * | 其它 | 按密文解：XOR → 期待 `1f 8b`（再 gunzip）或 `{` |
     *
     * @return 明文字节；判定为「不是密文」时返回 null
     */
    fun decodeEncrypted(bytes: ByteArray): ByteArray? {
        if (bytes.size < 2) return null
        if (bytes[0] == 0x7B.toByte() || bytes[0] == 0x5B.toByte()) return null
        if (isGzip(bytes)) {
            val un = runCatching { gunzip(bytes) }.getOrNull() ?: return null
            if (un.isNotEmpty() && (un[0] == 0x7B.toByte() || un[0] == 0x5B.toByte())) return un
            return decodeXor(un)
        }
        return decodeXor(bytes)
    }

    /** `c()` 就是「与固定密钥流逐位 XOR」，对称；解完应是 gzip 流或裸 JSON。 */
    private fun decodeXor(cipher: ByteArray): ByteArray? {
        val dec = ContentBridge.encode(cipher) ?: return null
        if (isGzip(dec)) return runCatching { gunzip(dec) }.getOrNull()
        if (dec.isNotEmpty() && dec[0] == 0x7B.toByte()) return dec
        return null
    }

    private fun isGzip(b: ByteArray): Boolean =
        b.size >= 2 && b[0] == 0x1F.toByte() && b[1] == 0x8B.toByte()

    /**
     * 提交体编码：明文 JSON → gzip(level 6) → `c()`（XOR）→ 密文。
     *
     * 与 [cn.apixiaoyuan.app.core.network.EncodeBridge] 的 `NativePayloadEncoder`
     * 同一条链路，这里显式走一遍是为了让 PK 的编码在 Repository 里可见
     * （加密要起 JNI，日志需要在「加密中 / 加密完成」之间插事件）。
     *
     * @return 密文；编码器不可用时返回 null（**宁可失败，也不要发明文出去** ——
     *         声明了 octet-stream 却发明文会被服务端判 417）
     */
    fun encodeSubmitBody(plainJson: ByteArray): ByteArray? {
        if (plainJson.isEmpty()) return null
        val gz = runCatching {
            val out = ByteArrayOutputStream(plainJson.size.coerceAtLeast(64))
            java.util.zip.GZIPOutputStream(out).use { it.write(plainJson) }
            out.toByteArray()
        }.getOrNull() ?: return null
        return ContentBridge.encode(gz)
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        ByteArrayInputStream(bytes).use { input ->
            GZIPInputStream(input).use { gz ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = gz.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            }
        }
    }
}
