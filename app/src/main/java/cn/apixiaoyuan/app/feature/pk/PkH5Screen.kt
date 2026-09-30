package cn.apixiaoyuan.app.feature.pk

import android.annotation.SuppressLint
import cn.apixiaoyuan.app.BuildConfig
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import cn.apixiaoyuan.app.core.design.icon.AppIcons
import cn.apixiaoyuan.app.core.oldsimian.PkH5Proxy
import cn.apixiaoyuan.app.core.oldsimian.PkJsInjector
import cn.apixiaoyuan.app.core.oldsimian.PkWebViewBridge
import cn.apixiaoyuan.app.core.session.SessionStore

/**
 * 口算 PK 的 H5 容器。
 *
 * 这是模块 8 的核心 —— 口算 PK 全部交互在 H5 里，原生只做三件事：
 *
 *  1. **提供容器**：全屏 WebView，开启 JS 与 DOM Storage
 *  2. **同步登录态**：把原生 [SessionStore] 里存的 cookie 注入
 *     WebView 的 [CookieManager]，让 H5 侧的请求也带上登录态
 *  3. **拦截 scheme**：原版 H5 用 `leo://` scheme 调原生能力，
 *     这里拦截并记录，未实现的先忽略
 *
 * **不做的事**：
 *  - 不注入 JS bridge（原版的 `addJavascriptInterface` 暴露了哪些方法
 *    还没从 smali 确证，贸然注入会引入未知行为；等确证后再补）
 *  - 不解析 H5 内部的答题协议（那是 H5 私有协议，原生不参与）
 *
 * 关键实现点：**Cookie 同步的时机**。WebView 的 CookieManager 与
 * OkHttp 的 PersistentCookieJar 是两套独立存储 —— H5 发起请求时用的
 * 是 CookieManager 里的 cookie，不是 OkHttp 的。必须在 `loadUrl` 之前
 * 把 SessionStore 里的 cookie 逐个写进 CookieManager，否则 H5 侧
 * 表现为未登录。
 *
 * @param viewModel PK 状态机
 * @param onFinish   H5 侧要关闭容器时的回调（如 `leo://close`），
 *                   由调用方决定去哪；当前未接线，留出口
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PkH5Screen(
    viewModel: PkViewModel,
    onFinish: () -> Unit = {},
) {
    
    // WebView 实例在 composition 期间创建；销毁由 AndroidView 的 onRelease
    // 负责（不能放 DisposableEffect.onDispose，详见下方 AndroidView 注释）。
    val context = LocalContext.current
    val webView = remember {
        WebView(context).apply {
            // ---- View 焦点：PK 容器绝不能持有它（真机崩溃的根因）----
            //
            // 崩溃栈（dropbox data_app_crash@1790394023891，2026-09-26 11:40）：
            //   Choreographer.doFrame
            //     → Compose applyChanges (ez0.h / ma.i)
            //     → ViewGroup.removeViewInLayout
            //     → ViewGroup.removeViewInternal (ViewGroup.java:5847)
            //     → View.rootViewRequestFocus (View.java:9103)
            //     → AndroidComposeView.requestFocus
            //     → "Compose Runtime internal error
            //        (pending composition has not been applied)"
            //
            // 机理：`ViewGroup.removeViewInternal` 发现被移除的 View 正是
            // `mFocused` 时，会调 `rootViewRequestFocus()` 向上层重新找焦点
            // 持有者；此刻 Compose 正处在 apply 阶段，焦点落到
            // `AndroidComposeView` 上便触发重入合成 → 抛错崩溃。
            //
            // 而 WebView 天生可聚焦：clickable 的 View 在 touch mode 下触摸即
            // `requestFocus()`。所以「点一下 PK 页面，再返回」必然命中这条路径。
            //
            // 这里设 `isFocusable=false` 从源头断掉；**不动**
            // `descendantFocusability`，保留 WebView 内部 input 弹输入法的能力
            // （HTML 输入焦点走 chromium 内部子 View，不依赖 WebView 自身可聚焦）。
            isFocusable = false
            isFocusableInTouchMode = false

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                // UA 追加小猿口算客户端标识：
                // PK H5 的请求层按 UA 里的版本决定**是否走 requestConfig 桥**：
                //   `isAndroid() && version >= 3.42.0 && url 含 {client}/{device}`
                //     → 交给原生解析 URL（带 sign）
                //     → 否则自己把 {client} 替换成 "api" 发出去（必然 417）
                // 所以这个版本号是「H5 能否正确拿到登录态数据」的开关，别删。
                //
                // ⚠️ 版本必须是**纯净的 `x.y.z`**（2026-09-29 实测）。
                // H5 用严格正则从 UA 取版本：
                //   /\s+(...|YuanSouTiKouSuan|...)\/(\d+\.\d+\.\d+)(\s+|$)/i
                // 尾部要求「数字.数字.数字」后跟空白或结束 —— 带后缀（如
                // `3.141.1-abc1234`）会**整体匹配失败并返回空串**，H5 于是发出
                // `...?version=`（空）→ 417「获取banner数据失败」。
                //
                // root cause 已在 build.gradle.kts 去掉 versionNameSuffix；
                // 这里再 `substringBefore('-')` 兜一道，防止将来又被加回后缀。
                val uaVersion = BuildConfig.VERSION_NAME.substringBefore('-')
                userAgentString = "$userAgentString YuanSouTiKouSuan/$uaVersion"
                cn.apixiaoyuan.app.core.log.AppLogger.i("PkH5", "WebView UA = $userAgentString")
            }

            // 原生桥。H5 按**方法前缀**选对象名，前缀与方法名分开：
            //  无前缀        → window.WebView.<method>
            //  common_xxx   → window.CommonWebView.<method>
            //  leo_xxx      → window.LeoWebView.<method>
            //  LeoSecure_xx → window.LeoSecureWebView.<method>
            // 找不到对象时统一回退到 window.LeoWebView.callNative(b64)。
            //
            // ⚠️ 四个名字必须都注册：`common_getUserInfo`（首屏用户态的唯一来源）
            // 只会在 `CommonWebView` 或 `LeoWebView.callNative` 上落地，
            // 少注册 = H5 永远拿不到登录态（真机症状：首屏「一年级 / 0 胜 / 胜率 0%」）。
            val bridge = PkWebViewBridge(context.applicationContext, this)
            addJavascriptInterface(bridge, "WebView")
            addJavascriptInterface(bridge, "CommonWebView")
            addJavascriptInterface(bridge, "LeoWebView")
            addJavascriptInterface(bridge, "LeoSecureWebView")

            // 同步登录态：把 SessionStore 的 cookie 写进 CookieManager。
            // 必须在**首次** loadUrl 之前 —— WebView 用 CookieManager 发请求，
            // 不是用 OkHttp 的 PersistentCookieJar。
            //
            // ⚠️ 这里**只在创建时同步一次**。此前同步放在 `AndroidView.update` 里
            // 「每次重组都跑」，而 `syncCookiesToWebView` 结尾有
            // `CookieManager.flush()`（**磁盘 I/O**）。PK 的 H5 是 SPA，内部导航会
            // 反复触发 onPageStarted / onPageFinished → `setProgress` 改状态 →
            // 重组 → 又一次 flush…… 形成「重组→flush→重组」的自激循环，
            // 表现就是**PK 页面一闪一闪**。登录态后续刷新的场景由
            // `AndroidView.update` 里带守卫的那次同步兜底（见下方 loadedTarget 块）。
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            syncCookiesToWebView(viewModel.h5Url)

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    viewModel.setProgress(5)
                    viewModel.webError = null
                    // 重置注入标记：同一次加载 onPageFinished 可能回调多次，
                    // 不重置会导致「老挂戏老叟」脚本被叠加注入多轮。
                    view?.let { PkJsInjector.markPageStarted(it) }
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    viewModel.setProgress(100)
                    view?.title?.takeIf { it.isNotBlank() }?.let { viewModel.webTitle = it }
                    // 「老挂戏老叟」PK 侧注入：去排行榜动效 / 结算页自动开下一局。
                    // 本项目 PK 容器是自己的 WebView，直接 evaluateJavascript 即可，
                    // 不需要像 cn.nizou.sxd 那样 hook 宿主的 loadUrl。
                    view?.let { PkJsInjector.injectIfEnabled(it) }
                }
                
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    // 只对主文档报错 —— 子资源失败（图片、埋点）不该阻塞整页。
                    if (request?.isForMainFrame == true) {
                        viewModel.webError = "H5 加载失败：${error?.description ?: "未知错误"}"
                        viewModel.setProgress(0)
                    }
                }
                
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    return handleScheme(url, onFinish)
                }

                /**
                 * 主域业务请求改由原生代发（补公共参数 / sign / 风控头 / Cookie）。
                 *
                 * 原因见 [PkH5Proxy] 的 KDoc：项目那些头挂在 OkHttp 上，
                 * 而 H5 的请求是 WebView 自己发的，根本带不上。
                 *
                 * ## ⚠️ 必须跳过主文档（2026-09-27 踩坑）
                 *
                 * `shouldInterceptRequest` 会拦**所有**请求，**包括 `pk.html` 本身**。
                 * 若把主文档也代发，`WebResourceResponse` 必须完整还原响应头，
                 * 否则会出现「页面显示网页源码」：
                 *  - OkHttp 返回的 body 是**已解压**的，但响应头里还留着
                 *    `Content-Encoding: gzip` → WebView 二次解压失败或当纯文本渲染；
                 *  - `Content-Length` 是压缩前的长度，与实际不符；
                 *  - `Content-Type` 若被改写，WebView 就不再按 HTML 解析。
                 * 所以主文档**一律交回 WebView 自己加载**。
                 *
                 * 任何异常都返回 null → 交回 WebView 原样发，**绝不影响页面加载**。
                 */
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val req = request ?: return null
                    // 主文档不代理：交给 WebView 原生加载（含其自身的压缩/编码处理）。
                    if (req.isForMainFrame) return null
                    val method = req.method ?: return null
                    val url = req.url?.toString() ?: return null
                    // ★ 2026-09-30：把「**像业务接口**的子请求」打出来，用于区分
                    //   「H5 压根没发请求」vs「发了但没进代理」。
                    //
                    // ⚠️ 过滤必须收紧，否则会把几十个 js/css/png 全打出来 → 又刷屏。
                    //   只留：自有域 + 路径以 /leo- 或 /math/ 或 /api/ 开头（业务接口特征）。
                    val host = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")
                    val isOwnHost = host.endsWith("yuanfudao.com") || host.endsWith("yuanfudao.biz")
                    if (isOwnHost && (url.contains("/leo-") || url.contains("/math/") || url.contains("/api/"))) {
                        cn.apixiaoyuan.app.core.log.AppLogger.d(
                            "PkH5Proxy",
                            "子请求 ${req.method} $url 代理=${PkH5Proxy.shouldProxy(method, url)}",
                        )
                    }
                    if (!PkH5Proxy.shouldProxy(method, url)) return null

                    val headers = runCatching { req.requestHeaders.orEmpty() }.getOrDefault(emptyMap())
                    val body = if (method.equals("GET", true)) null else readRequestBody(req)
                    val resp = PkH5Proxy.fetch(method, url, headers, body) ?: return null
                    return runCatching {
                        // 只保留**内容语义相关**且已与实际 body 一致的头。
                        //
                        // 不能整包透传 OkHttp 的响应头：body 已被 OkHttp 解压，
                        // 而 `Content-Encoding` / `Content-Length` 描述的是**传输态**，
                        // 原样交给 WebView 会导致解码错乱（表现为显示源码 / 空白页）。
                        val safeHeaders = resp.headers
                            .filterKeys { it.equals("Content-Type", true) }
                            .mapValues { (_, v) -> v.firstOrNull().orEmpty() }
                        WebResourceResponse(
                            resp.contentType,
                            "UTF-8",
                            resp.statusCode,
                            statusPhrase(resp.statusCode),
                            safeHeaders,
                            resp.body,
                        )
                    }.getOrNull()
                }

                @Deprecated("Deprecated in API 24, but kept for older WebView")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    return url?.let { handleScheme(it, onFinish) } ?: false
                }
            }

            // ★★ 2026-09-30：把 H5 自己的 console 落盘 —— 「PK 没有登录态」的
            //   唯一可靠取证手段。
            //
            // 背景：H5 首屏「一年级 / 0 胜 / 胜率 0%」，我们只看到 getUserInfo 被调、
            // 却看不到任何业务请求（`getHomepage` 从未发出）。要判定是
            //   (a) isLogin=false 走了「登录后开始PK」分支，还是
            //   (b) isLogin=true 但后续某步挂了，
            // 只能看 H5 自己的推理。原版 index-legacy 里正好有几条关键 console：
            //
            //   `console.log(">>>>>>>>>最终结果", err, extData)`  ← getUserInfo 桥的返回值
            //   `at("webviewLogin", r[0])`                        ← 写进 store 的 userInfo
            //
            // 没有它就只能靠猜 —— 这正是本项目反复踩坑的根源。
            //
            // ⚠️ 过滤：只落「像业务诊断」的行（H5 的关键 console + 错误），
            //    不把 H5 的一堆 info 全打进来（否则又刷屏）。
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                    val m = msg ?: return false
                    val text = m.message() ?: return false
                    val keep = text.contains("最终结果") ||
                        text.contains("webviewLogin") ||
                        text.contains("isLogin") ||
                        text.contains("homepage") ||
                        text.contains("userInfo") ||
                        m.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                    if (keep) {
                        cn.apixiaoyuan.app.core.log.AppLogger.d(
                            "PkH5JS",
                            "[${m.messageLevel()}] ${m.sourceId()}:${m.lineNumber()} $text",
                        )
                    }
                    return true
                }
            }
        }
    }
    
    // 原生**主动驱动加载**的目标 URL。
    //
    // 只认这个值，绝不用 `WebView.url` 做判断 —— PK H5 是 SPA（hash 路由），
    // H5 内部导航会把 `WebView.url` 改写成 `pk.html#/xxx`，此时
    // `view.url != h5Url` **恒为真** → `AndroidView.update` 每次重组都 loadUrl
    // → 表现就是「PK 页面一直在刷新」。
    //
    // 这里记住「原生最近一次下发的目标」：只有目标变化，或用户显式重试
    // （reloadToken 递增）才重新加载。H5 内部的导航完全不触发原生 loadUrl，
    // 也就不再打断 SPA 自身的路由。
    var loadedTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            // 注意：这里**不能** destroy WebView —— 销毁已移交给 AndroidView 的
            // onRelease（见下方 AndroidView）。onDispose 与合成同帧同步执行，
            // 此时 WebView 正在被移出视图树，destroy 会与 requestFocus 重入竞争
            // 导致 Compose 运行时崩溃。这里只做非破坏性的清理。
            //
            // clearFocus 是必要的一半：即使 WebView 本身不可聚焦
            // （见构造处的 isFocusable=false），focus 也可能落在它的某个子 View 上，
            // removeViewInternal 同样会走 rootViewRequestFocus 那条崩溃路径。
            runCatching { webView.clearFocus() }
            runCatching { webView.stopLoading() }
        }
    }

    // ---- 系统返回键 ----
    //
    // 语义对齐原版容器（BaseWebApp 的返回 = 先退 H5 历史，退无可退才关容器）：
    //  1. H5 自己有历史（含 SPA 的 pushState/hash 导航，Chromium 会记进
    //     navigation controller）→ `goBack()`，不退容器；
    //  2. 已在 H5 首页 → 回调 [onFinish]，由调用方决定去哪。
    //     [PkScreen] 传的是 `popBackStack<RouteHome>`，即回主页。
    BackHandler(enabled = true) {
        goBackOrFinish(webView, onFinish)
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            
            // ---- 顶部进度条 ----
            //
            // 只在**首次加载**显示（`loadedTarget` 为空 = 还没加载过）。
            //
            // 之前是 `webProgress in 1..99` 就显示，而 PK 的 H5 是 SPA：内部路由
            // （`pk.html#/xxx`）会反复触发 onPageStarted(5) / onPageFinished(100)，
            // 进度条于是**反复出现又消失**，看起来就是页面一闪一闪（用户反馈）。
            // 首次加载完成后不再显示，把 SPA 的内部导航交给 H5 自己。
            if (viewModel.webProgress in 1..99 && loadedTarget == null) {
                LinearProgressIndicator(
                    progress = { viewModel.webProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            
            // ---- 标题栏 ----
            //
            // 返回按钮是原版有、而此前这里漏掉的：原版 PK 走独立的 WebApp
            // Activity（`BaseWebAppActivity`），它的标题栏由容器统一提供返回控件，
            // 点它 = 退 H5 历史 / 关容器回主页。这里此前只有 `Text(webTitle)`，
            // 用户点不到「返回」，只能靠系统返回键 —— 这就是「点 PK 页返回按钮
            // 没法像原版一样回主页」的原因。
            //
            // 语义与 [BackHandler] 完全一致，共用 [goBackOrFinish]，避免两处
            // 行为漂移。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { goBackOrFinish(webView, onFinish) }) {
                    Icon(
                        imageVector = AppIcons.Back,
                        contentDescription = "返回",
                    )
                }
                Text(
                    text = viewModel.webTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (viewModel.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            
            // ---- WebView ----
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    // ⚠️ **不要**在这里无条件调 `syncCookiesToWebView()`。
                    //
                    // 此前那版放在这里，导致 PK 页一闪一闪：该函数结尾有
                    // `CookieManager.flush()`（磁盘 I/O）；SPA 内部导航反复触发
                    // `onPageStarted`/`onPageFinished` → `setProgress` 改状态 → 重组
                    // → 再一次 flush → …… 形成自激循环。
                    //
                    // 现在只在**「原生下发的加载目标」发生变化**（即真的要重新加载）
                    // 时才同步一次，与加载判定共用同一个守卫，天然去重。
                    val target = viewModel.h5Url to viewModel.reloadToken
                    if (viewModel.webError == null && target != loadedTarget) {
                        loadedTarget = target
                        // 顺序要紧：先同步 cookie，再 loadUrl（WebView 用 CookieManager 发请求）。
                        syncCookiesToWebView(viewModel.h5Url)
                        view.loadUrl(viewModel.h5Url)
                    }
                },
                // 销毁必须交给 AndroidView 的 onRelease：它在 View 被移出视图树
                // **之后**才回调。此前放在 DisposableEffect(Unit).onDispose 里会崩：
                // 返回时 Compose 先 removeViewInLayout 摘掉 WebView，摘除过程触发
                // requestFocus → 重入合成；而 onDispose 与合成同帧同步执行 destroy()，
                // 两者竞争抛出 "pending composition has not been applied"
                // （真机崩溃栈底：ViewGroup.removeViewInLayout → ... → requestFocus）。
                onRelease = { view -> releaseWebView(view) },
            )
        }
        
        // ---- 错误覆盖层 ----
        viewModel.webError?.let { err ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Card(modifier = Modifier.padding(24.dp)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = viewModel.h5Url,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { viewModel.reload() }) { Text("重试") }
                    }
                }
            }
        }
    }
}

/**
 * 「返回」的统一实现：先退 H5 历史，退无可退才关容器回主页。
 *
 * 标题栏的返回按钮与系统返回键（[BackHandler]）**共用**这一个实现，
 * 保证两条入口行为一致 —— 此前标题栏根本没有返回控件，只能在系统返回键里
 * 写内联逻辑，改一处漏一处。
 *
 * H5 的 hash 路由导航（SPA 的 `#/xxx`）会被 Chromium 记进 navigation
 * controller，所以 `canGoBack()` 同样覆盖 SPA 内部的前进后退。
 *
 * @param webView   PK 容器
 * @param onFinish  H5 首页再返回时的回调（由 [PkScreen] 决定，当前 = 回主页）
 */
private fun goBackOrFinish(webView: WebView, onFinish: () -> Unit) {
    if (webView.canGoBack()) webView.goBack() else onFinish()
}

/**
 * 安全销毁 WebView（由 AndroidView 的 onRelease 调用，此时 View 已移出视图树）。
 *
 * 顺序是有讲究的，真机崩溃倒逼出的三条：
 *
 *  1. **先从父容器摘除**。onRelease 时通常已被摘除，但若 View 仍挂在某个
 *     ViewGroup 上，直接 destroy 会让父容器在后续布局/焦点遍历中碰到已销毁的
 *     实例。用 runCatching 包住是因为"已不在树里"是常态，抛异常无意义。
 *  2. **清空回调与 JS 开关**。webViewClient 持有 viewModel 与 onFinish 引用，
 *     不清会让整棵 Activity 泄漏到 WebView 的内部线程；同时关掉 JS 阻断
 *     页面里还在跑的定时器继续回调原生。
 *  3. **stopLoading 再 destroy**。destroy 前必须停掉未完成的加载，否则
 *     网络线程回调已销毁的 WebView 会触发 native 层崩溃（chromium）。
 *
 * 整个流程用 runCatching 兜底：销毁阶段的任何异常都不该升级成用户可见的崩溃。
 */
private fun releaseWebView(view: WebView) {
    runCatching {
        // 先清焦点：removeView 时若 WebView 或其子 View 仍持有焦点，
        // ViewGroup.removeViewInternal 会走 rootViewRequestFocus()，
        // 把焦点交给 AndroidComposeView → 重入合成崩溃
        // （真机栈见构造处 isFocusable 的注释）。clearFocus 要在 destroy 之前。
        view.clearFocus()
        view.stopLoading()
        view.webViewClient = WebViewClient()
        view.settings.javaScriptEnabled = false
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.removeAllViews()
        view.destroy()
    }
}
/**
 * 把 [SessionStore] 里的登录态 cookie 同步进 WebView 的 CookieManager。
 *
 * 这是 H5 侧能识别登录态的唯一通道 —— CookieManager 与 OkHttp 的
 * [cn.apixiaoyuan.app.core.session.PersistentCookieJar] 是两套独立存储。
 *
 * 三个必须踩对的点：
 *
 *  1. **URL 必须是合法 host**。真机 cookie 里 `ks_*` 那批挂在
 *     `.yuanfudao.com`（带前导点，hostOnly=false），直接拼
 *     `https://.yuanfudao.com` 不是合法 URL，`setCookie` 会静默失败。
 *     这里去掉前导点，并对每个 cookie 各自的 domain 调一次。
 *
 *  2. **每个 cookie 按其 domain 落盘**，不能全塞到 H5 页面 host 下 ——
 *     cookie 的 domain 归属由服务端 `Set-Cookie` 决定，WebView 发请求时
 *     按 RFC 6265 匹配，放错域等于没放。
 *
 *  3. **expires 用 HTTP 日期格式**，不是 epoch 毫秒。
 *     `CookieManager.setCookie` 解析 `Expires=` 时按 `EEE, dd MMM yyyy HH:mm:ss z`
 *     解析，写数字会被忽略，cookie 退化成会话 cookie，WebView 进程一回收就丢。
 *
 * @param pageUrl H5 入口 URL，仅用于兜底 —— domain 为空的条目落到它上面。
 */
private fun syncCookiesToWebView(pageUrl: String) {
    val cm = CookieManager.getInstance()
    // PK H5 页面的实际 host。cookie 必须落到这个 host 下（或它的父域），
    // WebView 发请求时才按 RFC 6265 匹配得上。
    val pageHost = runCatching { java.net.URI(pageUrl).host }.getOrNull()
        ?: "xyks.yuanfudao.com"

    SessionStore.loadCookies().forEach { entry ->
        if (entry.value.isEmpty()) return@forEach

        // ★ 关键修复（2026-09-26）：cookie domain 必须带前导点，
        // 否则 CookieManager 会把它当 host-only cookie，只匹配裸域
        // `yuanfudao.com`，**不匹配** `xyks.yuanfudao.com` → PK H5 表现为未登录。
        //
        // 原版 WebView 的 cookie host_key 实测全是 `.yuanfudao.com`（带前导点，
        // domain cookie，匹配所有子域）；而 App 的 SessionStore 存的是
        // `yuanfudao.com`（无前导点，host-only）。这里统一补前导点，
        // 与真机地面真值对齐。
        val rawDomain = entry.domain.removePrefix(".")
        // 统一补前导点：domain cookie 才能匹配所有子域。
        val domain = ".$rawDomain"

        val cookieString = buildString {
            append(entry.name).append('=').append(entry.value)
            append("; domain=").append(domain)
            append("; path=").append(entry.path.ifEmpty { "/" })
            if (entry.expiresAt > 0L) {
                append("; expires=").append(httpDate(entry.expiresAt))
            }
            if (entry.secure) append("; Secure")
        }
        // setCookie 的 URL 必须用 PK H5 实际 host（xyks.yuanfudao.com），
        // 这样带前导点的 domain cookie 才会被登记为「匹配该 host 及其子域」。
        cm.setCookie("https://$pageHost", cookieString)
    }
    cm.flush()
    // ★ 诊断（2026-09-28）：H5 的登录态完全取决于「这批 cookie 到底写没写进去」，
    //   而 `setCookie` 失败是**静默**的（domain 不合法/URL 不合法都不报错）。
    //   这里把关键角色的存在性打出来，真机一眼可判定是「没同步」还是「同步了但 H5 不认」。
    val names = SessionStore.loadCookies().map { it.name }.toSet()
    cn.apixiaoyuan.app.core.log.AppLogger.i(
        "PkH5",
        "cookie 同步 → host=$pageHost 共 ${names.size} 条；" +
            "sid=${if ("sid" in names) "有" else "无"} " +
            "sess=${if ("sess" in names) "有" else "无"} " +
            "userid=${SessionStore.cookie("userid") ?: "无"} " +
            "ks_deviceid=${SessionStore.cookie("ks_deviceid") ?: "无"}",
    )
}

/** epoch 毫秒 → HTTP 日期（`EEE, dd MMM yyyy HH:mm:ss z`，GMT）。 */
private fun httpDate(epochMillis: Long): String =
    java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
        .format(java.util.Date(epochMillis))

/**
 * HTTP 状态码 → reason phrase。
 *
 * `WebResourceResponse` 的三参构造（statusCode + reasonPhrase + headers）要求给非空短语，
 * 传空串会导致 WebView 侧解析异常（表现为资源加载失败）。
 */
private fun statusPhrase(code: Int): String = when (code) {
    200 -> "OK"
    201 -> "Created"
    204 -> "No Content"
    301 -> "Moved Permanently"
    302 -> "Found"
    304 -> "Not Modified"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    417 -> "Expectation Failed"
    429 -> "Too Many Requests"
    500 -> "Internal Server Error"
    502 -> "Bad Gateway"
    503 -> "Service Unavailable"
    else -> "HTTP $code"
}

/**
 * 读取 WebView 请求体。
 *
 * `WebResourceRequest`（API 21）**不暴露请求体** —— 这是平台限制，无法绕过
 * （`requestHeaders` 只有头，没有内容）。故统一返回 null，
 * 由 [PkH5Proxy] 以空体发送。
 *
 * 当前无害：PK 首页与 `anti-addiction` 都是 **GET**（无 body）；
 * 若将来 H5 出现「原生代发的 POST/PUT」，需要改用
 * `shouldInterceptRequest` + `WebView.loadUrl(url, extraHeaders)` 之外的方案
 * （例如在 JS 侧改走 `dataEncrypt` 桥）。
 */
@Suppress("UNUSED_PARAMETER")
private fun readRequestBody(request: WebResourceRequest): ByteArray? = null

/**
 * 拦截 `leo://` scheme。
 *
 * 原版 H5 通过这个 scheme 调原生能力，已知的有：
 *  - `leo://openWebView?url=...` —— 打开新 WebView
 *    （MT APK MCP 取证：`LeoPkAppWidgetProvider` 等类内含 `leo://openWebView?url=`）
 *  - `leo://close` —— 关闭当前容器
 *  - `leo://back` —— 退当前容器历史（对齐原生返回语义）
 *
 * 当前处理 `close` / `back`（都回退），其余记录日志后忽略。
 * 完整 scheme 表待从原版 smali 的 WebView 容器实现里挖出。
 *
 * @return true 表示已消费该 URL，WebView 不再加载它
 */
private fun handleScheme(url: String, onFinish: () -> Unit): Boolean {
    if (!url.startsWith("leo://")) return false
    
    val uri = Uri.parse(url)
    when (uri.host) {
        "close", "back", "finish" -> {
            onFinish()
            return true
        }
        "openWebView" -> {
            // 原版会新开一个 WebView 加载 url 参数里的地址。
            // 当前未实现多 WebView 栈，记录后让 WebView 自己处理（会失败，但不崩）。
            return false
        }
        else -> {
            return true
        }
    }
}
