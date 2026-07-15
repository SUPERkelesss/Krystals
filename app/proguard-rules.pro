# ProGuard / R8 rules for Krystals release builds.
# Keep rules mirror the libraries shipped in app/build.gradle.kts.

# --- Kotlin / Compose ---
# Compose ships its own consumer rules; keep runtime reflection entry points intact.
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.** { volatile <fields>; }

# --- OkHttp 4.x (officially recommended rules) ---
# https://square.github.io/okhttp/r8_proguard/
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# OkHttp uses platform lookup (Platform.get) and reflection on several platform
# classes; the don'twarn above silences the missing references on Android.
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# --- Materials Project JSON parsing ---
# org.json is part of the Android framework, no keep needed. Keep the app's own
# data classes that are never reflected on — nothing to do here.

# --- Coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# --- Keep BuildConfig (used by MaterialsProject User-Agent) ---
-keep class com.krystals.app.BuildConfig { *; }
