# Project-specific ProGuard / R8 keep rules for Release builds (Android SDK 36)

# Room Database Entities & DAOs
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Moshi & JSON Serialization
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json(name = *) <fields>;
}

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# J.A.R.V.I.S. Entities, Models, AI & System Services
-keep class com.example.data.local.** { *; }
-keep class com.example.model.** { *; }
-keep class com.example.tools.** { *; }
-keep class com.example.permissions.** { *; }
-keep class com.example.service.** { *; }
-keep class com.example.automation.** { *; }

# Keep BroadcastReceivers, AccessibilityService, InCallService, NotificationListenerService entry points
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.accessibilityservice.AccessibilityService
-keep public class * extends android.telecom.InCallService
-keep public class * extends android.service.notification.NotificationListenerService
-keep public class * extends android.app.Service

# OkHttp & WebSockets
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# CameraX & ML Kit Vision
-keep class androidx.camera.** { *; }
-keepclassmembers class androidx.camera.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.mlkit.** { *; }

# Credential Manager / Google Identity Services
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
