# R8 Full Mode Optimization Rules for MemoryVoid

-keep public class com.example.essentialspace.MainActivity
-keep public class com.example.essentialspace.KeyMappingService
-keep public class com.example.essentialspace.NotificationHelper
-keep public class com.example.essentialspace.ThemeManager

-keep class org.json.** { *; }

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn kotlinx.coroutines.**

-keep class com.github.chrisbanes.photoview.** { *; }

# Keep Kotlin standard library classes to prevent invalid cross-package class merging/repackaging
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
# Keep all classes, methods, and fields in MemoryVoid / EssentialSpace
-keep class com.example.essentialspace.** { *; }
-keep interface com.example.essentialspace.** { *; }
-dontwarn com.example.essentialspace.**

# Keep PhotoView, SwipeRefreshLayout, and Material components intact
-keep class com.github.chrisbanes.photoview.** { *; }
-keep class com.google.android.material.** { *; }
-keep class androidx.** { *; }

# Keep standard Android components
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.accessibilityservice.AccessibilityService
-keep public class * extends android.content.BroadcastReceiver