# ProGuard rules for Swift Agent (J330)
# Add project-specific ProGuard rules here.

# Keep Firebase classes
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class **$$serializer { *; }

# Keep Room entities
-keep class com.momo.swift.data.** { *; }

# Keep Credential Manager classes
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# Keep Play Core (In-App Updates)
-keep class com.google.android.play.core.** { *; }
