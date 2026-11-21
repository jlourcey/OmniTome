# ─────────────────────────────────────────────────────────────────────────────
# ProGuard / R8 rules for the E-Reader app.
# These rules tell the minifier what NOT to strip or rename so the app
# continues to work correctly in release builds.
# ─────────────────────────────────────────────────────────────────────────────

# ── Keep line numbers in crash stack traces ───────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Hilt ─────────────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# ── Room ─────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# ── Kotlin Serialization ──────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

# ── Retrofit / OkHttp ────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions

# ── Readium ───────────────────────────────────────────────────────────────────
-keep class org.readium.** { *; }
-dontwarn org.readium.**

# ── Google Drive / API client ─────────────────────────────────────────────────
-keep class com.google.api.** { *; }
-keep class com.google.apis.** { *; }
-dontwarn com.google.**

# ── Coil ─────────────────────────────────────────────────────────────────────
-keep class coil.** { *; }

# ── Timber ───────────────────────────────────────────────────────────────────
-dontwarn org.jetbrains.annotations.**

# ── Apache HTTP Client (pulled in by Google API client libs) ──────────────────
# These reference Java SE classes (javax.naming, org.ietf.jgss) that don't
# exist on Android. We don't use Kerberos/LDAP auth so it's safe to ignore.
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.http.**
