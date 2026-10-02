package cn.apixiaoyuan.app.core.pk

import cn.apixiaoyuan.app.core.session.PersistentCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * PK 链路的**裸 OkHttp 出口**（不用 Retrofit）。
 *
 * ## 为什么绕开 Retrofit（2026-10-02 移植 pk-node 时的关键决定）
 *
 * 应用统一的 OkHttp（[cn.apixiaoyuan.app.core.network.RetrofitFactory]）挂了三个
 * 会改写请求的拦截器，它们按**主域**纪律工作，而 PK 恰好处处相反：
 *
 * | 拦截器 | 它会做什么 | PK 为什么不能要 |
 * |---|---|---|
 * | [cn.apixiaoyuan.app.core.network.CommonQueryInterceptor] | 补 `_productId=611` / `platform=android<本机SDK>` / `vendor=UC` / `webviewVersion=150` / `whRatio=2.17` / `isBackground=0` / `sign` | PK 要 `android35` / `fenbi` / `110` / `1.78`，且**不能带 `isBackground`** |
 * | [cn.apixiaoyuan.app.core.network.HeaderInterceptor] | 覆盖 UA 为 `Leo/3.140.1 (...)`，加 `leo-client-trace-id` / `sw8` / `x-shepherd-did` | PK 出题必须是 H5 WebView UA + `X-Requested-With` |
 * | [cn.apixiaoyuan.app.core.network.AuthInterceptor] | 注入 `YFD_U` 查询参数 | 真机 PK 请求没有它 |
 *
 * 与其在两个全局拦截器里塞「如果是 PK 就跳过」的分支（那是把两套协议搅在一起，
 * 迟早互相污染），不如给 PK 一条**干净的专用 client**：只保留 CookieJar
 * （登录态必须共享），其余全部在 [PkProtocol] 里显式拼装。
 *
 * 这也与 pk-node 的形态一致 —— 那边 PK 同样是「自己拼 URL + 自己拼头」，
 * 不共用主域那套 `buildUrl`。
 */
object PkRawApi {

    private const val TIMEOUT_SECONDS = 30L

    /**
     * PK 专用 client。
     *
     * ⚠️ 只挂 [PersistentCookieJar]：**不挂** [cn.apixiaoyuan.app.core.network.RetrofitFactory]
     * 里的任何业务拦截器（理由见类 KDoc）。
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(PersistentCookieJar)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 出题（v2，加密响应）。
     *
     * 请求 body 必须是**空**（传 `{}` 会被服务端判 400，pk-node 实测）。
     * 响应密文的解包由调用方用 [PkProtocol.decodeEncrypted] 完成 ——
     * 解不开时还能拿到原始字节写日志。
     */
    fun match(mode: PkMode, pointId: Int): PkRawResponse {
        val request = Request.Builder()
            .url(PkProtocol.matchUrl(mode, pointId))
            .post(ByteArray(0).toRequestBody(null, 0, 0))
            .apply { PkProtocol.pkH5Headers().forEach { (k, v) -> header(k, v) } }
            .build()
        return execute(request)
    }

    /**
     * 提交一局。
     *
     * @param cipher 已过 [PkProtocol.encodeSubmitBody] 的密文（octet-stream）
     */
    fun submit(mode: PkMode, cipher: ByteArray): PkRawResponse {
        val ep = PkProtocol.endpoint(mode)
        val request = Request.Builder()
            .url(PkProtocol.submitUrl(mode))
            .method(ep.submitMethod, cipher.toRequestBody(null, 0, cipher.size))
            .apply { PkProtocol.submitHeaders().forEach { (k, v) -> header(k, v) } }
            .build()
        return execute(request)
    }

    /** 结算明细核对（明文 JSON）。 */
    fun historyDetail(pkIdStr: String): PkRawResponse {
        val request = Request.Builder()
            .url(PkProtocol.historyDetailUrl(pkIdStr))
            .get()
            .apply { PkProtocol.historyHeaders(pkIdStr).forEach { (k, v) -> header(k, v) } }
            .build()
        return execute(request)
    }

    /** PK 首页（对局类型 + 分数，明文 JSON）。 */
    fun home(grade: Int): PkRawResponse {
        val request = Request.Builder()
            .url(PkProtocol.homeUrl(grade))
            .get()
            .apply { PkProtocol.homeHeaders().forEach { (k, v) -> header(k, v) } }
            .build()
        return execute(request)
    }

    private fun execute(request: Request): PkRawResponse {
        client.newCall(request).execute().use { response ->
            val bytes = response.body.bytes()
            return PkRawResponse(
                status = response.code,
                body = bytes,
            )
        }
    }
}

/**
 * 裸响应。
 *
 * @param status HTTP 状态码
 * @param body   原始响应字节（`match/v2` 时是**密文**，需 [PkProtocol.decodeEncrypted]）
 */
class PkRawResponse(
    val status: Int,
    val body: ByteArray,
) {
    /** 按 UTF-8 解释的响应文本（判频控措辞只看明文接口，够用）。 */
    val text: String get() = body.toString(Charsets.UTF_8)
}
