// sign_jni.cpp —— 调用 libRequestEncoder.so 的 chain 函数生成主域 SIGN
//
// 为什么不走 System.loadLibrary + external fun：
//   libRequestEncoder.so 的方法走 JNI_OnLoad 内 RegisterNatives 动态注册，
//   注册目标类在 so 内是 `com/yuanfudao/android/leo/stub/SecureStub`（本工程不存在），
//   System.loadLibrary 会因 RegisterNatives 找不到类而失败。
//   因此改为 dlopen + 直接按偏移调用 chain 函数（与逆向验证时的 harness 完全同路径）。
//
// 关键偏移（以各自 so 的 JNI_OnLoad 为基）：
//   chain   = JNI_OnLoad + OFF_CHAIN
//   T 生成  = chain 内部自行调用，无需外部触发
//
// ## ★★ 两套签名资产（2026-10-02 新增 PK 版，此前只有练习版）
//
// sign 的公式是 `md5 四段链`，其中唯一随版本变化的是 **T**（由 so 内 T 函数生成、
// 按分钟变化）。服务端按请求里的 `version` 选用对应版本的 T 去校验 sign，
// **用错版本的 so 算出的 sign 一律 417 `x-block-by: solar-encoder`**。
//
// | 变体 | so 文件名 | 字节数 | md5 | version | chain 偏移 |
// |---|---|---|---|---|---|
// | 练习 `exercise` | `libRequestEncoder.so`   | 919,600 | 1d9d8e3b… | 3.140.1 | **+0x4078** |
// | PK  `pk`        | `libRequestEncoderPk.so` | 919,648 | 9b9b6ab2… | 3.143.1 | **+0x40A8** |
//
// PK 那份的来源与定位方法（`lre_pk.so`，pk-node 侧同一份）：
//   两份 so 是同源不同版本的构建。把两边的 .text 做互相关，最佳对齐是
//   **文件偏移相差 48 字节**（82.9% 字节相同）——即 `pk_vaddr ≈ exercise_vaddr + 0x20`。
//   练习版 chain 在 0x66a64（= JNI_OnLoad 0x629ec + 0x4078），
//   对应 PK 版就在 0x66a84，而 PK 版 JNI_OnLoad 在 0x629dc →
//   **偏移 0x66a84 - 0x629dc = 0x40A8**。
//   校验：两处开头 24 条指令逐字节同构（`sub sp,#0x100` / `stp x29,x30,[sp,#192]` …），
//   且第 15 条都是 `bl <T 函数>`（练习 → 0x657b4，PK → 0x657a4），
//   函数体内各有 4 次 `bl <md5fn>`（练习 → 0x64990，PK → 0x64980）。
//
// ## 将来换 so 时重新定位 chain 的方法（三步，全部静态）
//   1) 找 getEncodedP：JNI_OnLoad 内 RegisterNatives 的 methods 数组第三项 fnPtr
//      （so 偏移约 0x61bf4），其函数体内有 bl 到 chain 的调用；
//   2) 找 chain：getEncodedP 尾部（约 +0x62794）那条 `bl` 的目标即 chain 入口，
//      特征为函数开头 `sub sp, sp, #0x100` + `stp x29,x30,[sp,#192]`；
//   3) 校验：chain 入口偏移处应能反汇编到 `bl <T 生成>`，且函数体内有 4 次
//      md5 调用（bl md5fn）与 4 次 digest→hex 调用。再到真机比对抓包 sign。
//   ⚠️ 换 so 时**必须同时改这里与 Kotlin 侧的 SO_SIZE/md5 校验**（见 SignComputer）。
//
// chain 语义（已由反汇编 + 运行时双证）：
//   out = md5hex( A + B + md5hex(A+B) + A + md5hex(...) + T + md5hex(...) + B )
//   其中 A = path，B = salt("wdi4n2t8edr")，T 由 time()/60 派生。

#include <jni.h>
#include <dlfcn.h>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <android/log.h>

#define LOG_TAG "SignBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// 精确寄存器调用垫片（call_shim.S）
//   call_shim(fn=x0, out=x1, a=x2, b=x3, c=w4)
extern "C" void call_shim(void* fn, void* out, void* a, void* b, int c);

// 变体槽位：0 = 练习（version 3.140.1），1 = PK（version 3.143.1）。
// 两套资产各自 dlopen，互不影响（同一个 so 不能 load 两次）。
static const int VARIANT_COUNT = 2;

struct SignSlot {
    void* base = nullptr;        // JNI_OnLoad 地址
    uintptr_t offChain = 0;      // chain 相对 JNI_OnLoad 的偏移
};

static SignSlot g_slots[VARIANT_COUNT];
static void (*g_free)(void*) = nullptr;     // libc++ _ZdlPv（用于回收返回串的堆缓冲）

/** 取槽位；variant 非法时返回 nullptr。 */
static SignSlot* slotOf(int variant) {
    if (variant < 0 || variant >= VARIANT_COUNT) return nullptr;
    return &g_slots[variant];
}

// ---- libc++ std::string 布局辅助 ----
// 短串(<=22)：[0]=(len<<1)，正文在 [1..]
// 长串      ：[0]=cap|1（低位标记），[8]=len，[16]=ptr
static void lcxx_write(char* buf, const char* s, size_t n) {
    if (n <= 22) {
        buf[0] = (char)(n << 1);
        memcpy(buf + 1, s, n);
        buf[1 + n] = 0;
    } else {
        char* p = (char*)malloc(n + 1);
        memcpy(p, s, n + 1);
        *(unsigned long*)buf = (unsigned long)((n + 16) | 1);
        *(unsigned long*)(buf + 8) = (unsigned long)n;
        *(char**)(buf + 16) = p;
    }
}
static const char* lcxx_ptr(const char* buf) {
    return ((unsigned char)buf[0] & 1) ? *(const char**)(buf + 16) : buf + 1;
}
// 回收 libc++ 长串堆缓冲（短串无堆分配）
static void lcxx_release(char* buf) {
    if (((unsigned char)buf[0] & 1) && g_free) {
        g_free(*(void**)(buf + 16));
    }
    memset(buf, 0, 32);
}

// 线程本地缓冲：避免每次调用都 malloc 容器（链函数只读 A/B，返回串需回收）
static thread_local char t_a[64];
static thread_local char t_b[64];
static thread_local char t_out[64];

/**
 * 装载某个变体的签名资产。
 *
 * @param variant     0 = 练习（version 3.140.1），1 = PK（version 3.143.1）
 * @param jPath       so 的绝对路径（可 dlopen）
 * @param offChain    该 so 里 chain 函数相对 JNI_OnLoad 的偏移
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_cn_apixiaoyuan_app_core_sign_SignComputer_nativeInit(
        JNIEnv* env, jclass, jint variant, jstring jPath, jint offChain) {
    SignSlot* slot = slotOf(variant);
    if (slot == nullptr) {
        LOGI("nativeInit: bad variant %d", (int)variant);
        return JNI_FALSE;
    }
    if (slot->base != nullptr) return JNI_TRUE;
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    void* h = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (h == nullptr) {
        LOGI("dlopen fail (variant=%d): %s", (int)variant, dlerror());
        env->ReleaseStringUTFChars(jPath, path);
        return JNI_FALSE;
    }
    void* jni = dlsym(h, "JNI_OnLoad");
    if (jni == nullptr) {
        LOGI("dlsym JNI_OnLoad fail: %s", dlerror());
        env->ReleaseStringUTFChars(jPath, path);
        return JNI_FALSE;
    }
    // _ZdlPv 只取一次（两份 so 的 libc++ 符号同源）
    if (g_free == nullptr) g_free = (void (*)(void*))dlsym(h, "_ZdlPv");
    slot->base = jni;
    slot->offChain = (uintptr_t)(uint32_t)offChain;
    LOGI("loaded variant=%d: JNI_OnLoad=%p chain=%p (off=0x%x) free=%p",
         (int)variant, jni, (void*)((uintptr_t)jni + slot->offChain),
         (unsigned)slot->offChain, (void*)g_free);
    env->ReleaseStringUTFChars(jPath, path);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_apixiaoyuan_app_core_sign_SignComputer_nativeReady(JNIEnv*, jclass, jint variant) {
    SignSlot* slot = slotOf(variant);
    return (slot != nullptr && slot->base != nullptr) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_cn_apixiaoyuan_app_core_sign_SignComputer_nativeSign(
        JNIEnv* env, jclass, jint variant, jstring jA, jstring jB, jint c) {
    SignSlot* slot = slotOf(variant);
    if (slot == nullptr || slot->base == nullptr) return nullptr;
    const char* a = env->GetStringUTFChars(jA, nullptr);
    const char* b = env->GetStringUTFChars(jB, nullptr);
    lcxx_write(t_a, a, strlen(a));
    lcxx_write(t_b, b, strlen(b));
    env->ReleaseStringUTFChars(jA, a);
    env->ReleaseStringUTFChars(jB, b);

    lcxx_release(t_out);
    call_shim((void*)((uintptr_t)slot->base + slot->offChain), t_out, t_a, t_b, (int)c);

    const char* sig = lcxx_ptr(t_out);
    jstring ret = env->NewStringUTF(sig);
    // 回收 A/B 的堆缓冲（chain 只读它们）；t_out 留到下次调用前回收
    lcxx_release(t_a);
    lcxx_release(t_b);
    return ret;
}