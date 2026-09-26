package cn.apixiaoyuan.app.core.auth

import android.os.Build
import android.util.Log
import cn.apixiaoyuan.app.core.network.ServiceLocator
import cn.apixiaoyuan.app.core.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 设备注册（`POST /leo-auth/android/user-devices`）的封装。
 *
 * ## 背景（2026-09-27 逆向闭环）
 *
 * 主域业务端点（`/leo-profile/android/user-infos/batchGet`、
 * `/leo-gateway/android/accounts/switch` 等）需要**两层设备链** `sid` + `ks_*`：
 *  - 缺 `ks_*` → 401 `x-block-by: leo-auth`（认证层挡）
 *  - 有 `ks_*` → 417 `x-block-by: solar-encoder`（认证已过，卡传输/编码层，App 内可过）
 *
 * 本项目登录只拿到 `sid`，`ks_*` 需要先调设备注册接口，由响应的 `Set-Cookie`
 * 下发（`PersistentCookieJar` 自动落盘）。这正是原版 `v0$b.run()` 干的事。
 *
 * ## 字段契约（smali 逐行，`Lcom/fenbi/android/leo/logic/v0$b`）
 *
 * ```smali
 * register(device, deviceInfo)            // @FormUrlEncoded @POST("/leo-auth/android/user-devices")
 *   device     = kv/k.b( Lds/i3.c().b() )  // RSA( "leo-android-" + 指纹UUID )
 *   deviceInfo = JSONObject.toString()
 * ```
 *
 * `deviceInfo` JSON 字段：
 * ```
 * id     = kv/k.b( Lds/i3.c().b() )  // 与 device 同值（RSA 密文）
 * brand  = Build.BRAND
 * device = Build.DEVICE
 * model  = Build.MODEL
 * sdk    = Build.VERSION.SDK_INT
 * host   = Build.HOST
 * rom    = Build.DISPLAY
 * release= Build.VERSION.RELEASE
 * ```
 * （原版还有 `encryptedOaid`，仅在 OAID SDK ready 时带；本项目不接 OAID，省略。）
 *
 * `kv/k.b` 就是 [PhoneEncoder.encode]（同一把 RSA 公钥 + RSA/ECB/PKCS1Padding + NO_WRAP base64）。
 */
object DeviceRegistrar {

    private const val TAG = "DeviceRegistrar"

    /**
     * 确保本机已注册设备（拿到 `ks_*`）。幂等 —— 已有 `ks_deviceid` 时直接返回。
     *
     * @return true 表示「已具备设备链」（本来就有，或本次注册成功）
     */
    suspend fun ensureRegistered(): Boolean {
        if (SessionStore.cookie("ks_deviceid") != null) return true
        return registerDevice()
    }

    /**
     * 主动注册设备（不检查是否已注册）。
     *
     * @return 注册成功（HTTP 2xx）返回 true；否则 false。
     */
    suspend fun registerDevice(): Boolean = withContext(Dispatchers.IO) {
        val devicePlain = "leo-android-" + DeviceFingerprint.fingerprintUuid()
        val deviceEncrypted = try {
            PhoneEncoder.encode(devicePlain)
        } catch (t: Throwable) {
            Log.w(TAG, "device 字段 RSA 加密失败", t)
            return@withContext false
        }

        val deviceInfo = try {
            buildDeviceInfo(deviceEncrypted)
        } catch (t: Throwable) {
            Log.w(TAG, "deviceInfo 组装失败", t)
            return@withContext false
        }

        try {
            // register 是旧版 Call；用 execute 同步发（这里已在 Dispatchers.IO）。
            val resp = ServiceLocator.user.register(
                device = deviceEncrypted,
                deviceInfo = deviceInfo,
            ).execute()
            val ok = resp.isSuccessful
            if (ok) {
                // ks_* 由 Set-Cookie 下发，PersistentCookieJar 已落盘。
                // 这里验证一下关键 cookie 是否真的落盘。
                val ksDeviceId = SessionStore.cookie("ks_deviceid")
                Log.i(TAG, "设备注册成功，ks_deviceid=${ksDeviceId ?: "(未落盘，可能服务端未下发)"}")
            } else {
                Log.w(TAG, "设备注册失败：HTTP ${resp.code()}")
            }
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "设备注册请求异常", t)
            false
        }
    }

    /** 复刻 `v0$b.run()` 里 deviceInfo JSON 的组装。 */
    private fun buildDeviceInfo(encryptedDevice: String): String {
        val json = JSONObject()
        json.put("id", encryptedDevice)
        json.put("brand", Build.BRAND)
        json.put("device", Build.DEVICE)
        json.put("model", Build.MODEL)
        json.put("sdk", Build.VERSION.SDK_INT)
        json.put("host", Build.HOST)
        json.put("rom", Build.DISPLAY)
        json.put("release", Build.VERSION.RELEASE)
        return json.toString()
    }
}
