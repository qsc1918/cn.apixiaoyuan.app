package cn.apixiaoyuan.app.core.network

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import cn.apixiaoyuan.app.core.session.PersistentCookieJar
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit 与 OkHttp 的装配中心。
 *
 * 结构：一个 [OkHttpClient]（带全套 Interceptor），两个 [Retrofit]（leo / ytk），
 * 共享同一个 client。Service 由 [ServiceLocator] 从这里取。
 *
 * 域名不硬编码在代码里，而是通过 [BaseUrlRegistry] 注册别名；真实域名在
 * [init] 时从配置（BuildConfig 或运行时设置）注入。这样切测试环境不用改代码。
 *
 * Interceptor 顺序有语义，不可随意调换：
 *  0. [PersistentCookieJar] —— OkHttp 内建，先于所有 Interceptor 处理 Cookie
 *  1. [BaseUrlInterceptor] —— 先定最终 host
 *  2. [HeaderInterceptor]   —— 公共头
 *  3. [AuthInterceptor]     —— 鉴权（要读 [BaseUrl] 注解判断是否注入 YFD_U）
 *  4. [NeedEncodeInterceptor] —— 编码（会改变请求 body，必须在发出之前）
 *  5. [NeedDecodeInterceptor] —— 解码（会改变响应 body，必须在日志之前）
 *  6. [LoggingInterceptor]  —— 日志（打的是最终形态）
 */
object RetrofitFactory {

    private const val TIMEOUT_SECONDS = 30L

    private lateinit var leoRetrofit: Retrofit
    private lateinit var ytkRetrofit: Retrofit

    /**
     * 全应用唯一的 OkHttp 实例（Interceptor 链见 KDoc 顶部）。
     *
     * `@Volatile` + 可空：`init` 之前读它没有意义，这里显式用 null 表示
     * 「还没初始化」，比 `lateinit` 抛 UninitializedPropertyAccessException 更好定位。
     */
    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var initialized = false

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }

    /**
     * @param leoBaseUrl 主域，如 `https://leo.fenbi.com`
     * @param ytkBaseUrl 账号域，如 `https://ytk.fenbi.com`
     * @param sessionProvider 登录态来源，未接入 SessionManager 前传 `{ null }`
     * @param logging 是否打网络日志
     */
    fun init(
        leoBaseUrl: String,
        ytkBaseUrl: String,
        appVersionName: String,
        appVersionCode: Int,
        sessionProvider: () -> AuthInterceptor.AuthSnapshot? = { null },
        logging: Boolean = false,
    ) {
        if (initialized) return

        val leoUrl = leoBaseUrl.toHttpUrlOrNull()
            ?: error("invalid leoBaseUrl: $leoBaseUrl")
        val ytkUrl = ytkBaseUrl.toHttpUrlOrNull()
            ?: error("invalid ytkBaseUrl: $ytkBaseUrl")

        BaseUrlRegistry.register(BASE_LEO, leoUrl)
        BaseUrlRegistry.register(BASE_YTK, ytkUrl)

        val client = OkHttpClient.Builder()
            .cookieJar(PersistentCookieJar)
            .addInterceptor(BaseUrlInterceptor())
            .addInterceptor(CommonQueryInterceptor(appVersionName))
            .addInterceptor(HeaderInterceptor(appVersionName, appVersionCode))
            .addInterceptor(AuthInterceptor(sessionProvider))
            .addInterceptor(NeedEncodeInterceptor())
            .addInterceptor(NeedDecodeInterceptor())
            .addInterceptor(LoggingInterceptor(enabled = logging))
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        this.client = client

        val contentType = "application/json".toMediaType()
        val factory = json.asConverterFactory(contentType)

        leoRetrofit = Retrofit.Builder()
            .baseUrl(leoUrl)
            .client(client)
            .addConverterFactory(factory)
            .build()

        ytkRetrofit = Retrofit.Builder()
            .baseUrl(ytkUrl)
            .client(client)
            .addConverterFactory(factory)
            .build()

        initialized = true
    }

    /** 取主域 Service 实现。 */
    fun <T> leo(service: Class<T>): T = ensureInit().let { leoRetrofit.create(service) }

    /** 取账号域 Service 实现。 */
    fun <T> ytk(service: Class<T>): T = ensureInit().let { ytkRetrofit.create(service) }

    /**
     * 取**应用统一的 OkHttp 实例**（2026-09-27 新增，待办 9）。
     *
     * ## 为什么要开放这个口
     *
     * 「接口控制台」（[cn.apixiaoyuan.app.feature.api.ApiViewModel]）要能像应用自己
     * 那样发请求 —— 带上登录 Cookie、公共 query（含 sign）、主域风控头 —— 而不是
     * 自己 `new` 一个「裸」客户端（那样既没登录态也没有 sign，测出来的结果没意义）。
     *
     * ## 直接复用是安全的（逐条核对过 Interceptor 的判定条件）
     *
     * 控制台是用 `Request.Builder` **手工**构造请求的，没有 Retrofit 的
     * `Invocation` tag。而链上三个「会改写内容」的拦截器都以 tag 为前提：
     *
     *  - [BaseUrlInterceptor]：`request.tag(Invocation::class.java) ?: return` → 放行；
     *  - [NeedEncodeInterceptor] / [NeedDecodeInterceptor]：同样先查 tag 上有没有
     *    对应注解 → 没有 tag 直接 `chain.proceed`。
     *
     * 所以控制台请求**不会被误编码/误解码**，看到的仍是服务端原始响应；
     * 而 [CommonQueryInterceptor]（补 sign + 公共参数）、[HeaderInterceptor]
     * （主域风控头）、[AuthInterceptor]（Cookie / YFD_U）与 CookieJar 都会正常生效。
     * 这正是「方便直接调用应用 okhttp」所要的语义。
     *
     * 注意返回类型不可空：`ensureInit()` 已保证初始化完成。
     */
    fun client(): OkHttpClient = ensureInit().let {
        client ?: error("OkHttpClient 未初始化（init 未执行完）")
    }

    /** 运行时切换环境用；会清空已建实例。 */
    fun reset() {
        BaseUrlRegistry.clear()
        client = null
        initialized = false
    }

    private fun ensureInit() {
        check(initialized) {
            "RetrofitFactory.init() must be called before creating services"
        }
    }
}