package cn.apixiaoyuan.app.core.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 设备链（`ks_*` cookie）值的加密器。
 *
 * ## 为什么需要它
 *
 * 用户明确要求（2026-09-28）：**账号 cookie 里的设备链 `ks_*` 必须加密存库，
 * 不得明文**。原因是设备链属**设备级凭据**（`ks_deviceid` 非账号级）——
 * 拿到它就能在别处伪装成同一台设备，泄漏面比普通会话 cookie 更大。
 *
 * ## 与 pk-node 的关系
 *
 * pk-node 侧已实现同一设计（`src/cookiecrypt.js`，AES-256-GCM，
 * 密文格式 `enc:v1:iv:tag:ct`）。本类**对齐其密文格式**，便于将来
 * 两端互通或对比排查。差异只有密钥来源：
 *
 * | 实现 | 密钥来源 |
 * |---|---|
 * | pk-node | `PK_SECRET` 环境变量 → sha256，或 `data/secret.key`（32B/0600）|
 * | 本项目 | **Android Keystore**（alias 见 [KEY_ALIAS]），硬件级保护、不可导出 |
 *
 * 本项目的选择更稳：Keystore 密钥在设备上生成且**无法被导出**，
 * 即便 APK 数据目录被拷走，密文也无法在别处解开。
 *
 * ## 密文格式
 *
 * ```
 * enc:v1:<iv_b64>:<tag_b64>:<ct_b64>
 * ```
 *
 * - 前缀 `enc:v1:` 用于**识别是否已加密**（[isEncrypted]）；
 * - `iv` 12 字节（GCM 标准长度），`tag` 16 字节；
 * - 三个分片各自 Base64（NO_WRAP）。
 *
 * ## 只加密 value，不加密 name/domain
 *
 * 与 pk-node 同样的取舍：`name` / `domain` / `path` 保持明文 ——
 * 便于按名字筛选（如挑出所有 `ks_*`）、也便于导入导出时人工核对。
 * 真正的秘密在 value 里。
 *
 * ## ⚠️ 密钥丢失 = 已加密 cookie 无法解密
 *
 * Keystore 密钥随 App 卸载 / 清除数据而销毁。届时已加密的 `ks_*`
 * 解不开，表现为「设备链读不出来」—— 处理方式是**重新导入账号**
 * （重新登录会自动补链）。这与 pk-node「密钥丢失需重导账号」一致。
 *
 * ## 为什么不用 EncryptedSharedPreferences
 *
 * 那需要引入 `androidx.security:security-crypto` 依赖。本项目纪律是
 * 「能不加依赖就不加」，而 GCM + Keystore 的手写实现只有本文件几十行、
 * 无外部依赖、行为完全可控。
 */
object DeviceChainCipher {

    private const val TAG = "DeviceChainCipher"

    /** Keystore 里的密钥别名。（与 App 其它密钥无冲突。） */
    private const val KEY_ALIAS = "apixiaoyuan_device_chain_v1"

    /** 密文前缀（含版本号，便于将来换算法时平滑升级）。 */
    const val PREFIX = "enc:v1:"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** GCM 认证标签长度（比特）。 */
    private const val TAG_LENGTH_BITS = 128

    private val SEPARATOR = ":"

    /**
     * 判断一个字符串是否**已是本类产出的密文**。
     *
     * 用于幂等：重复加密同一个值时应直接返回原值，不解第二次。
     */
    fun isEncrypted(value: String?): Boolean =
        value != null && value.startsWith(PREFIX)

    /**
     * 加密一个明文值。
     *
     * @return 密文；若加密失败（Keystore 不可用等）**返回原文**，
     *         让调用方至少不丢数据（宁可不加密，也不要写坏 cookie）。
     */
    fun encrypt(plain: String?): String? {
        if (plain == null) return null
        if (plain.isEmpty()) return plain
        // 幂等：已经是密文就不再套一层（否则会出现 enc:enc:…）
        if (isEncrypted(plain)) return plain
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            // GCM 下密文尾部 16 字节即 tag（doFinal 输出 = ct||tag）
            val tagLen = TAG_LENGTH_BITS / 8
            val bodyLen = ct.size - tagLen
            if (bodyLen < 0) return plain
            val body = ct.copyOfRange(0, bodyLen)
            val tag = ct.copyOfRange(bodyLen, ct.size)
            PREFIX + b64(iv) + SEPARATOR + b64(tag) + SEPARATOR + b64(body)
        } catch (t: Throwable) {
            Log.w(TAG, "encrypt failed, fallback to plaintext: ${t.message}")
            plain
        }
    }

    /**
     * 解密 [encrypt] 产出的密文。
     *
     * @return 明文；**若入参不是密文则原样返回**（兼容历史明文数据 ——
     *         迁移期必然存在旧数据，不应因此报错）。
     */
    fun decrypt(stored: String?): String? {
        if (stored == null) return null
        if (!isEncrypted(stored)) return stored
        return try {
            val parts = stored.substring(PREFIX.length).split(SEPARATOR)
            if (parts.size != 3) return stored
            val iv = unb64(parts[0])
            val tag = unb64(parts[1])
            val body = unb64(parts[2])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, iv),
            )
            String(cipher.doFinal(body + tag), Charsets.UTF_8)
        } catch (t: Throwable) {
            // 解不开（密钥被清 / 数据损坏）：返回原文便于上层观察，
            // 不要抛异常打断 cookie 读取（否则整个登录态都读不出来）。
            Log.w(TAG, "decrypt failed, return raw: ${t.message}")
            stored
        }
    }

    // ---- 内部 ----

    /** 取（或首次生成）Keystore 里的 AES 密钥。 */
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // 不要求用户认证：cookie 读写发生在后台请求路径上，弹出指纹会阻塞网络。
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return gen.generateKey()
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    /**
     * 自检：加密→解密应还原，且密文带前缀、不含明文。
     *
     * 供调试页/单测调用，返回 null 表示通过，否则返回失败原因。
     */
    fun selfTest(): String? {
        return try {
            val sample = "ks_deviceid_350266477"
            val enc = encrypt(sample)
            if (enc == null || enc == sample) return "encrypt 未生效（仍是明文）"
            if (!isEncrypted(enc)) return "密文缺少前缀：$enc"
            if (enc.contains(sample)) return "密文里仍含明文"
            val back = decrypt(enc)
            if (back != sample) return "解密不还原：$back"
            // 幂等性
            if (encrypt(enc) != enc) return "重复加密不是幂等的"
            null
        } catch (t: Throwable) {
            "selfTest 异常：${t.message}"
        }
    }
}