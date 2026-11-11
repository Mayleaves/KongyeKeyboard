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
        versionCode = 29
        versionName = "2.2.2"
        testInstrumentationRunner = "com.kongye.keyboard.OfflineResourcesCheck"
        // ML Kit 的识别、翻译带原生库；只要 64 位 ARM，安装包小很多
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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
    // Fcitx loads its official addons with dlopen from nativeLibraryDir.
    packaging { jniLibs.useLegacyPackaging = true }
    testBuildType = "release"
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-2.0.1.aar", "libs/fcitx5-0.1.3.aar"))
    implementation(files("libs/jna-5.17.0.aar", "libs/rime-1.17.0.aar"))
    implementation(files("libs/onnxruntime-java-1.28.0.aar"))
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.13.0")
    // 2.2.0：显示空叶文档（系统自动填充服务）推送到键盘工具栏的候选
    implementation("androidx.autofill:autofill:1.1.0")
    // Keep existing offline OCR and downloaded digital ink as optional backups.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // 翻译：离线翻译模型第一次用时下载
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")
}
