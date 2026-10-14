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
