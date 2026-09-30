package cn.apixiaoyuan.app.core.oldsimian

import android.util.Log
import cn.apixiaoyuan.app.core.log.AppLogger
import cn.apixiaoyuan.app.core.network.HeaderInterceptor
import cn.apixiaoyuan.app.core.network.ShepherdId
import cn.apixiaoyuan.app.core.session.SessionStore
import cn.apixiaoyuan.app.core.sign.SignComputer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * PK H5 业务请求的**原生代发器**。
 *
 * ## 为什么需要它（2026-09-27）
 *
 * 项目的 `HeaderInterceptor`（公共头 + 主域风控头）与 `CommonQueryInterceptor`（补 sign）
 * 都挂在 **OkHttp** 上，而 PK H5 的请求是 **WebView 自己发的** —— 那些头**根本不会生效**。
 *
 * 本地复现（arm64 Chromium + 真实 cookie）实测：
 * ```
 * /leo-game-pk/android/math/pk/home          → 200
 * /leo-star/android/exercise/rank/pk/rate    → 200
 * /leo-star/android/anti-addiction           → 417   ← 「点 PK 没反应」就卡在这
 * /leo-activity/android/activity/pk/daily/award → 417
 * /leo-game-pk/android/math/pk/props/home    → 417
 * ```
 * 且 417 是**逐端点**的，不是「全局缺某一组头」。
 *
 * 因此在 `WebViewClient.shouldInterceptRequest` 里把主域请求接过来，
 * 由本类用 OkHttp 代发（补齐公共参数 / sign / 风控头 / Cookie），
 * 并把每一笔的**状态码 + `x-block-by` + 关键请求头**落进日志页 ——
 * 这样 anti-addiction 这类端点缺什么，可以直接从真机日志看出来。
 *
 * ## 边界（重要）
 *
 * - 只接管 **GET/POST/PUT**；其余方法（极少）交回 WebView；
 * - 只接管 `xyks.yuanfudao.com`（主域）；账号域 / 静态资源不动；
 * - 任何异常都**回落 null**（交回 WebView 自己发），绝不阻断页面加载；
 * - 只记录**命中识别特征**（`x-block-by` / `LeoNet`）的响应，避免把
 *   页面几十个资源请求全塞进日志。
 */
internal object PkH5Proxy {

    private const val TAG = "PkH5Proxy"

    private const val LEO_HOST = "xyks.yuanfudao.com"
    private const val PARAM_SIGN = "sign"

    /** 业务主域根。 */
    private const val LEO_BASE = "https://$LEO_HOST"

    /**
     * H5 在「拿不到原生 requestConfig」时，会把 URL 模板里的
     * `{client}` / `{device}` **字面量替换成 `api`**，而不是 `android`。
     *
     * ## 证据（2026-09-27 逐字读 H5 bundle，待办 10）
     *
     * `leo-web-oral-pk/assets/request-legacy.CdI7tZrH.js`（axios 请求拦截器）：
     * ```js
     * if (a() && f("3.42.0") && (d.indexOf("{device}")>=0 || d.indexOf("{client}")>=0)) {
     *     s("requestConfig", {path:d, trigger:(r,t)=>{ e(r&&0!==r ? d : t.wrappedUrl) }}, "LeoSecure")
     * } else if (d.indexOf("{device}")>=0 || d.indexOf("{client}")>=0) {
     *     e(d.replace("{device}","api").replace("{client}","api"));   // ← 就是这里
     * }
     * ```
     * 也即：**设备判定 `a()` 不成立时走 else 分支，模板被替成 `api`**。
     * 真机实测（我们的 App）：`GET /leo-game-pk/api/math/pk/props/home → 417
     * [x-block-by: solar-encoder]` —— 服务端要的是客户端标识 `android`，
     * 收到 `api` 自然拒。
     *
     * ## 归属：这一层（原生代发）是**唯一正确的归正点**
     *
     * `{client}`/`{device}` 的取值在原版里就是 `android`
     * （H5 内部枚举 `d.ANDROID = "android"`）。H5 之所以退化成 `api`，
     * 是因为它没意识到自己跑在小猿口算 App 里；而**我们知道**。
     * 所以当 H5 把 `/leo-game-pk/api/...` 交给我们代发时，直接按
     * `android` 发才是「以真实身份发请求」。
     *
     * 不改 H5 只改这里，也避免了去逆 `a()` 那套设备判定。
     */
    private const val H5_FALLBACK_CLIENT = "api"

    /** 原版客户端标识（`d.ANDROID`）。 */
    private const val REAL_CLIENT = "android"

    /**
     * ★★ PK 端点的 `_productId` / `_appId`（2026-09-30，对齐 pk-node 权威结论）
     *
     * PK H5 打的全是 `/leo-game-pk/{client}/...`，而归一后即 `/leo-game-pk/android/...`。
     * 这些端点由 `SolarAuthFilter` 守卫，**硬要求 `_productId=631`**：
     *  - 记忆 #36（真机实测）：`_productId=611` → **401**；`631` → 200。
     *  - 与练习链路（611）是**两套口径**，不能混用。
     *
     * 此前本类的 `COMMON_PARAMS` 用的是练习的 611，且未带 `_appId` ——
     * H5 经此代发的所有 PK 请求都会 401 → 页面表现为「加载不出 / 登录态异常」。
     *
     * 原版真机 PK 请求恒为 `...&_productId=631&_appId=6&version=3.141.1`。
     */
    private const val PK_PRODUCT_ID = "631"
    private const val PK_APP_ID = "6"
    /** PK 端点自带的协议版本口径（与主域 3.140.1 不同，见 NetworkConfig 注释）。 */
    private const val PK_VERSION = "3.141.1"

    /**
     * 允许被归正的**模块段**（`api` 段的前一段）。
     *
     * 白名单而不是「见到 `api` 就换」：H5 也打其它域名/路径，
     * 万一某处 `api` 是真的路径段，误替换会打出错请求。
     * 这些模块名取自 H5 bundle 里所有 `{client}` 模板的实际前缀。
     */
    private val CLIENT_SCOPED_MODULES = setOf(
        "leo-game-pk",
        "leo-star",
        "leo-activity",
        "leo-math",
        "leo-english",
        "leo-profile",
        "leo-poetry",
        "leo-chinese",
    )

    /**
     * 代发用客户端。
     *
     * **不能**直接用 `RetrofitFactory` 的实例：那边注册了 `NeedDecodeInterceptor`，
     * 它会把 H5 的普通响应误当成 `@NeedDecode` 的密文去 gunzip + native 解码，
     * 解出来必然不是原响应。这里只要最朴素的客户端。
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    /**
     * 是否值得接管。
     *
     * 只拦主域 + 常规方法 + `http(s)`；其余（`data:` / `blob:` / 第三方域）返回 false。
     */
    fun shouldProxy(method: String, url: String): Boolean {
        if (!url.startsWith("https://$LEO_HOST/") && !url.startsWith("http://$LEO_HOST/")) return false
        return method.equals("GET", true) || method.equals("POST", true) || method.equals("PUT", true)
    }

    /**
     * 代发 H5 请求。失败返回 null（由调用方回落给 WebView 自己发）。
     *
     * @param method  HTTP 方法（大写）
     * @param url     原始 URL（可能已带 `@NeedEncode` 那种由 H5 拼好的 query）
     * @param headers WebView 侧收集到的请求头（Cookie 从这里取，保证与页面一致）
     * @param body    POST/PUT 的请求体字节；GET 传 null
     */
    fun fetch(method: String, url: String, headers: Map<String, String>, body: ByteArray?): WebResponse? {
        val signed = withSignAndCommonQuery(url)
        val cookie = headers.entries
            .firstOrNull { it.key.equals("Cookie", true) }?.value
            ?: SessionStore.cookieHeader()

        val builder = Request.Builder()
            .url(signed)
            .header("User-Agent", headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: DEFAULT_UA)
            .header("Accept", headers.entries.firstOrNull { it.key.equals("Accept", true) }?.value ?: "*/*")
            // 主域风控头（与 HeaderInterceptor 同源；H5 请求原样补上）
            .header("X-XYKS-REQ-TIMESTAMP", System.currentTimeMillis().toString())
            .header("X-XYKS-REQ-NETWORK-ENV", "mobile")
            .header("x-shepherd-did", ShepherdId.did())
            .header("x-shepherd-sessionid", "0")
        cookie?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }

        val req = when (method.uppercase()) {
            "GET" -> builder.get().build()
            "POST" -> builder.post((body ?: ByteArray(0)).toRequestBody(POST_BODY_TYPE.toMediaType())).build()
            "PUT" -> builder.put((body ?: ByteArray(0)).toRequestBody(POST_BODY_TYPE.toMediaType())).build()
            else -> return null
        }

        return runCatching {
            client.newCall(req).execute().use { resp -> toWebResponse(resp, signed) }
        }.getOrElse { t ->
            AppLogger.w(TAG, "代发失败 ${method.uppercase()} $signed: ${t.message}")
            null
        }
    }

    private fun toWebResponse(resp: Response, url: String): WebResponse {
        val bytes = resp.body?.bytes() ?: ByteArray(0)
        val blockBy = resp.header("x-block-by")
        val code = resp.code

        // 只把「可疑 / 出错」的记录进日志页：正常资源请求不刷屏。
        val noteworthy = code >= 400 || blockBy != null
        if (noteworthy) {
            val msg = "H5 ${resp.request.method} ${resp.request.url.encodedPath} → $code" +
                (blockBy?.let { " [x-block-by: $it]" } ?: "") +
                " sign=${resp.request.url.queryParameter(PARAM_SIGN)?.take(8) ?: "无"}" +
                " did=${resp.request.header("x-shepherd-did") ?: "无"}"
            if (code >= 400) AppLogger.w(TAG, "$msg body=${bytes.decodeToString().take(160)}")
            else AppLogger.d(TAG, msg)
        }

        val contentType = resp.header("Content-Type") ?: "application/octet-stream"
        val stream: InputStream = ByteArrayInputStream(bytes)
        return WebResponse(code, resp.headers.toMultimap(), contentType, stream)
    }

    /**
     * 给 H5 的 URL 补齐公共参数与 `sign`，并把 `{client}` 退化产物 `api` 归正为 `android`。
     *
     * 公共参数与 `CommonQueryInterceptor` **同源**（那边供原生 Retrofit，这边供 H5），
     * 改一处记得改另一处。
     *
     * ★ 2026-09-30：`_productId` 按**端点**区分 ——
     *  `/leo-game-pk/...`（PK）要 `631` + `_appId=6` + `version=3.141.1`；
     *  其余主域请求沿用 `611` + `NetworkConfig.LEO_PROTOCOL_VERSION`。
     *  见 [PK_PRODUCT_ID] 的 KDoc（记忆 #36：611 → 401）。
     */
    private fun withSignAndCommonQuery(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val normalized = normalizeClientSegment(parsed)
        val builder = normalized.newBuilder()
        val isPk = normalized.pathSegments.firstOrNull() == "leo-game-pk"
        val params = if (isPk) PK_COMMON_PARAMS else COMMON_PARAMS
        params.forEach { (k, v) -> if (normalized.queryParameter(k) == null) builder.addQueryParameter(k, v) }
        // sign 的输入是 encodedPath —— **必须在归正之后算**，否则签名与被请求的路径对不上。
        if (normalized.queryParameter(PARAM_SIGN) == null) {
            SignComputer.sign(normalized.encodedPath)?.let { builder.addQueryParameter(PARAM_SIGN, it) }
        }
        return builder.build().toString()
    }

    /**
     * 把 `/leo-xxx/api/...` 这种「H5 退化路径」的客户端段归正为 `android`。
     *
     * 见 [H5_FALLBACK_CLIENT] 的 KDoc：H5 在拿不到原生 `requestConfig` 时，
     * 会把 `{client}`/`{device}` 字面量替成 `api`，而服务端要的是 `android`。
     *
     * ## 判定规则（保守，宁可不改也不改错）
     *
     * 只替换**同时满足**下面两条的路径段：
     *  1. 该段恰好是 `api`；
     *  2. 它的前一段在我们的 [CLIENT_SCOPED_MODULES] 白名单里
     *     （即形如 `/leo-game-pk/api/math/pk/props/home`）。
     *
     * 注意 `_appId` 等 query 参数**不受影响** —— 只动路径段。
     */
    private fun normalizeClientSegment(url: HttpUrl): HttpUrl {
        val segments = url.pathSegments
        var hit = -1
        for (i in segments.indices) {
            if (segments[i] == H5_FALLBACK_CLIENT &&
                i > 0 &&
                segments[i - 1] in CLIENT_SCOPED_MODULES
            ) {
                hit = i
                break
            }
        }
        if (hit < 0) return url
        return url.newBuilder()
            .setPathSegment(hit, REAL_CLIENT)
            .build()
    }

    private val COMMON_PARAMS: List<Pair<String, String>> = listOf(
        "_productId" to "611",
        "platform" to "android${android.os.Build.VERSION.SDK_INT}",
        // ★ 主域协议版本（3.140.1），不是 App 的 versionName —— 见 NetworkConfig。
        "version" to cn.apixiaoyuan.app.core.network.NetworkConfig.LEO_PROTOCOL_VERSION,
        "vendor" to "UC",
        "av" to "5",
        "deviceCategory" to "phone",
        "webviewVersion" to "150",
        "whRatio" to "2.17",
        "isBackground" to "0",
    )

    /**
     * PK 端点的公共参数（★ 2026-09-30 新增）。
     *
     * 与 [COMMON_PARAMS] 的差异只有三点，但每一点都致命：
     *  - `_productId` = **631**（不是练习的 611）—— 否则 SolarAuthFilter 回 401；
     *  - `_appId` = **6** —— 原版 PK 请求恒带；
     *  - `version` = **3.141.1** —— PK 端点自己的口径（主域其余接口用 3.140.1）。
     *
     * 真机抓包（`auto_oral-2026-09-27.log`）与 pk-node 侧结论一致。
     */
    private val PK_COMMON_PARAMS: List<Pair<String, String>> = listOf(
        "_productId" to PK_PRODUCT_ID,
        "_appId" to PK_APP_ID,
        "platform" to "android${android.os.Build.VERSION.SDK_INT}",
        "version" to PK_VERSION,
        "vendor" to "UC",
        "av" to "5",
        "deviceCategory" to "phone",
        "webviewVersion" to "150",
        "whRatio" to "2.17",
        "isBackground" to "0",
    )

    /** H5 未带 UA 时的兜底（与 [HeaderInterceptor] 同形态）。 */
    private val DEFAULT_UA: String =
        "Leo/${cn.apixiaoyuan.app.core.network.NetworkConfig.LEO_PROTOCOL_VERSION} " +
            "(${android.os.Build.BRAND}${android.os.Build.MODEL}; Android ${android.os.Build.VERSION.SDK_INT}; Scale/1.0)"

    private const val POST_BODY_TYPE = "application/json; charset=utf-8"

    /** 代发结果：交给 `WebResourceResponse` 的最小载体（避免这里依赖 android.webkit）。 */
    internal data class WebResponse(
        val statusCode: Int,
        val headers: Map<String, List<String>>,
        val contentType: String,
        val body: InputStream,
    )
}
