# SeaTrace SDK ProGuard Rules

# Keep all public API classes
-keep class io.seatrace.sdk.SeaTraceClient { *; }
-keep class io.seatrace.sdk.SeaTraceConfig { *; }
-keep class io.seatrace.sdk.SeaTraceConfig$Builder { *; }

# Keep all model classes (needed for serialization)
-keep class io.seatrace.sdk.model.** { *; }

# Keep subscription classes
-keep class io.seatrace.sdk.subscription.** { *; }

# Keep error classes
-keep class io.seatrace.sdk.error.** { *; }

# Keep connection state classes
-keep class io.seatrace.sdk.connection.ConnectionState { *; }
-keep class io.seatrace.sdk.connection.ConnectionState$* { *; }
-keep class io.seatrace.sdk.connection.ReconnectPolicy { *; }

# Keep debug classes
-keep class io.seatrace.sdk.debug.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class io.seatrace.sdk.**$$serializer { *; }
-keepclassmembers class io.seatrace.sdk.** {
    *** Companion;
}
-keepclasseswithmembers class io.seatrace.sdk.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
