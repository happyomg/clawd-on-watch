# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Keep E2E crypto classes
-keep class com.clawd.watch.crypto.** { *; }
-keep class com.clawd.watch.data.** { *; }
