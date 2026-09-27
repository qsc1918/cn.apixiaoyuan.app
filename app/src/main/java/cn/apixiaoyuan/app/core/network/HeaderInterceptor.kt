package cn.apixiaoyuan.app.core.network

import android.os.Build
import okhttp3.Interceptor
import okhttp3.Response
import kotlin.random.Random

/**
 * 统一公共请求头。
 *
 * ## 2026-09-27：按真机抓包对齐（417 攻坚）
 *
 * 用「老挂戏老叟」模块的抓包（宿主 App 对 `/leo-star/android/exercise/homepage` 的
 * 一次 **200** 请求）拿到宿主的完整请求头，与我们原来的实现差异巨大：
 *
 * ```
 * leo-client-trace-id: y5f9ika0z60yl2i1g3um
 * default-namespace-sw8: MQ==-<trace>-MA==-0-X19PX1JfVF9f-UF9J-UF9F-SV9Q
 * User-Agent: Leo/3.140.1 (Redmi25053RT47C; Android 17; Scale/3.25)   ← 我们原来完全不对
 * X-XYKS-REQ-TIMESTAMP: 1790487400653                                  ← 我们缺
 * X-XYKS-REQ-NETWORK-ENV: mobile                                       ← 我们缺
 * x-shepherd-did: DUtA-DmaWBaa-xgaLMMFCl5fjJG__ajuzNf3                 ← 我们缺
 * x-shepherd-sessionid: 0                                              ← 我们缺
 * Cookie: ...
 * ```
 *
 * UA 格式为 `Leo/<版本名> (<Build.BRAND><Build.MODEL>; Android <sdkInt>; Scale/<density>)`。
 *
 * 只对**主域**（`xyks.yuanfudao.com`）注入这一组风控头：账号域（ape-api）实测不需要，
 * 加了可能干扰（与 [CommonQueryInterceptor] 同一纪律）。
 */
class HeaderInterceptor(
    private val appVersionName: String,
    private val appVersionCode: Int,
    private val channel: String = "official",
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()

        // UA 对齐宿主真机形态：Leo/<ver> (<BRAND><MODEL>; Android <sdk>; Scale/<density>)
        builder.header("User-Agent", leoUserAgent())
        builder.header("X-App-Version", appVersionName)
        builder.header("X-App-Version-Code", appVersionCode.toString())
        builder.header("X-Channel", channel)
        builder.header("Accept", "application/json")

        // 只给主域补风控头（对齐真机抓包）。
        if (request.url.host == LEO_HOST) {
            val traceId = randomTraceId()
            builder.header("X-XYKS-REQ-TIMESTAMP", System.currentTimeMillis().toString())
            builder.header("X-XYKS-REQ-NETWORK-ENV", "mobile")
            builder.header("x-shepherd-did", ShepherdId.did())
            builder.header("x-shepherd-sessionid", "0")
            builder.header("leo-client-trace-id", traceId)
            builder.header("default-namespace-sw8", sw8Header(traceId))
        }

        return chain.proceed(builder.build())
    }

    /** `Leo/<版本名> (<BRAND><MODEL>; Android <sdkInt>; Scale/<density>)`，逐字对齐真机。 */
    private fun leoUserAgent(): String {
        val density = runCatching {
            android.content.res.Resources.getSystem().displayMetrics.density
        }.getOrDefault(1.0f)
        val scale = if (density % 1.0f == 0f) density.toInt().toString() else density.toString()
        return "Leo/$appVersionName (${Build.BRAND}${Build.MODEL}; Android ${Build.VERSION.SDK_INT}; Scale/$scale)"
    }

    /** 20 位小写十六进制，形态同真机 `leo-client-trace-id`。 */
    private fun randomTraceId(): String {
        val hex = "0123456789abcdef"
        return buildString { repeat(20) { append(hex[Random.nextInt(16)]) } }
    }

    /**
     * `default-namespace-sw8`：真机形态为
     * `base64("1")-base64(traceId)-base64("0")-0-<固定尾>`。
     * 尾部那些 base64 段是固定常量（真机逐字如此），此处照抄。
     */
    private fun sw8Header(traceId: String): String {
        val b64 = { s: String -> android.util.Base64.encodeToString(s.toByteArray(), android.util.Base64.NO_WRAP) }
        return b64("1") + "-" + b64(traceId) + "-" + b64("0") + "-0-X19PX1JfVF9f-UF9J-UF9F-SV9Q"
    }

    private companion object {
        const val LEO_HOST = "xyks.yuanfudao.com"
    }
}

/**
 * 风控设备标识 `x-shepherd-did`。
 *
 * 真机上是宿主 App 的 `com.yuanfudao.android.leo.shepherd` 体系从服务端同步、
 * 持久化在 `files/mmkv/leo_shepherd_id`（key `didKey@v3.68.0@String`）的值。
 * 本工程**不复刻那套同步链路**，直接沿用同机宿主抓包得到的值 —— 与「导入登录态
 * cookie」同一思路：同一台设备复用同一份设备级凭据。
 *
 * 未来若接入自己的 shepherd 同步，用 [set] 覆盖即可。
 */
object ShepherdId {

    /** 真机抓包值（同设备复用）。 */
    private const val DEFAULT_DID = "DUtA-DmaWBaa-xgaLMMFCl5fjJG__ajuzNf3"

    @Volatile
    private var override: String? = null

    fun did(): String = override ?: DEFAULT_DID

    /** 供未来接入自有同步时覆盖。 */
    fun set(value: String?) {
        override = value?.takeIf { it.isNotBlank() }
    }
}