# ==========================================
# 1. JNI 桥接与 Native 方法保护
# ==========================================
# 保持 JNI 桥接类（注意包名必须带 .link）
-keep class wasai.link.mcilm.top.link.MciLmNative { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# ==========================================
# 2. 数据模型与 Kotlin Serialization 保护
# ==========================================
# 保留所有 @Serializable 注解的类和生成的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class wasai.link.mcilm.top.**$$serializer { *; }
-keepclassmembers class wasai.link.mcilm.top.** {
    *** Companion;
}
-keepclasseswithmembers class wasai.link.mcilm.top.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 防止 data class 的 component 方法和 copy 方法被混淆
-keep class wasai.link.mcilm.top.network.** { *; }

# ==========================================
# 3. 网络库 (Retrofit / OkHttp) 保护
# ==========================================
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn javax.annotation.**
-keepattributes Signature
-keepattributes Exceptions
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# ==========================================
# 4. 扫码库 (ZXing) 保护
# ==========================================
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }

# ==========================================
# 5. 基础 Kotlin 与 协程保护
# ==========================================
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ==========================================
# 6. 其他
# ==========================================
# 保留行号，方便崩溃时看堆栈
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile