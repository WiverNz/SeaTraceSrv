plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
    // OpenAPI generator disabled - using hand-written models in sdk/model/Models.kt
    // The generator creates non-sealed interfaces with @Serializable which kotlinx.serialization doesn't support
    // id("org.openapi.generator") version "7.4.0"
}

android {
    namespace = "io.seatrace.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // Build config for test endpoint
        buildConfigField("String", "TEST_ENDPOINT", "\"ws://asgard.fritz.box:8080/realtime\"")
        buildConfigField("String", "TEST_HTTP_ENDPOINT", "\"http://asgard.fritz.box:8080\"")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
        )
    }

    buildFeatures {
        buildConfig = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

// OpenAPI Generator configuration disabled - using hand-written models
// See sdk/src/main/kotlin/io/seatrace/sdk/model/Models.kt for data models
// To re-enable, uncomment the plugin above and the configuration below:
//
// openApiGenerate {
//     generatorName.set("kotlin")
//     inputSpec.set("$rootDir/../api-contracts/openapi.yaml")
//     outputDir.set("$buildDir/generated/openapi")
//     packageName.set("io.seatrace.sdk.generated")
//     modelPackage.set("io.seatrace.sdk.generated.model")
//     apiPackage.set("io.seatrace.sdk.generated.api")
//     configOptions.set(mapOf(
//         "dateLibrary" to "java8",
//         "serializationLibrary" to "kotlinx_serialization",
//         "enumPropertyNaming" to "UPPERCASE",
//         "collectionType" to "list",
//         "generateOneOfAnyOfWrappers" to "false",
//         "omitGradleWrapper" to "true"
//     ))
//     globalProperties.set(mapOf(
//         "models" to "",
//         "modelDocs" to "false",
//         "modelTests" to "false",
//         "apis" to "false"
//     ))
// }
// tasks.named("preBuild") { dependsOn("openApiGenerate") }

dependencies {
    // Kotlin
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // OkHttp for WebSocket
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)

    // Instrumented tests
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
}

// Publishing configuration
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "io.seatrace"
            artifactId = "sdk"
            version = "1.0.0"

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("SeaTrace SDK")
                description.set("Android SDK for SeaTraceSrv - Real-time maritime vessel tracking")
                url.set("https://github.com/your-org/seatrace-sdk-android")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("seatrace")
                        name.set("SeaTrace Team")
                    }
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/your-org/seatrace-sdk-android")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
