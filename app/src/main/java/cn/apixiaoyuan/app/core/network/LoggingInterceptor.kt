package cn.apixiaoyuan.app.core.network

import android.util.Log
import cn.apixiaoyuan.app.core.log.AppLogger
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

/**
 * 请求/响应全量日志。
 *
 * 对齐原版 Packet 抓包的语义：可开关、可看完整 body。区别是原版写文件
 * （`externalCacheDir/packet_capture.log`），这里先输出到 logcat，
 * 落文件与样本回放交给 `feature/samples`（DEV-PLAN 模块 13）。
 *
 * 注意：打印 body 会消费流，所以必须用 [Buffer] 缓存后重建。
 * 只在 [enabled] 为 true 时执行，release 构建默认关闭。
 *
 * ## 2026-09-27：请求摘要始终写入 [AppLogger]
 *
 * 「日志」页需要真实内容。这里对**每个**请求记录一行摘要
 * （方法 / 路径 / 状态码 / 耗时）到 [AppLogger] —— 不依赖 [enabled]
 * （那是「详细 body」的开关），release 也能看到发生了什么。
 * 路径只取 `encodedPath`，不含 query（避免 sign 等噪音）。
 */
class LoggingInterceptor(
    private val enabled: Boolean = false,
    private val tag: String = "LeoNet",
    private val maxBodyChars: Int = 16 * 1024,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startNs = System.nanoTime()

        if (enabled) {
            Log.d(tag, "--> ${request.method} ${request.url}")
            request.headers.forEach { (name, value) -> Log.d(tag, "    $name: $value") }
            request.body?.let { body ->
                val buffer = Buffer()
                runCatching { body.writeTo(buffer) }
                    .onSuccess { Log.d(tag, "    body=${buffer.readUtf8().truncate()}") }
            }
        }

        val response = chain.proceed(request)
        val tookMs = (System.nanoTime() - startNs) / 1_000_000

        // 摘要始终落 AppLogger（日志页内容来源）。
        AppLogger.i(
            tag,
            "${request.method} ${request.url.encodedPath} → ${response.code} (${tookMs}ms)",
        )
        // 失败响应体始终记录 —— 400 / 401 / 417 的真因常藏在 body 里，
        // 日志页据此可定位（PK 刷局 400 即靠这个查）。
        if (response.code >= 400) {
            val errBody = runCatching {
                response.peekBody(maxBodyChars.toLong()).string()
            }.getOrNull()
            AppLogger.w(tag, "  ⚠ ${response.code} ${request.url.encodedPath} body=${errBody?.take(800)}")
        }

        if (enabled) {
            Log.d(tag, "<-- ${response.code} ${request.url} (${tookMs}ms)")
            val text: String = runCatching {
                response.peekBody((maxBodyChars + 1).toLong()).string()
            }.getOrDefault("<unreadable>")
            Log.d(tag, "    body=${text.truncate()}")
        }

        return response
    }

    private fun String.truncate(): String =
        if (length <= maxBodyChars) this else substring(0, maxBodyChars) + "…(+${length - maxBodyChars})"
}
