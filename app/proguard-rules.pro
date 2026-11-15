# ==========================================
# 1. 基础与 JNI 保护
# ==========================================
-keep class wasai.link.mcilm.top.link.MciLmNative { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ==========================================
# 2. 数据模型与 Kotlin Serialization
# ==========================================
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
-keep class wasai.link.mcilm.top.network.** { *; }

# ==========================================
# 3. 网络与扫码库保护
# ==========================================
-keepattributes Signature
-keepattributes Exceptions
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }

# ==========================================
# 4. 超级全家桶防报错 (Missing Classes)
# ==========================================
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn javax.annotation.**
-dontwarn kotlinx.**
-dontwarn kotlin.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
-dontwarn org.apache.**
-dontwarn java.lang.management.**
-dontwarn java.rmi.**
-dontwarn java.rmi.registry.**
-dontwarn sun.misc.**
-dontwarn jdk.internal.**
-dontwarn javax.naming.**
-dontwarn javax.security.**
-dontwarn javax.net.**
-dontwarn com.sun.**

# ==========================================
# 5. R8 生成的 ErrorProne 缺失类（刚补充的）
# ==========================================
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# ==========================================
# 6. 其他
# ==========================================
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile