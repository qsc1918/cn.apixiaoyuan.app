package cn.apixiaoyuan.app.core.oldsimian

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import cn.apixiaoyuan.app.core.log.AppLogger
import cn.apixiaoyuan.app.core.native.ContentBridge
import cn.apixiaoyuan.app.core.session.SessionStore
import cn.apixiaoyuan.app.core.sign.SignComputer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * PK H5 的原生桥 —— 同一实例注册为 `window.WebView` / `window.CommonWebView` /
 * `window.LeoWebView` / `window.LeoSecureWebView`。
 *
 * ## 协议（2026-09-27 本地复现逐项实测，非推测）
 *
 * 复现方式：Playwright + Chromium 打开线上 `pk.html`，stub 掉桥后抓全部桥调用，
 * 直到页面渲染出**带真实昵称与战绩的登录态首页**（脚本见对话记录）。结论：
 *
 * ### 1) H5 选哪一个对象 = 方法前缀决定
 *
 * ```
 * 无前缀        → window.WebView.<method>(b64)
 * common_xxx   → window.CommonWebView.<method>(b64)     … 不存在则退回 ②
 * leo_xxx      → window.LeoWebView.<method>(b64)        … 不存在则退回 ②
 * LeoSecure_xx → window.LeoSecureWebView.<method>(b64)  … 不存在则退回 ②
 * ```
 *
 * ### 2) 通道①（能力调用）的 payload
 *
 * ```
 * b64( {"arguments":[ {...} ], "callback":"__Oldcallback__"} )
 * ```
 * —— 注意 `arguments` / `callback` 在**顶层**，不在 `params` 里。
 *
 * ### 3) 通道②（通用调用）的 payload
 *
 * ```
 * window.LeoWebView.callNative( b64( {"method":"common_getUserInfo",
 *                                     "params":{"trigger":"getUserInfo_<ts>_<n>"}} ) )
 * ```
 * 前缀方法的兜底通道，**本项目必须实现**：`common_getUserInfo` 只走这里。
 *
 * ### 4) 回调（原生 → H5）
 *
 * ```
 * window['<回调名>']( b64( JSON.stringify([ err, data ]) ) )
 * ```
 *  - `<回调名>` = 通道①的顶层 `callback`，或通道②的 `params.trigger` / `params.jsCallBack`；
 *  - payload 必须是 **base64 字符串**：H5 侧 `pt()` = `new Buffer(t,'base64').toString()`
 *    再 `JSON.parse`；
 *  - 数组首元素非空即视为错误（H5 会 reject）；
 *  - 回调名是 H5 注册在 window 上的函数，**名字带随机后缀，必须原样回**。
 *
 * ### 5) requestConfig（首屏数据的关键）
 *
 * H5 自己发的每个请求，URL 里的 `{client}`/`{device}` 占位符要先问原生：
 * ```
 * se("requestConfig", { path: "/leo-game-pk/{client}/math/pk/home" }, "LeoSecure")
 *   → window.LeoSecureWebView.requestConfig( b64({arguments:[{path}], callback}) )
 * ← [null, { "wrappedUrl": "https://xyks.yuanfudao.com/leo-game-pk/android/..." }]
 * ```
 * H5 拿到 `wrappedUrl` 后**自己 axios 发**（带 cookie）。
 * 所以原生这里必须把**公共参数与 `sign` 一并算好**，否则 H5 侧会 417
 * （实测：只补 `_productId/_appId/platform/version` 时 pk/home 200、
 *  `activity/pk/daily/award` 与 `math/pk/props/home` 均 417）。
 *
 * ## dataEncrypt / dataDecrypt（ds/i4 语义，2026-09-26 钉死）
 *
 *  - `dataEncrypt`：入参 `{base64}`（JSON 明文的 base64）→ Base64 解码 →
 *    **gzip 压缩** → `ContentBridge.encode`（native）→ Base64 编码 →
 *    回调 `[null, {result}]`；
 *  - `dataDecrypt`：入参 `{base64}`（密文的 base64）→ Base64 解码 →
 *    `ContentBridge.encode` → **gunzip** → Base64 编码 → 回调 `[null, {result}]`。
 *
 * 两者分别与项目 `NativeEncodeInstaller` / `NativeDecodeInstaller` 的顺序一致
 * （= 原版 ds/i4.c / ds/i4.a），但这里不能走网络层拦截器（是 JS 桥直调），
 * 所以在桥内独立实现同样的顺序。
 *
 * ## 线程与异常纪律
 *
 * `@JavascriptInterface` 方法跑在 JS 桥线程：**任何异常都会崩掉宿主进程**，
 * 所以每个方法体全部包 `runCatching`，回调统一 post 到主线程执行
 * （`evaluateJavascript` 必须主线程）。
 */
class PkWebViewBridge(
    private val appContext: Context,
    private val webView: WebView,
) {
    private val main = Handler(Looper.getMainLooper())

    // ==================== 无前缀能力 ====================

    /**
     * 用户信息 —— PK H5 首屏用户卡的**主要来源**，也是 H5 判定「是否已登录」的唯一依据。
     *
     * ## 为什么这是「点开始 PK 提示未登录」的关键（2026-09-27 逐行读 H5，待办 10）
     *
     * `assets/useHomeModel-legacy.Bd8rSiW2.js` 的 store 初始化：
     * ```js
     * var j = l(false);   // isLogin
     * var T = l(-1);      // userId
     * var W = function(){ ... v() ... j.value = true; T.value = r.userId ?? -1; E.value = r.gradeId; }
     * ```
     * 即 **`isLogin` 只有在 `getUserInfo` 成功回调时才被置 true**。
     * 而 H5 点「开始 PK」时（`NewHomeCard.A`）：
     * ```js
     * if (!(isLogin.value || unloginPkEnable.value)) { await s({loginTitle:"登录后开始PK"}); ... }
     * ```
     * → 于是出现「点开始 PK 弹登录」。
     *
     * ## 所以这里必须回**真实有效**的 userId
     *
     * 旧实现的取值链只有 `SessionStore.yfdU ?: 0L`：一旦会话缓存没被回填
     * （例如用户直接从首页快捷入口进 PK、主页子账号列表还没拉到），
     * 回的 `userId` 就是 0 → H5 视为未登录。
     *
     * 现在改成三级兜底（`yfdU` 缓存 → `userid` cookie → 0）。
     * `userid` cookie 是服务端下发的**权威身份**，由登录/切换账号时写入，
     * 比本地缓存更可靠；这也是「既然能拿到 cookie，软件也一定可以」的思路。
     */
    @JavascriptInterface
    fun getUserInfo(payload: String?) {
        val info = runCatching {
            JSONObject().apply {
                put("userId", resolveUserId())
                put("userName", SessionStore.currentNickname ?: "我")
                put("nickName", SessionStore.currentNickname ?: "我")
                put("avatarUrl", SessionStore.currentAvatarUrl ?: "")
                put("userPendantUrl", "")
                put("gradeId", SessionStore.grade() ?: 0)
            }
        }.getOrDefault(JSONObject())
        // 诊断：H5 的 isLogin 完全由这里决定，回报内容必须可查。
        AppLogger.d(
            TAG_BRIDGE,
            "getUserInfo → userId=${info.opt("userId")} grade=${info.opt("gradeId")}" +
                " nickname=${if (info.optString("nickName").isBlank()) "空" else "有"}" +
                " avatar=${if (info.optString("avatarUrl").isBlank()) "空" else "有"}",
        )
        respond(payload, ok(info))
    }

    /**
     * 解析当前用户 ID：本地缓存 → `userid` cookie → 0。
     *
     * `userid` cookie 由服务端下发（登录 / 切换子账号时写入），
     * 是比内存缓存更权威、更不容易缺失的一手来源。
     */
    private fun resolveUserId(): Long =
        SessionStore.yfdU
            ?: SessionStore.cookie("userid")?.toLongOrNull()
            ?: 0L

    /**
     * 打开子页 —— 「开始PK」等点击的真正通路。
     *
     * H5 传 `native://openWebView?url=<enc>&hideNavigation=true...` 声明式参数，
     * 这里解出 url 后在**当前 WebView** 加载（单容器策略：返回键可退回列表页，
     * 与原版「新开 WebView」观感一致）。缺这个桥 = 点击 PK 没反应（真机症状）。
     */
    @JavascriptInterface
    fun openWebView(payload: String?) {
        val url = extractOpenUrl(payload)
        if (!url.isNullOrBlank()) {
            main.post { runCatching { webView.loadUrl(url) } }
        }
        respond(payload, ok())
    }

    /** 关容器。单容器下退 H5 历史即可。 */
    @JavascriptInterface
    fun closeWebView(payload: String?) {
        main.post { runCatching { if (webView.canGoBack()) webView.goBack() } }
        respond(payload, ok())
    }

    /** 提示。消息形态两种都兜：纯字符串 / {message: "..."}。 */
    @JavascriptInterface
    fun toast(payload: String?) {
        val msg = extractToastMessage(payload)
        if (!msg.isNullOrBlank()) {
            main.post {
                runCatching { Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show() }
            }
        }
        respond(payload, ok())
    }

    @JavascriptInterface
    fun getDeviceInfo(payload: String?) {
        respond(payload, ok(JSONObject().put("pad", false).put("os", "android")))
    }

    @JavascriptInterface
    fun getImmerseStatusBarHeight(payload: String?) {
        respond(payload, ok(JSONObject().put("height", 0)))
    }

    /** 未登录拉起登录 —— 本项目账号在 App 内登录，这里只提示。 */
    @JavascriptInterface
    fun login(payload: String?) {
        main.post {
            runCatching { Toast.makeText(appContext, "请在 App 内登录", Toast.LENGTH_SHORT).show() }
        }
        respond(payload, ok())
    }

    /** 能力白名单。H5 侧「不实现也不影响主流程」，回空表即可。 */
    @JavascriptInterface
    fun getNativeCommandList(payload: String?) {
        respond(payload, ok(JSONArray()))
    }

    // ==================== LeoSecure 前缀能力 ====================

    /**
     * dataEncrypt：JSON 明文 → gzip → native → base64（= 原版 ds/i4.c）。
     * H5 把回调结果作为 octet-stream body 直接提交。
     */
    @JavascriptInterface
    fun dataEncrypt(payload: String?) {
        val out = runCatching {
            val raw = decodeFlexibleBase64(firstArgObj(payload)?.optString("base64").orEmpty())
            val mid = ContentBridge.encode(gzip(raw)) ?: error("ContentBridge 未就绪")
            b64(mid)
        }.getOrNull()
        respond(payload, if (out != null) ok(JSONObject().put("result", out)) else err("encrypt failed"))
    }

    /**
     * dataDecrypt：密文 base64 → native → gunzip → base64（= 原版 ds/i4.a）。
     * H5 用它解出题接口（match/v2）的加密 arraybuffer 响应。
     */
    @JavascriptInterface
    fun dataDecrypt(payload: String?) {
        val out = runCatching {
            val raw = decodeFlexibleBase64(firstArgObj(payload)?.optString("base64").orEmpty())
            val mid = ContentBridge.encode(raw) ?: error("ContentBridge 未就绪")
            b64(gunzip(mid))
        }.getOrNull()
        respond(payload, if (out != null) ok(JSONObject().put("result", out)) else err("decrypt failed"))
    }

    /**
     * requestConfig —— 给 H5 的请求 URL 做**模板解析 + 补公共参数 + 加签**。
     *
     * 首屏数据的关键：H5 自己不直接请求业务接口，而是拿这里返回的 `wrappedUrl`
     * 再去 axios（所以它拿到的 URL 里必须已经带好 `sign`，否则敏感端点 417）。
     *
     * 实测（本地复现）：若这里只回空对象，PK 首页只有「一年级 / 0 胜 / 胜率 0%」；
     * 正确回 `{wrappedUrl}` 后，`/leo-game-pk/android/math/pk/home` 等返回 200，
     * 页面渲染出昵称与真实战绩。
     */
    @JavascriptInterface
    fun requestConfig(payload: String?) {
        val path = firstArgObj(payload)?.optString("path")
        val wrapped = path?.takeIf { it.isNotBlank() }?.let { resolveWrappedUrl(it) }
        respond(
            payload,
            if (wrapped != null) ok(JSONObject().put("wrappedUrl", wrapped)) else ok(),
        )
    }

    /** 通道② —— 带前缀方法（`common_*` / `leo_*` / `LeoSecure_*`）的兜底入口。 */
    @JavascriptInterface
    fun callNative(payload: String?) {
        val json = runCatching { JSONObject(decodePayloadJson(payload).orEmpty()) }.getOrNull()
        // H5 在兜底通道里传的是**带前缀**的方法名（如 common_getUserInfo）。
        val method = json?.optString("method").orEmpty().substringAfter('_')
        val params = json?.optJSONObject("params") ?: JSONObject()

        // 合成与通道①等价的 payload，复用已有能力实现与[extractCallback]。
        val synth = JSONObject().apply {
            put(
                "params",
                JSONObject().apply {
                    put(
                        "callback",
                        params.optString("jsCallBack").takeIf { it.isNotBlank() }
                            ?: params.optString("trigger"),
                    )
                    put("arguments", JSONArray().put(params))
                },
            )
        }
        val p = b64(synth.toString())

        when (method) {
            "getUserInfo" -> getUserInfo(p)
            "getDeviceInfo" -> getDeviceInfo(p)
            "getImmerseStatusBarHeight" -> getImmerseStatusBarHeight(p)
            "requestConfig" -> requestConfig(p)
            "dataEncrypt" -> dataEncrypt(p)
            "dataDecrypt" -> dataDecrypt(p)
            "openWebView" -> openWebView(p)
            "closeWebView" -> closeWebView(p)
            "toast" -> toast(p)
            // 其余（getOrionConfig / getFeatureConfig / addFrog / setTitle /
            // scrollStateChanged 之类）H5 不依赖返回值，回成功即可，
            // 关键是**必须回调**，否则那几个 Promise 会一直挂着。
            else -> respond(p, ok())
        }
    }

    // ==================== 内部工具 ====================

    /** 成功回调体：`[null, data]` 的 base64。 */
    private fun ok(data: Any = JSONObject()): String =
        b64(JSONArray().put(JSONObject.NULL).put(data).toString())

    /** 失败回调体：`[err]` 的 base64。 */
    private fun err(msg: String): String = b64(JSONArray().put(msg).toString())

    private fun b64(s: String): String = Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decodePayloadJson(payload: String?): String? = runCatching {
        val raw = payload ?: return@runCatching null
        String(Base64.decode(raw, Base64.DEFAULT))
    }.getOrNull()

    /**
     * 兼容标准 / URL-safe base64（H5 的 `Base64.encode` 可能产出 `-_` 字符集，
     * 解码前统一归一化 + 补齐 padding）。
     */
    private fun decodeFlexibleBase64(s: String): ByteArray {
        val normalized = s.replace('-', '+').replace('_', '/').replace("\n", "").replace("\r", "")
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return Base64.decode(padded, Base64.DEFAULT)
    }

    /** 在 window 上回调 H5 注册的回调名。回调名白名单校验，防注入。 */
    private fun respond(payload: String?, resultB64: String) {
        val cb = extractCallback(payload) ?: return
        if (!cb.matches(Regex("[A-Za-z0-9_$]+"))) return
        main.post {
            runCatching {
                webView.evaluateJavascript("window['$cb'] && window['$cb']('$resultB64')", null)
            }
        }
    }

    /**
     * 规范化入参对象。
     *
     * 两条通道 payload 结构不同：
     *  - 通道①（能力调用）：`{ arguments:[...], callback:"..." }` —— 字段在**顶层**；
     *  - 通道②（callNative）：`{ method:"...", params:{...} }` —— 字段在 `params` 里。
     * 统一成一个对象，下游只认这一种。
     */
    private fun paramsOf(payload: String?): JSONObject? = runCatching {
        val json = JSONObject(decodePayloadJson(payload) ?: return@runCatching null)
        json.optJSONObject("params") ?: json
    }.getOrNull()

    /** 入参数组（通道①顶层 `arguments` / 通道② `params.arguments`）。 */
    private fun argsOf(payload: String?): JSONArray? = paramsOf(payload)?.optJSONArray("arguments")

    /** 第一个对象型实参；没有则退回参数对象本身。 */
    private fun firstArgObj(payload: String?): JSONObject? {
        val args = argsOf(payload)
        if (args != null) {
            for (i in 0 until args.length()) args.optJSONObject(i)?.let { return it }
        }
        return paramsOf(payload)
    }

    /**
     * 回调名 —— 原生回 H5 时必须调回 window 上**同名**函数。
     *
     * 依次找：`callback` → `trigger` → `jsCallBack`，两层都看
     * （通道②在 `params` 上，通道①在顶层），再兜 `arguments[i]` 上的串字段。
     * 顺序不能反：通道①顶层同时有 `callback:"__Oldcallback__"`，
     * 而 `arguments[0].trigger` 可能是别的东西。
     */
    private fun extractCallback(payload: String?): String? = runCatching {
        val json = JSONObject(decodePayloadJson(payload) ?: return@runCatching null)
        val layers = buildList {
            json.optJSONObject("params")?.let { add(it) }
            add(json)
        }
        for (layer in layers) {
            for (key in CALLBACK_KEYS) {
                layer.optString(key).takeIf { it.isNotBlank() && it != "null" }
                    ?.let { return@runCatching it }
            }
            val args = layer.optJSONArray("arguments") ?: continue
            for (i in 0 until args.length()) {
                val a = args.optJSONObject(i) ?: continue
                for (key in CALLBACK_KEYS) {
                    a.optString(key).takeIf { it.isNotBlank() && it != "null" }
                        ?.let { return@runCatching it }
                }
            }
        }
        null
    }.getOrNull()

    /**
     * 把 H5 的 URL 模板补成可直接请求的绝对地址（模板占位符 + 公共参数 + sign）。
     *
     * 公共参数与 [cn.apixiaoyuan.app.core.network.CommonQueryInterceptor] **同源**：
     * 那边供原生 Retrofit 请求用，这边供 H5 自己发请求用，两处必须一致
     * （原版也是同一个 `vp/d` 注入器同时服务两条路）。
     * 改一处记得改另一处。
     */
    private fun resolveWrappedUrl(template: String): String {
        val path = template.replace("{client}", CLIENT).replace("{device}", CLIENT)
        val absolute = if (path.startsWith("http")) path else LEO_BASE + path
        val url = absolute.toHttpUrlOrNull() ?: return absolute
        val builder = url.newBuilder()
        commonParams().forEach { (k, v) ->
            if (url.queryParameter(k) == null) builder.addQueryParameter(k, v)
        }
        // sign 输入是 path（不含 query），放最后只为日志里醒目 —— 与拦截器一致。
        if (url.queryParameter(PARAM_SIGN) == null) {
            SignComputer.sign(url.encodedPath)?.let { builder.addQueryParameter(PARAM_SIGN, it) }
        }
        return builder.build().toString()
    }

    /** 公共查询参数（逐字对齐原版真机请求；与 CommonQueryInterceptor 保持同源）。 */
    private fun commonParams(): List<Pair<String, String>> = listOf(
        PARAM_PRODUCT_ID to PRODUCT_ID,
        PARAM_PLATFORM to "android${android.os.Build.VERSION.SDK_INT}",
        PARAM_VERSION to cn.apixiaoyuan.app.BuildConfig.VERSION_NAME,
        PARAM_VENDOR to "UC",
        PARAM_AV to "5",
        PARAM_DEVICE_CATEGORY to "phone",
        PARAM_WEBVIEW_VERSION to "150",
        PARAM_WH_RATIO to "2.17",
        PARAM_IS_BACKGROUND to "0",
    )

    /** 从 openWebView 的声明式参数里解出真实 url。 */
    private fun extractOpenUrl(payload: String?): String? = runCatching {
        val args = argsOf(payload) ?: return@runCatching null
        for (i in 0 until args.length()) {
            val a = args.optJSONObject(i) ?: continue
            val schemas = a.optJSONArray("schemas")
            if (schemas != null) {
                for (j in 0 until schemas.length()) {
                    val s = schemas.optString(j)
                    val m = Regex("url=([^&]+)").find(s) ?: continue
                    return@runCatching URLDecoder.decode(m.groupValues[1], "UTF-8")
                }
            }
            a.optString("url").takeIf { it.isNotBlank() }?.let { return@runCatching it }
        }
        null
    }.getOrNull()

    private fun extractToastMessage(payload: String?): String? = runCatching {
        val args = argsOf(payload) ?: return@runCatching null
        for (i in 0 until args.length()) {
            if (args.optString(i).isNotBlank() && args.optJSONObject(i) == null) {
                return@runCatching args.optString(i)
            }
            val obj = args.optJSONObject(i) ?: continue
            obj.optString("message").takeIf { it.isNotBlank() }?.let { return@runCatching it }
            obj.optString("text").takeIf { it.isNotBlank() }?.let { return@runCatching it }
        }
        null
    }.getOrNull()

    private fun gzip(raw: ByteArray): ByteArray = ByteArrayOutputStream().use { out ->
        GZIPOutputStream(out).use { it.write(raw) }
        out.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPInputStream(data.inputStream()).use { gz ->
            val buf = ByteArray(8192)
            while (true) {
                val n = gz.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    private companion object {
        /** 桥的日志 tag（`getUserInfo` 回报内容要可查）。 */
        const val TAG_BRIDGE = "PkWebViewBridge"

        /** 回调名候选键，顺序即优先级（`callback` 先于 `trigger`）。 */
        val CALLBACK_KEYS = arrayOf("callback", "trigger", "jsCallBack")

        /** `{client}` / `{device}` 占位符的取值。 */
        const val CLIENT = "android"

        /** 业务主域（与 [cn.apixiaoyuan.app.core.network.NetworkConfig] 同值）。 */
        const val LEO_BASE = "https://xyks.yuanfudao.com"

        const val PARAM_SIGN = "sign"
        const val PARAM_PRODUCT_ID = "_productId"
        const val PARAM_PLATFORM = "platform"
        const val PARAM_VERSION = "version"
        const val PARAM_VENDOR = "vendor"
        const val PARAM_AV = "av"
        const val PARAM_DEVICE_CATEGORY = "deviceCategory"
        const val PARAM_WEBVIEW_VERSION = "webviewVersion"
        const val PARAM_WH_RATIO = "whRatio"
        const val PARAM_IS_BACKGROUND = "isBackground"

        /** 小猿口算产品号。真机抓包逐字：`hostProductId("611")`。 */
        const val PRODUCT_ID = "611"
    }
}