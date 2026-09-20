plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.gazescroll"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.gazescroll"
        minSdk = 26
        targetSdk = 34
        versionCode = 115
        versionName = "5.75"
    }

    /**
     * v5.65：固定签名。
     *
     * ## 为什么
     *
     * Android 规定「同包名只有签名相同才能覆盖安装」。debug 构建默认用**每台电脑各自
     * 随机生成**的 debug keystore —— 我这台、朋友那台、任何一台新电脑打出来的包
     * **互相装不上**（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），换台机器就得先卸载，
     * 用户的设置会一起没。换成一个固定 keystore 之后，任何电脑、任何一次构建的产物
     * 都能互相覆盖升级，别人拿到 APK 也能直接点安装。
     *
     * ## 放在哪
     *
     * `keystore/gazescroll-release.jks`（项目根目录下）。口令与注意事项见
     * `keystore/README.txt`；**文件丢了就永远无法再覆盖升级同一个包名**。
     * 该文件已被 `.gitignore` 的 `*.jks` 规则排除，不会进仓库 —— 要给别人"能覆盖你的包"
     * 的编译能力，得单独把这个 jks 发给他，放到同样的路径即可。
     *
     * ## 找不到时怎么办
     *
     * **不报错、回退成默认签名**：这样别人 clone 下来、手上没有这个 jks 时，
     * `assembleDebug` 仍然能跑通（只是打出来的包跟我们的不能互相覆盖）。
     */
    signingConfigs {
        create("fixed") {
            val store = rootProject.file("keystore/gazescroll-release.jks")
            if (store.exists()) {
                storeFile = store
                storePassword = "gazescroll2026"
                keyAlias = "gazescroll"
                keyPassword = "gazescroll2026"
            }
        }
    }

    /** 有没有固定 keystore —— 没有就什么都不设，走各自机器的默认签名。 */
    val hasFixedKeystore = rootProject.file("keystore/gazescroll-release.jks").exists()

    buildTypes {
        debug {
            isMinifyEnabled = false
            // v5.65：debug 包也用固定签名。我们整套工具链（install-*.ps1、ui-shot.ps1、
            // capture-loop.ps1）跑的都是 assembleDebug，这样发出去的包天然可以互相覆盖升级。
            if (hasFixedKeystore) signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            if (hasFixedKeystore) signingConfig = signingConfigs.getByName("fixed")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        // BuildConfig.DEBUG gates the adb-triggered swipe self-test.
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // CameraX 鈥?front camera preview + image analysis.
    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // ML Kit face detection, BUNDLED variant (com.google.mlkit:*) so the model
    // ships inside the APK and works offline on a sideloaded test build.
    // The unbundled variant would be
    // com.google.android.gms:play-services-mlkit-face-detection.
    implementation("com.google.mlkit:face-detection:16.1.7")

    // Shizuku 鈥?injects the swipe gesture with ADB (shell) privileges.
    // This replaces AccessibilityService, which MIUI/HyperOS blocks on
    // sideloaded apps (it refuses to grant sensitive permissions to them).
    //
    // 13.1.5 is the minimum usable version here: 13.1.5 fixed a
    // ShizukuProvider crash on Android 14 when the app targets API 34, so
    // 12.2.0 and earlier would crash on this device.
    val shizuku = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizuku")
    implementation("dev.rikka.shizuku:provider:$shizuku")
    // The `aidl` artifact carries moe.shizuku.server.IShizukuService, which is
    // how we reach IShizukuService#newProcess: the convenience wrapper
    // Shizuku#newProcess was removed from the client in 13.1.1, but the server
    // method is still present (verified in the 13.6.0 APK).
    implementation("dev.rikka.shizuku:aidl:$shizuku")
}
