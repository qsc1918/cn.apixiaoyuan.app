package cn.apixiaoyuan.app.core.sign

import android.content.Context
import android.util.Log
import cn.apixiaoyuan.app.core.native.NativeSoExtractor

/**
 * 主域请求签名（`sign`）计算器。
 *
 * ## 背景
 *
 * 主域（`xyks.yuanfudao.com`）业务接口要求 URL 带 32 位 MD5 的 `sign` 参数，
 * 缺它一律 417 `x-block-by: solar-encoder`。该值是
 * `Lcom/fenbi/android/leo/utils/e;->zcvsd1wr2t(path, "wdi4n2t8edr", ts)` 的返回值，
 * 即 `libRequestEncoder.so` 内 JNI_OnLoad 动态注册的 native 方法。
 *
 * ## 为什么直接调 so 而不是用 Kotlin 复刻
 *
 * 该 native 方法（内部 chain 函数，so 偏移 `JNI_OnLoad + 0x4078`）的完整形态是：
 *
 * ```
 * s = path + salt
 * d1 = md5hex(s);  s += d1 + path
 * d2 = md5hex(s);  s += d2 + T
 * d3 = md5hex(s);  s += d3 + salt
 * sign = md5hex(s)
 * ```
 *
 * 其中 `T` 由内部函数（so 偏移 `+0x2dc8`）生成，长度 410 字符，内容是
 * **78 次 `ostream << unsigned` 的十进制直接拼接**，取值是 `time()/60` 经
 * 一串 SIMD 浮点除法/位运算得到的混合量（`M//9`、`M//3`、`M+16`、`2^24` …）。
 *
 * 纯 Kotlin 复刻 `T` 需要逐条还原那 78 个表达式，风险高且宿主一旦升级即失效。
 * 而 `libRequestEncoder.so` 本身**不校验调用方**（`JNI_OnLoad` 里注册失败会被
 * 静默吞掉，chain 函数无任何环境依赖，只读入参 + `time()`），因此直接
 * `dlopen` 它并按偏移调用 chain 是精确、稳定且零维护的做法。
 *
 * ## 为什么不走 `System.loadLibrary` + `external fun`
 *
 * `libRequestEncoder.so` 的方法名在 so 内被加密，运行时经 `RegisterNatives`
 * 注册到 `com/yuanfudao/android/leo/stub/SecureStub` —— 该类在本工程不存在，
 * `System.loadLibrary` 会因 `RegisterNatives` 找不到目标类而失败。
 * 因此本类改为 `dlopen` + 直接按偏移调用（见 `sign_jni.cpp`）。
 *
 * ## 偏移的稳健性
 *
 * `+0x4078` 是以 `JNI_OnLoad` 符号地址为基的相对偏移，与 so 在内存中的加载
 * 基址无关，因此 ASLR 下同样成立。已用真机 harness 逐字节验证：
 * `chain("/leo-goblin-mall/android/goods/click-read/display", "wdi4n2t8edr", 0)`
 * 输出 `e4e851febf67146e2db256a78d6ec357`，与真机抓包一致。
 *
 * ## ⚠️ so 版本与偏移强绑定（重要）
 *
 * 偏移**只对当前内置的那一份 so 成立**。`jniLibs` 里此前存在另一份旧版
 * `libRequestEncoder.so`（919,568 字节，md5 `e6a9e427…`），它的 chain 入口是
 * `JNI_OnLoad + 0x406c`（整体比设备版小 0x10 / 0x1c），算法输出与抓包对不上
 * （实测给出 `cdf8c5a2…`，而真机是 `e4e851fe…`）。
 *
 * ## 内置的两份资产（见 [Variant]）
 *
 * | 变体 | 文件 | 字节数 | md5 | chain 偏移 | 用于 |
 * |---|---|---|---|---|---|
 * | `EXERCISE` | `libRequestEncoder.so`   | 919,600 | `1d9d8e3be5f9f1511d2862b0b1b0addb` | `+0x4078` | 练习 / 主域（3.140.1） |
 * | `PK`       | `libRequestEncoderPk.so` | 919,648 | `9b9b6ab28dd3af4d8349eddc95b69ef0` | `+0x40A8` | PK（3.143.1） |
 *
 * 两份都是**设备实际运行的版本**，已用真机 harness 逐字节验证（练习版样本见上）。
 * 若将来替换 so，必须重新定位 chain 偏移（方法见 `sign_jni.cpp` 头注释），
 * 否则 sign 静默算错、全部 417。
 */
object SignComputer {

    private const val TAG = "SignComputer"

    /** 主域签名盐。原版 `ds/c5` 里硬编码，逐字保留。 */
    const val SALT = "wdi4n2t8edr"

    /** 桥接库名（本工程 `cpp/sign_jni.cpp` 编译产物）。 */
    private const val BRIDGE_LIB = "signbridge"

    /**
     * 签名资产变体。
     *
     * ## ★★ 为什么有两套（2026-10-02 修「刷 PK 一秒钟就结束」）
     *
     * sign 公式是四段 MD5 链，其中唯一随 App 版本变化的是 **T**（由 so 内 T 函数
     * 生成、按分钟变化）。服务端按请求里的 `version` 挑对应版本的 T 去校验，
     * **拿错版本的 so 算出的 sign 一律 417 `x-block-by: solar-encoder`**。
     *
     * | 变体 | so | version | chain 偏移 |
     * |---|---|---|---|
     * | [EXERCISE] | `libRequestEncoder.so`   | 3.140.1（练习/主域） | `+0x4078` |
     * | [PK]       | `libRequestEncoderPk.so` | 3.143.1（PK 链路）   | `+0x40A8` |
     *
     * 此前本类只有练习版资产，而 PK 请求改口径后声明 `version=3.143.1` →
     * sign 对不上 → 出题立刻被拒（界面表现：点开始后 1 秒内「完成 0/N 局」）。
     */
    enum class Variant(
        internal val slot: Int,
        internal val soName: String,
        internal val soSize: Long,
        internal val chainOffset: Int,
        /** 该资产对应的协议版本，仅用于日志。 */
        val protocolVersion: String,
    ) {
        /** 练习 / 主域业务端点（`version=3.140.1`）。 */
        EXERCISE(0, "libRequestEncoder.so", 919_600L, 0x4078, "3.140.1"),

        /** PK 端点（`version=3.143.1`）。 */
        PK(1, "libRequestEncoderPk.so", 919_648L, 0x40A8, "3.143.1"),
        ;

        /** `0x4078` 形态的十六进制串，供日志用。 */
        internal fun chainOffsetHex(): String = "0x" + chainOffset.toString(16)
    }

    @Volatile
    private var ready = false

    /** 已装载成功的变体（用于日志与降级判断）。 */
    private val loadedVariants = java.util.Collections.synchronizedSet(mutableSetOf<Variant>())

    /** 是否已就绪（至少练习版 so 已加载、chain 可调用）。 */
    val isReady: Boolean
        get() = ready

    /** PK 版资产是否可用（不可用时 PK 请求会退化为「不带 sign」）。 */
    val isPkReady: Boolean
        get() = loadedVariants.contains(Variant.PK)

    // ---- native 桥 ----
    private external fun nativeInit(variant: Int, path: String, chainOffset: Int): Boolean
    private external fun nativeReady(variant: Int): Boolean
    private external fun nativeSign(variant: Int, a: String, b: String, c: Int): String?

    /**
     * 初始化。幂等。
     *
     * 两套资产**都尝试装载**：练习版失败会让整个 [ready] 为 false（主域业务全废），
     * PK 版失败只影响 PK（此时 [isPkReady] 为 false，PK 请求会不带 sign 发出 ——
     * 至少能让日志里看到「是 417 还是别的」，而不是静默算出一个错的 sign）。
     *
     * @param context 任意 Context，用于定位 `nativeLibraryDir`。
     * @return true 表示桥接库与**练习版**资产加载成功。
     */
    fun init(context: Context): Boolean {
        if (ready) return true
        val loaded = try {
            System.loadLibrary(BRIDGE_LIB)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "loadLibrary($BRIDGE_LIB) failed: ${t.message}")
            false
        }
        if (!loaded) return false

        val exOk = loadVariant(context, Variant.EXERCISE)
        val pkOk = loadVariant(context, Variant.PK)
        ready = exOk
        Log.i(
            TAG,
            "init ok=$exOk (exercise=${Variant.EXERCISE.chainOffsetHex()}), pk=$pkOk " +
                "(pk=${Variant.PK.chainOffsetHex()})",
        )
        if (!pkOk) {
            Log.w(TAG, "PK 版签名资产不可用 —— PK 请求将不带 sign（会 417），刷局不可用")
        }
        return exOk
    }

    /** 装载单个变体；成功时登记进 [loadedVariants]。 */
    private fun loadVariant(context: Context, variant: Variant): Boolean = runCatching {
        val so = NativeSoExtractor.resolve(context, variant.soName, variant.soSize)
            ?: return@runCatching false
        val ok = nativeInit(variant.slot, so.absolutePath, variant.chainOffset) && nativeReady(variant.slot)
        if (ok) loadedVariants.add(variant)
        ok
    }.getOrElse { t ->
        Log.w(TAG, "loadVariant(${variant.name}) failed: ${t.message}")
        false
    }

    /**
     * 计算主域签名（**练习版资产**）。
     *
     * @param path 请求路径（`url.encodedPath()`，不含 host 与 query）。
     * @param ts   时间偏移秒（原版来自 prefs `time.delta`，默认 0）。
     * @return 32 位小写 hex；未就绪时返回 null。
     */
    fun sign(path: String, ts: Int = 0): String? = sign(Variant.EXERCISE, path, ts)

    /**
     * 按路径**自动选资产**算签名。
     *
     * `/leo-game-pk/...` → [Variant.PK]（PK 链路，`version=3.143.1`），
     * 其余 → [Variant.EXERCISE]（练习/主域，`version=3.140.1`）。
     *
     * 所有「给主域请求补 sign」的地方都应该走这个入口，别再各自写 if。
     */
    fun signForPath(path: String, ts: Int = 0): String? {
        val variant = if (path.startsWith("/leo-game-pk")) Variant.PK else Variant.EXERCISE
        return sign(variant, path, ts)
    }

    /**
     * 计算签名（指定资产）。
     *
     * @return 32 位小写 hex；该变体未就绪时返回 null（**不要**回落到另一套资产 ——
     *         那样只会得到一个错的 sign，服务端照样 417，还把问题藏起来）。
     */
    fun sign(variant: Variant, path: String, ts: Int = 0): String? {
        if (!loadedVariants.contains(variant)) return null
        return try {
            nativeSign(variant.slot, path, SALT, ts)
        } catch (t: Throwable) {
            Log.w(TAG, "sign(${variant.name}) failed: ${t.message}")
            null
        }
    }
}