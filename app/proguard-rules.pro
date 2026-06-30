# HexRDP ProGuard Rules
#
# SEC-OBFUSCATION FIX: The previous rule "-keep class com.gotohex.rdp.** { *; }"
# disabled ALL R8 obfuscation and shrinking for the entire application package.
# Any attacker unpacking the APK received class names, method names, field names,
# and string constants in plain text — making reverse-engineering and vulnerability
# discovery trivial.
#
# Replacement strategy: keep ONLY the classes/members that MUST be kept by name
# (Hilt injection entry-points, Room entities/DAOs, JNI bridges, Gson models).
# Everything else is obfuscated and shrunk by R8.

# ── Android entry-points (kept by the platform; R8 knows these) ──────────────
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

# ── Hilt ─────────────────────────────────────────────────────────────────────
# Hilt generates glue code that references ViewModel classes by name at runtime.
-keepnames @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel
# Hilt component interfaces must survive shrinking
-keep @dagger.hilt.InstallIn class *
-keep @dagger.hilt.android.HiltAndroidApp class *
-keep @dagger.hilt.android.AndroidEntryPoint class *
-keepclasseswithmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}

# ── Room ──────────────────────────────────────────────────────────────────────
# Room's annotation processor generates code that references entity field names
# and DAO method names at runtime via reflection/generated SQL.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keepclassmembers @androidx.room.Entity class * { *; }
# Room migration lambdas reference column names as strings
-keepclassmembers class * extends androidx.room.migration.Migration { *; }

# ── JNI bridge ───────────────────────────────────────────────────────────────
# AFreeRdpBridge and hexrdp_jni.c call each other by exact name via JNI.
# Renaming either side breaks the native↔JVM boundary at runtime.
-keep class com.gotohex.rdp.rdp.native.AFreeRdpBridge { *; }
# 🔴 CRIT FIX: the JNI callback methods invoked from C via GetMethodID() must be
# kept under their REAL names/signatures, exactly as declared in AFreeRdpBridge.kt
# and looked up in hexrdp_jni.c (Java_..._nativeInit):
#   onNativeFrame(I I I I [I Z)V   ← hctx->onFrameMethod
#   onNativeState(I)V              ← hctx->onStateMethod
#   onNativeError(Ljava/lang/String;)V ← hctx->onErrorMethod
# The previous rule kept non-existent methods named "onFrame(int,int)" and
# "onError(java.lang.String)" — those names/signatures do not exist anywhere in
# AFreeRdpBridge.kt, so the rule matched nothing. With isMinifyEnabled=true
# (release builds only), R8 was therefore free to rename/strip the real
# onNativeFrame/onNativeError methods. hexrdp_jni.c's GetMethodID() calls would
# then return NULL, and the subsequent CallVoidMethod() on a NULL jmethodID is
# undefined behaviour (crash) the first time the native bridge tries to deliver
# a frame, state change, or error to Kotlin — i.e. on every real RDP session in
# a release/signed build using the native FreeRDP backend.
-keepclassmembers class com.gotohex.rdp.rdp.native.AFreeRdpBridge {
    public void onNativeState(int);
    public void onNativeError(java.lang.String);
    public void onNativeFrame(int, int, int, int, int[], boolean);
}

# ── bVNC / LibVNCAndroid ──────────────────────────────────────────────────────
# RfbConnectable, Connection, AuthenticationException, and RemotePointer are
# referenced by name from VncClient.kt. Without these rules R8 strips or
# renames them in release builds → NoClassDefFoundError at runtime. (CRIT-NEW-3)
-keep class com.undatech.opaque.** { *; }
-dontwarn com.undatech.opaque.**

# ── Gson serialisation ────────────────────────────────────────────────────────
# Gson uses reflection on field names to serialize/deserialize JSON.
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
# Keep any class annotated with @SerializedName (Gson model fields)
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── BouncyCastle ──────────────────────────────────────────────────────────────
# Required for TLS (NLA, self-signed cert generation in HttpFileServer).
# BouncyCastle uses reflection internally for algorithm lookup.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ── Kotlin Coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ── Compose ───────────────────────────────────────────────────────────────────
-dontwarn androidx.compose.**

# ── Coil ─────────────────────────────────────────────────────────────────────
-dontwarn coil.**

# ── Debug symbols (crash reporting) ──────────────────────────────────────────
# Retain source file names and line numbers so stack traces remain meaningful
# in crash reports — without exposing method/class names in the binary.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

