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
        versionCode = 55
        versionName = "5.5"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
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

    // CameraX — front camera preview + image analysis.
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

    // Shizuku — injects the swipe gesture with ADB (shell) privileges.
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
