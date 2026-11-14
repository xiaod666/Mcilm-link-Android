plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // 【新增这一行】声明 Kotlin 序列化插件
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
}