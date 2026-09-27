package cn.apixiaoyuan.app.core.oldsimian

import android.util.Log
import cn.apixiaoyuan.app.core.log.AppLogger
import cn.apixiaoyuan.app.core.network.HeaderInterceptor
import cn.apixiaoyuan.app.core.network.ShepherdId
import cn.apixiaoyuan.app.core.session.SessionStore
import cn.apixiaoyuan.app.core.sign.SignComputer
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
     * 给 H5 的 URL 补齐公共参数与 `sign`。
     *
     * 公共参数与 `CommonQueryInterceptor` **同源**（那边供原生 Retrofit，这边供 H5），
     * 改一处记得改另一处。
     */
    private fun withSignAndCommonQuery(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val builder = parsed.newBuilder()
        COMMON_PARAMS.forEach { (k, v) -> if (parsed.queryParameter(k) == null) builder.addQueryParameter(k, v) }
        if (parsed.queryParameter(PARAM_SIGN) == null) {
            SignComputer.sign(parsed.encodedPath)?.let { builder.addQueryParameter(PARAM_SIGN, it) }
        }
        return builder.build().toString()
    }

    private val COMMON_PARAMS: List<Pair<String, String>> = listOf(
        "_productId" to "611",
        "platform" to "android${android.os.Build.VERSION.SDK_INT}",
        "version" to cn.apixiaoyuan.app.BuildConfig.VERSION_NAME,
        "vendor" to "UC",
        "av" to "5",
        "deviceCategory" to "phone",
        "webviewVersion" to "150",
        "whRatio" to "2.17",
        "isBackground" to "0",
    )

    /** H5 未带 UA 时的兜底（与 [HeaderInterceptor] 同形态）。 */
    private val DEFAULT_UA: String =
        "Leo/${cn.apixiaoyuan.app.BuildConfig.VERSION_NAME} " +
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
