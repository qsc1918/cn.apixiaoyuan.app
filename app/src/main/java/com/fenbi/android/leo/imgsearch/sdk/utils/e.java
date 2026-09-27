package com.fenbi.android.leo.imgsearch.sdk.utils;

/**
 * 宿主混淆类的**替身**（stub）。
 *
 * ## 为什么需要它
 *
 * `libContentEncoder.so` 只导出 `JNI_OnLoad`，其唯一作用是：
 *
 * ```c
 * jclass c = FindClass("com/fenbi/android/leo/imgsearch/sdk/utils/e");
 * RegisterNatives(c, methods, 1);   // methods[0] = { "c", "([B)[B", so+0x1ecf0 }
 * ```
 *
 * （静态确证：smali/ELF 双重取证 —— `JNI_OnLoad @ 0x1ee2c`，
 *  `.rela.dyn` 里 vaddr `0x45bf8` 三个 `R_AARCH64_RELATIVE` 分别为
 *  名字 `0x1466f` = `"c"`、签名 `0x13959` = `"([B)[B"`、入口 `0x1ecf0`。）
 *
 * **真正的编解码入口是这里的 `c([B)[B]`**，不是 so 里的某个导出函数。
 * 宿主 App 自己也有这个类（就是它的实现），我们工程原本没有，
 * 于是 `FindClass` 失败 → `JNI_OnLoad` 抛出挂起异常 → 库加载失败。
 *
 * 本类只为让 `RegisterNatives` 有落点而存在：**方法名、签名、静态性必须与上面
 * 逐字一致**，否则 ART 会以 `NoSuchMethodError` 拒绝注册。
 *
 * ## 别改这个类
 *
 * - 包名/类名/方法名/签名/`static` 全部是 so 里的硬编码常量，动一个即崩。
 * - R8 可能把它当无用类删掉（只被 native 按名字引用），
 *   故 `app/proguard-rules.pro` 有对应 `-keep`；`ContentBridge` 里
 *   也直接 `import` 使用，双重保险。
 *
 * @see cn.apixiaoyuan.app.core.native.ContentBridge
 */
public final class e {

    private e() {
    }

    /**
     * 内容编解码（对称变换，编码/解码同一入口）。
     *
     * 由 `libContentEncoder.so` 在 `JNI_OnLoad` 中 `RegisterNatives` 绑定到
     * so 内 `0x1ecf0`，**必须在本类被 `System.load` 之前先加载 so**，
     * 否则调用会抛 `UnsatisfiedLinkError`。
     *
     * @param data 明文（编码时）或密文（解码时）字节
     * @return 变换后的字节
     */
    public static native byte[] c(byte[] data);
}