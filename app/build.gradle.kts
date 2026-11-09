plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kongye.keyboard"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kongye.keyboard"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.5.0"
        // ML Kit 的识别、翻译带原生库；只要 64 位 ARM，安装包小很多
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 本地使用：沿用 debug 签名，生成的 release APK 可直接安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.13.0")
    // 手写：中文文字识别模型打包在 APK 里，手机上离线识别（版本与空叶悬浮相同，离线缓存里有）
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // 翻译：离线翻译模型第一次用时下载
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    // 手写：数字墨水识别（按笔画认字，中文模型第一次用时下载）
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")
}
