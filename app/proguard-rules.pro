# 1. 保持我们的 JNI 桥接类不被混淆
-keep class wasai.link.mcilm.top.MciLmNative { *; }

# 2. 保持 Native 方法名不被混淆
-keepclasseswithmembernames class * {
    native <methods>;
}

# 3. 保持数据模型类不被混淆（如果 AI 给你写了数据类，比如 UserInfo, RoomInfo，请加上它们）
-keep class wasai.link.mcilm.top.** { *; }

# 4. 如果使用了 Retrofit / OkHttp / Gson 等网络库，加上通用防混淆规则
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }