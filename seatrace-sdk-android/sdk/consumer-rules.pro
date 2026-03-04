# SeaTrace SDK Consumer ProGuard Rules
# These rules are automatically included when consuming the AAR

# Keep model classes for serialization
-keep class io.seatrace.sdk.model.** { *; }

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class io.seatrace.sdk.**$$serializer { *; }
-keepclassmembers class io.seatrace.sdk.** {
    *** Companion;
}
