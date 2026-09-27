# 保留 serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class cn.apixiaoyuan.app.**$$serializer { *; }
-keepclassmembers class cn.apixiaoyuan.app.** {
    *** Companion;
}
-keepclasseswithmembers class cn.apixiaoyuan.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions
-keepclasseswithmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# ===== native 替身类（勿删/勿混淆）=====
# libContentEncoder.so 的 JNI_OnLoad 会 FindClass 这个宿主混淆类并在其上
# RegisterNatives("c", "([B)[B")。类名/方法名/签名是 so 里的硬编码常量：
# 被 R8 重命名或裁剪后，System.load 会因 ClassNotFoundException 直接崩启动。
-keep class com.fenbi.android.leo.imgsearch.sdk.utils.e { *; }
-dontwarn androidx.room.paging.**
