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

        ndk {
            // H3-Java ships native .so files; include the ABIs your targets need.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    sourceSets {
        getByName("main") {
            // Include dynamically extracted H3 libraries into the build
            jniLibs.srcDir(layout.buildDirectory.dir("h3-jni").get().asFile)
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

// ── H3 Native Library Extraction ─────────────────────────────────────────────
// The com.uber:h3 standard jar contains precompiled .so files inside "android-arm64" 
// and "android-arm" folders within the jar. Android Gradle Plugin doesn't unpack 
// jars (only AARs) automatically, so we dynamically extract them here.
val extractH3NativeLibs by tasks.registering(Copy::class) {
    // Only parse dependencies from the releaseCompileClasspath to avoid resolution issues.
    dependsOn(configurations.getByName("releaseCompileClasspath"))
    
    doFirst {
        val h3File = configurations.getByName("releaseCompileClasspath").files.find { 
            it.name.startsWith("h3-") && it.extension == "jar" 
        }
        if (h3File != null) {
            from(zipTree(h3File)) {
                include("android-arm64/libh3-java.so")
                include("android-arm/libh3-java.so")
            }
            into(layout.buildDirectory.dir("h3-jni"))
            
            eachFile {
                if (path.startsWith("android-arm64")) {
                    path = path.replaceFirst("android-arm64", "arm64-v8a")
                } else if (path.startsWith("android-arm/")) {
                    path = path.replaceFirst("android-arm", "armeabi-v7a")
                }
            }
            includeEmptyDirs = false
        }
    }
}

// Ensure extraction happens before Android tasks process libraries
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
        dependsOn(extractH3NativeLibs)
    }
}

dependencies {
    // ── Map ──────────────────────────────────────────────────────────────────
    implementation("org.maplibre.gl:android-sdk:11.7.0")

    // ── H3 spatial index (matches server-side h3o resolution) ────────────────
    // Note: uses JNI native libs — verify ABI filters above match your devices.
    implementation("com.uber:h3:4.1.1")

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
