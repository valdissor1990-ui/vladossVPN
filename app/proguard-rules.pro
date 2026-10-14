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

# --- Room (если используется) ---
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keepclassmembers class * extends java.net.Proxy { *; }

# --- SnakeYAML ---
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**

# --- Timber ---
-dontwarn org.jetbrains.annotations.**
-keep class timber.log.Timber { *; }
-keep class timber.log.Timber$* { *; }

# --- AndroidX Security Crypto ---
-keep class androidx.security.crypto.** { *; }

# --- JNI / Xray-core ---
# Нативная библиотека использует JNI — сохраняем методы с native
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.vpnproxy.core.xray.XrayCore { *; }
-keep class com.vpnproxy.core.tunnel.HevTunnelBridge { *; }

# --- TUN2SOCKS / JNI wrapper ---
-keep class com.vpnproxy.core.tunnel.** { *; }
-keep class com.vpnproxy.core.xray.** { *; }

# --- Update ---
-keep class com.vpnproxy.update.** { *; }

# --- Compose ---
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class androidx.compose.runtime.** { *; }

# --- WorkManager ---
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.CoroutineWorker { *; }

# --- EncryptedSharedPreferences / Tink ---
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# --- Отладочная информация ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Удалить логи в release
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
}
