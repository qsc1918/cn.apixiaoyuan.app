package cn.apixiaoyuan.app.core.native

import android.content.Context
import android.util.Log
import com.fenbi.android.leo.imgsearch.sdk.utils.e
import java.io.File

/**
 * `libContentEncoder.so` 的桥接（内容编解码，`([B)[B`）。
 *
 * ## 2026-09-27 重写（修复启动崩溃）
 *
 * 之前用 `dlopen` + 手工调 `JNI_OnLoad` + 裸函数指针调 `so+0x1ecf0`，
 * 结果**每次启动都崩**：
 *
 * ```
 * java.lang.RuntimeException: Unable to create application
 *   Caused by: java.lang.ClassNotFoundException:
 *     com.fenbi.android.leo.imgsearch.sdk.utils.e
 *     at ContentBridge.nativeInit(Native Method)
 * ```
 *
 * 因为我们工程缺那个类 → `JNI_OnLoad` 里 `FindClass` 失败 → 异常挂起 →
 * native 函数带异常返回 → ART 直接崩。
 *
 * **正确做法：把那个类补上，用 `System.load` 走标准加载流程。**
 *
 * ## 真实契约（ELF 静态确证）
 *
 * ```
 * so: libContentEncoder.so（298144 字节，只导出 JNI_OnLoad @ 0x1ee2c）
 * JNI_OnLoad 做的事：
 *   FindClass("com/fenbi/android/leo/imgsearch/sdk/utils/e")
 *   RegisterNatives(clazz, methods, 1)
 * methods[0] @ .rela.dyn vaddr 0x45bf8（3 个 R_AARCH64_RELATIVE）：
 *   name   addend 0x1466f → "c"
 *   sig    addend 0x13959 → "([B)[B"
 *   fnPtr  addend 0x1ecf0 → so 内实现
 * ```
 *
 * 即 **编解码入口是 Java 侧的 `e.c(byte[])`**（替身类见
 * [com.fenbi.android.leo.imgsearch.sdk.utils.e]），so 内部函数由 ART 绑定，
 * 我们不再自己按偏移调用。
 *
 * ## 用法
 *
 * ```kotlin
 * ContentBridge.init(context)
 * val out = ContentBridge.encode(rawBytes)   // 编码或解码同一入口（对称）
 * ```
 *
 * 原版里 `c([B)[B]` 既是编码也是解码的同一入口（对称变换），
 * 请求侧与响应侧共用（见 [cn.apixiaoyuan.app.core.network.NeedEncode]）。
 *
 * ## 注意
 *
 * `System.load` 只能加载一次（重复加载同一路径会抛
 * `UnsatisfiedLinkError: dlopen failed: library ... already opened`），
 * 故 [init] 用 `ready` 做幂等，且整段包在 `runCatching` 里 ——
 * **编码器不可用只应退化为「不编码」，绝不能拖垮 App 启动**。
 */
object ContentBridge {

    private const val TAG = "ContentBridge"

    private const val SO_NAME = "libContentEncoder.so"

    /** 设备上该 so 的字节数，用于校验取到的是正确版本。 */
    private const val SO_SIZE = 298_144L

    @Volatile
    private var ready = false

    val isReady: Boolean get() = ready

    /**
     * 初始化。幂等。
     *
     * @param context 任意 Context，用于定位 / 解压 so。
     * @return true 表示 `libContentEncoder.so` 已加载、`e.c` 可调。
     */
    fun init(context: Context): Boolean {
        if (ready) return true
        val so = NativeSoExtractor.resolve(context, SO_NAME, SO_SIZE) ?: run {
            Log.w(TAG, "$SO_NAME unavailable")
            return false
        }
        val ok = runCatching {
            System.load(so.absolutePath)
            true
        }.getOrElse { t ->
            Log.w(TAG, "System.load(${so.absolutePath}) failed: $t")
            false
        }
        ready = ok
        Log.i(TAG, "init ok=$ok (so=${so.absolutePath})")
        return ok
    }

    /**
     * 内容编解码（对称）。
     *
     * @param raw 明文（编码时）或密文（解码时）字节。
     * @return 变换后的字节；未就绪或失败时返回 null。
     */
    fun encode(raw: ByteArray): ByteArray? {
        if (!ready) return null
        return runCatching { e.c(raw) }.getOrElse { t ->
            Log.w(TAG, "encode failed: ${t.message}")
            null
        }
    }
}

/**
 * 从 APK 里取出内置 so（AGP 默认 `extractNativeLibs=false`，
 * `nativeLibraryDir` 是空目录，必须自己解压）。
 *
 * 三个候选依次尝试：
 *  1. `nativeLibraryDir` —— `extractNativeLibs=true` 或部分 ROM 会解压到此；
 *  2. `filesDir/native/` 缓存 —— 上次已解压的副本；
 *  3. 从 `sourceDir` / `splitSourceDirs` 的 `lib/arm64-v8a/` 条目解压并缓存。
 *
 * 每步都用 [expectedSize] 校验，避免拿到被截断或版本不符的文件
 * （so 版本与内部偏移强绑定，拿错版本会静默算错）。
 */
internal object NativeSoExtractor {

    fun resolve(context: Context, soName: String, expectedSize: Long): File? {
        val direct = File(context.applicationInfo.nativeLibraryDir, soName)
        if (direct.exists() && direct.length() == expectedSize) return direct

        val cache = File(context.filesDir, "native/$soName")
        if (cache.exists() && cache.length() == expectedSize) return cache

        val sources = buildList {
            context.applicationInfo.sourceDir?.let { add(File(it)) }
            context.applicationInfo.splitSourceDirs?.forEach { add(File(it)) }
        }
        for (apk in sources) {
            if (!apk.exists()) continue
            val out = runCatching {
                java.util.zip.ZipFile(apk).use { zip ->
                    val entry = zip.getEntry("lib/arm64-v8a/$soName") ?: return@use null
                    cache.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        cache.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (cache.length() == expectedSize) cache else null
                }
            }.getOrNull()
            if (out != null) return out
        }
        return null
    }
}