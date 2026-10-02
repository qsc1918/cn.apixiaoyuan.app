package cn.apixiaoyuan.app.core.oldsimian

import android.util.Log
import cn.apixiaoyuan.app.core.log.AppLogger
import cn.apixiaoyuan.app.core.network.HeaderInterceptor
import cn.apixiaoyuan.app.core.network.ShepherdId
import cn.apixiaoyuan.app.core.pk.PkProtocol
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
        if (!(method.equals("GET", true) || method.equals("POST", true) || method.equals("PUT", true))) {
            return false
        }
        val u = url.lowercase()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        // ★★ 2026-09-30：从「只拦主域」改为**通配自有域**（对齐 pk-node 的 isAllowedHost）。
        //
        // ## 为什么（用户报「PK 页面没有登录态」的根因之一）
        //
        // 原先只拦 `xyks.yuanfudao.com`。但 PK H5 会打**多个**业务域：
        //   - `ape-api.yuanfudao.com`   → 账号/登录态（`/accounts/api/current`）
        //   - `xyst.yuanfudao.com`      → banner / 配置中心
        //   - `leo-homework`            → PK 榜
        //   - `leo-activity`            → 道具 / 背包
        //   - `leo-alchemy-account`     → 好友 / 头像挂件
        //   - `leo-star`                → 胜率 / 任务
        //   - `leo-reward`              → 积分兑换
        //
        // 漏一个域 → 该请求**既不带 cookie、也不补 sign / 公共参数** →
        // 服务端 401/417 → H5 判定「未登录」／页面「渲染出来但内容空白」。
        //
        // pk-node 侧（8392b1a 前后）早已改成通配 `*.yuanfudao.com`，
        // 本类之前没同步 —— 这是「web 端好了、老挂不行」的直接原因。
        //
        // 安全性：只放行自有域（yuanfudao.com / .biz 测试域），
        // 且 host 由 WebView 给出，H5 脚本无法借它访问任意第三方。
        val host = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")
        if (host.isEmpty()) return false
        return host.endsWith(".yuanfudao.com") || host == "yuanfudao.com" ||
            host.endsWith(".yuanfudao.biz") || host == "yuanfudao.biz"
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

        // ★ 2026-09-30：「PK 还是不行」的取证入口 —— 证明代理**确实被调用**了。
        //   与 toWebResponse 的全量日志配对：有「代理请求」= 接管生效；
        //   有「H5 … → 状态」= 真发出去了。
        AppLogger.i(
            TAG,
            "代理请求 ${method.uppercase()} ${runCatching { java.net.URI(url).path }.getOrNull() ?: url}" +
                " cookie=${if (cookie.isNullOrBlank()) "无" else cookie.split(";").size.toString() + "条"}",
        )

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

        // ★ 2026-09-30：**全量日志**（原为只记 4xx）。
        //
        // ## 为什么必须全量（用户报「PK 还是不行」时无日志可查）
        //
        // 原先只在 `code >= 400 || blockBy != null` 时打日志。后果：如果 PK 页面的
        // 请求**根本没走到代理**（例如 WebView 直接发了、或 shouldInterceptRequest
        // 没被调用），日志里一片空白 —— 既看不到「有没有代理」，也看不到「代理了什么」。
        //
        // 现在每一笔都记：方法 + 路径 + 状态 + 是否被拦（x-block-by）+
        // **实际使用的身份（userid）**。这样一跑就能判定：
        //   · 有日志 → 代理生效了，看 status 就知道是 401（身份）还是 417（sign）
        //   · 无日志 → 请求压根没进代理，问题在 WebView/shouldInterceptRequest 侧
        val userId = resp.request.header("Cookie")
            ?.split(";")
            ?.firstOrNull { it.trim().startsWith("userid=") }
            ?.trim()
            ?: "无"
        val msg = "H5 ${resp.request.method} ${resp.request.url.encodedPath} → $code" +
            (blockBy?.let { " [x-block-by: $it]" } ?: "") +
            " sign=${resp.request.url.queryParameter(PARAM_SIGN)?.take(8) ?: "无"}" +
            " did=${resp.request.header("x-shepherd-did") ?: "无"}" +
            " $userId"
        if (code >= 400 || blockBy != null) {
            AppLogger.w(TAG, "$msg body=${bytes.decodeToString().take(160)}")
        } else {
            AppLogger.i(TAG, msg)
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
     * ★ 2026-10-02：`_productId` 按**端点**区分 ——
     *  `/leo-game-pk/...`（PK）用 [PK_COMMON_PARAMS]（真机口径：`611` 不带 `_appId`
     *  + `version=3.143.1` + `android35` + `fenbi` + `110/1.78`，**不带 `isBackground`**）；
     *  其余主域请求沿用 `611` + `NetworkConfig.LEO_PROTOCOL_VERSION`。
     *
     *  PK 的那份表在 [PkProtocol.COMMON_QUERY]，与原生刷局链路**共用同一份**
     *  —— 改口径只改一处。
     */
    private fun withSignAndCommonQuery(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        // ★★ 2026-09-30：只有**主域**才补公共参数 / sign / 归正 {client}。
        //
        // ## 为什么必须分域（否则修完登录态反而打坏账号接口）
        //
        // `shouldProxy` 现在放行全部 `*.yuanfudao.com`，但各域的**参数口径不同**：
        //   - `xyks.yuanfudao.com`（主域 / PK）：要 `_productId` + `sign` + `{client}` 归正；
        //   - `ape-api.yuanfudao.com`（账号域）：走的是**另一套**签名与产品号
        //     （`_productId=241` 之类），塞主域参数会直接把它打挂；
        //   - `xyst` / `leo-*` 诸域：各有各的公共参数。
        //
        // 所以非主域**只借 cookie 原样转发**（这正是它们缺的东西 —— 登录态），
        // 不做任何 query 改写。
        if (parsed.host != LEO_HOST) return url

        val normalized = normalizeClientSegment(parsed)
        val builder = normalized.newBuilder()
        val isPk = normalized.pathSegments.firstOrNull() == "leo-game-pk"
        val params = if (isPk) PK_COMMON_PARAMS else COMMON_PARAMS
        params.forEach { (k, v) -> if (normalized.queryParameter(k) == null) builder.addQueryParameter(k, v) }
        // sign 的输入是 encodedPath —— **必须在归正之后算**，否则签名与被请求的路径对不上。
        if (normalized.queryParameter(PARAM_SIGN) == null) {
            // PK 路径要用 PK 版签名资产（version 3.143.1）—— 用练习版会 417。
            SignComputer.signForPath(normalized.encodedPath)?.let { builder.addQueryParameter(PARAM_SIGN, it) }
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
     * PK 端点的公共参数。
     *
     * ## ★★ 2026-10-02：改为**直接取用** [PkProtocol.COMMON_QUERY]，不再各写一份
     *
     * 口径也一并按 pk-node 最新实测修正（原来那份是 `631` + `_appId=6` +
     * `version=3.141.1` + `UC/150/2.17` + `isBackground=0`）：
     *
     * | 项 | 旧 | 新（真机逐字） |
     * |---|---|---|
     * | `_productId` / `_appId` | `631` + `6` | **`611`，不带 `_appId`** |
     * | `version` | `3.141.1` | **`3.143.1`** |
     * | `platform` / `vendor` | `android<本机SDK>` / `UC` | **`android35` / `fenbi`** |
     * | `webviewVersion` / `whRatio` | `150` / `2.17` | **`110` / `1.78`** |
     * | `isBackground` | `0` | **不带** |
     *
     * 「`611` → 401」那条旧结论是在**旧参数组合**（`version=3.141.1` 等）下测出来的；
     * 整套换成真机口径后 `611` 正常放行 —— 401 的真因是参数异构，不是 productId 本身。
     *
     * 不再各写一份的原因：这份表与原生刷局链路（[PkProtocol]）必须永远一致，
     * 两处各写一份的结果就是「原生通了、H5 还是 401」。
     */
    private val PK_COMMON_PARAMS: List<Pair<String, String>> = PkProtocol.COMMON_QUERY

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
