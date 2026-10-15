# VPNProxy — ProGuard/R8 rules

# --- Kotlin ---
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# --- Kotlinx Serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.vpnproxy.**$$serializer { *; }
-keepclassmembers class com.vpnproxy.** {
    *** Companion;
}
-keepclasseswithmembers class com.vpnproxy.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Hilt / Dagger ---
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# --- Room ---
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# --- SnakeYAML ---
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**

# --- Timber ---
-dontwarn org.jetbrains.annotations.**
-keep class timber.log.Timber { *; }
-keep class timber.log.Timber$* { *; }

# --- AndroidX Security Crypto ---
-keep class androidx.security.crypto.** { *; }

# --- JNI ---
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- AndroidLibXrayLite (libv2ray) ---
-keep class libv2ray.** { *; }
-keep class go.** { *; }
-dontwarn libv2ray.**
-dontwarn go.**

# --- hev-socks5-tunnel AAR ---
-keep class hev.htproxy.** { *; }
-dontwarn hev.htproxy.**

# --- App tunnel / xray wrappers ---
-keep class com.vpnproxy.core.xray.** { *; }
-keep class com.vpnproxy.core.tunnel.** { *; }

# --- Update ---
-keep class com.vpnproxy.update.** { *; }

# --- Compose ---
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }

# --- WorkManager ---
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.CoroutineWorker { *; }

# --- Tink ---
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# --- Debug info ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
}
