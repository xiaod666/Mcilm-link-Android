plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "wasai.link.mcilm.top"
    compileSdk = 34

    // 修复 P2-11：显式钉住 NDK 版本。
    // ⚠️ 如果你本地 Android Studio 装的不是这个版本，请替换为实际版本号
    // （SDK Manager → SDK Tools → NDK (Side by side) 可查）。
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "wasai.link.mcilm.top"
        minSdk = 24
        targetSdk = 34
        versionCode = 10
        versionName = "1.4.1-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        // 修复 P0-1 配套：AuthRepository 用到 BuildConfig.DEBUG 控制日志级别。
        // AGP 8.x 默认 buildConfig=false，不显式开启会编译失败。
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // 修复 N3：useLegacyPackaging = true 让 so 安装时解压到磁盘（nativeLibraryDir），
            // dlopen("libmcilm_link.so") 在所有设备上都能找到。
            // false 时依赖 Android 对 APK 内 .so 的 mmap 支持，部分老设备/厂商 ROM 会失败。
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.compose.material:material-icons-extended:1.6.8")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    implementation("com.google.zxing:core:3.5.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}