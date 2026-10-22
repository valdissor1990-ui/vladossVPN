package com.vpnproxy.core.singbox

import android.content.Context
import com.vpnproxy.BuildConfig
import com.vpnproxy.util.DebugFlags
import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import org.json.JSONObject
import java.util.Locale

/**
 * Owns the libbox 1.14.3 CommandServer in the application's main process.
 * No Android Service or secondary process is created for the core.
 */
class SingBoxCore(
    private val context: Context,
    private val platform: SingBoxPlatform
) {
    companion object {
        private const val TAG = "SingBoxCore"
        @Volatile private var setupComplete = false

        @Synchronized
        private fun setup(context: Context) {
            if (setupComplete) return
            val working = context.getExternalFilesDir(null) ?: context.filesDir
            val options = SetupOptions().apply {
                basePath = context.filesDir.absolutePath
                workingPath = working.absolutePath
                tempPath = context.cacheDir.absolutePath
                logMaxLines = 3000
                debug = false
                appVersion = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString()
                }.getOrDefault("1")
                appMarketingVersion = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1"
                }.getOrDefault("1")
                platformMetadata = JSONObject().apply {
                    put("os", "Android")
                    put("sdk", android.os.Build.VERSION.SDK_INT)
                    put("manufacturer", android.os.Build.MANUFACTURER)
                    put("model", android.os.Build.MODEL)
                }.toString()
            }
            try {
                Log.d(TAG, "Calling Libbox.setLocale()...")
                Libbox.setLocale(Locale.getDefault().toLanguageTag())
                Log.d(TAG, "Calling Libbox.setup()... basePath=${options.basePath}, workingPath=${options.workingPath}")
                Libbox.setup(options)
                setupComplete = true
                Log.i(TAG, "Libbox.setup() completed")
            } catch (t: Throwable) {
                Log.e(TAG, "JNI failure during Libbox initialization", t)
                throw t
            }
        }
    }

    @Volatile private var commandServer: CommandServer? = null
    @Volatile private var activeConfig: String? = null
    @Volatile private var lastCoreStopAtMs: Long = 0L

    private val handler = object : CommandServerHandler {
        override fun serviceStop() {
            Log.w(TAG, "libbox requested service stop")
            stop()
        }

        override fun serviceReload() {
            val config = activeConfig ?: return
            Log.i(TAG, "libbox requested service reload")
            runCatching { start(config) }
                .onFailure { Log.e(TAG, "Reload requested by libbox failed", it) }
        }

        override fun getSystemProxyStatus(): SystemProxyStatus? = null
        override fun setSystemProxyEnabled(isEnabled: Boolean) {
            Log.w(TAG, "unsupported CommandServerHandler method: setSystemProxyEnabled")
        }
        override fun triggerNativeCrash() {
            Log.w(TAG, "unsupported CommandServerHandler method: triggerNativeCrash")
        }
        override fun writeDebugMessage(message: String?) {
            Log.d(TAG, message.orEmpty())
        }
        override fun connectSSHAgent(): Int {
            Log.w(TAG, "unsupported CommandServerHandler method: connectSSHAgent")
            return -1
        }
    }

    @Synchronized
    fun start(configJson: String) {
        require(configJson.isNotBlank()) { "Sing-box configuration is empty" }
        // A previous native service must be fully closed before the next service is created.
        if (commandServer != null || activeConfig != null) stopInternal()
        val cooldownMs = if (BuildConfig.DEBUG) DebugFlags.CORE_RESTART_COOLDOWN_MS else 0L
        val stoppedAt = lastCoreStopAtMs
        if (cooldownMs > 0L && stoppedAt > 0L) {
            val sinceStop = System.currentTimeMillis() - stoppedAt
            val remaining = (cooldownMs - sinceStop).coerceAtLeast(0L)
            if (remaining > 0L) {
                Log.i(TAG, "Core start cooldown ms=$remaining")
                try {
                    Thread.sleep(remaining)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Interrupted during core restart cooldown", interrupted)
                }
            }
        }
        Log.i(TAG, "Core start requested")
        var server: CommandServer? = null
        try {
            Log.i(TAG, "Initializing libbox 1.14.3")
            setup(context)
            Log.d(TAG, "Creating libbox CommandServer...")
            val createdServer = CommandServer(handler, platform)
            server = createdServer
            try {
                Log.d(TAG, "Calling CommandServer.start()...")
                createdServer.start()
                Log.i(TAG, "CommandServer started; applying VPN configuration")
                // libbox 1.14.3's public Java API creates the native service internally
                // in startOrReloadService; there is no direct Libbox.newService() call here.
                Log.d(TAG, "Calling Libbox.newService()... (via CommandServer.startOrReloadService)")
                createdServer.startOrReloadService(configJson, OverrideOptions())
                Log.d(TAG, "startOrReloadService returned successfully")
            } catch (t: Throwable) {
                Log.e(TAG, "JNI/libbox failure while starting CommandServer or service", t)
                throw t
            }
            commandServer = createdServer
            activeConfig = configJson
            Log.i(TAG, "Core started successfully")
            Log.i(TAG, "libbox 1.14.3 started in the app process")
        } catch (t: Throwable) {
            Log.e(TAG, "Core start failed: ${t.message}", t)
            Log.e(TAG, "Failed to initialize/start libbox", t)
            server?.let { created ->
                runCatching { created.closeService() }
                    .onFailure { Log.e(TAG, "CommandServer.closeService cleanup failed", it) }
                runCatching { created.close() }
                    .onFailure { Log.e(TAG, "CommandServer.close cleanup failed", it) }
            }
            runCatching { platform.closeTun() }
                .onFailure { Log.e(TAG, "TUN cleanup after libbox startup failure failed", it) }
            throw IllegalStateException("Unable to start libbox 1.14.3: ${t.message}", t)
        }
    }

    @Synchronized
    fun stop() {
        stopInternal()
    }

    @Synchronized
    private fun stopInternal() {
        Log.i(TAG, "Core stop requested")
        val server = commandServer
        val hadCore = server != null || activeConfig != null
        commandServer = null
        activeConfig = null
        if (server != null) {
            runCatching { server.closeService() }
                .onFailure { Log.w(TAG, "closeService failed", it) }
            runCatching { server.close() }
                .onFailure { Log.w(TAG, "CommandServer.close failed", it) }
        }
        runCatching { platform.closeTun() }
            .onFailure { Log.w(TAG, "closeTun failed", it) }
        if (hadCore) lastCoreStopAtMs = System.currentTimeMillis()
        Log.i(TAG, "Core stopped")
    }
}
