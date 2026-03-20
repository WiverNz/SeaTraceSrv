plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "io.seatrace.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.seatrace.android"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // Override for a real device or production: set to your server URL.
        // 10.0.2.2 is the Android emulator's alias for the host machine.
        buildConfigField("String", "WS_BASE_URL", "\"ws://asgard.fritz.box:8080\"")

        // Maximum viewport diagonal in kilometres. Viewports larger than this skip
        // ship loading. Must match or exceed the server's MAX_VIEWPORT_KM setting.
        buildConfigField("double", "MAX_VIEWPORT_KM", "10.0")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // ── Map ──────────────────────────────────────────────────────────────────
    implementation("org.maplibre.gl:android-sdk:11.7.0")

    // ── Networking ───────────────────────────────────────────────────────────
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ── Serialization ────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // ── Coroutines ───────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ── AndroidX ─────────────────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
}
