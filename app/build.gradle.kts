import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ============================================================
// 应用内更新（GitHub Releases / Gitee Releases）配置读取
// 默认值写在下方 buildConfigField 里（团队共享）；
// 本地可在 local.properties 覆盖（不进版本库）
// ============================================================
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun propOr(key: String, default: String): String =
    localProps.getProperty(key) ?: default

android {
    namespace = "wasai.link.mcilm.top"
    compileSdk = 34

    // 显式钉住 NDK 版本
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "wasai.link.mcilm.top"
        minSdk = 24
        targetSdk = 34
        versionCode = 19
        versionName = "1.6.5-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        // ============================================================
        // 应用内更新（GitHub Releases）—— 编译期注入 BuildConfig
        // 本地覆盖：在 local.properties 里加同名 key
        // ============================================================
        buildConfigField(
            "String", "UPDATE_GITHUB_OWNER",
            "\"${propOr("update.github.owner", "xiaod666")}\""
        )
        buildConfigField(
            "String", "UPDATE_GITHUB_REPO",
            "\"${propOr("update.github.repo", "Mcilm-link-Android")}\""
        )
        buildConfigField(
            "String", "UPDATE_GITHUB_API_BASE",
            "\"${propOr("update.github.api.base", "https://api.github.com/")}\""
        )
        buildConfigField(
            "String", "UPDATE_ASSET_FILTER",
            "\"${propOr("update.asset.filter", "arm64")}\""
        )
        buildConfigField(
            "String", "UPDATE_GITHUB_TOKEN",
            "\"${propOr("update.github.token", "")}\""
        )
        buildConfigField(
            "long", "UPDATE_CHECK_INTERVAL_MS",
            "${propOr("update.check.interval.ms", "21600000")}L"
        )
        buildConfigField(
            "boolean", "UPDATE_ALLOW_PRERELEASE",
            "${propOr("update.allow.prerelease", "false")}"
        )

        // ============================================================
        // 双源配置：auto / github / gitee
        //   auto   → Gitee 配置非空则 Gitee 优先，失败降级 GitHub；Gitee 空则只 GitHub
        //   github → 强制只走 GitHub
        //   gitee  → 强制只走 Gitee（Gitee 配置为空时视为"未配置源"会报错）
        // ============================================================
        buildConfigField(
            "String", "UPDATE_SOURCE",
            "\"${propOr("update.source", "auto")}\""
        )
        buildConfigField(
            "String", "UPDATE_GITEE_OWNER",
            "\"${propOr("update.gitee.owner", "")}\""
        )
        buildConfigField(
            "String", "UPDATE_GITEE_REPO",
            "\"${propOr("update.gitee.repo", "")}\""
        )
        buildConfigField(
            "String", "UPDATE_GITEE_API_BASE",
            "\"${propOr("update.gitee.api.base", "https://gitee.com/api/v5/")}\""
        )
        buildConfigField(
            "String", "UPDATE_GITEE_TOKEN",
            "\"${propOr("update.gitee.token", "")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

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
        // AuthRepository 用到 BuildConfig.DEBUG 控制日志级别，必须开启
        // 同时更新体系依赖 BuildConfig.UPDATE_*
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
            // 让 so 安装时解压到磁盘，dlopen("libmcilm_link.so") 在所有设备上都能找到。
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