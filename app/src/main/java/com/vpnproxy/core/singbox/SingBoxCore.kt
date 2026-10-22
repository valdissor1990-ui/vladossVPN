package com.vpnproxy.core.singbox

import android.content.Context
import android.util.Log
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.logging.LogCollectorHolder
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the libbox 1.14.3 CommandServer in the application's main process.
 * No Android Service or secondary process is created for the core.
 */
class SingBoxCore(
    private val context: Context,
    private val platform: SingBoxPlatform,
    private val logCollector: LogCollector? = LogCollectorHolder.get()
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
                debug = com.vpnproxy.BuildConfig.DEBUG
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

    private val firstOutboundLogged = AtomicBoolean(false)
    @Volatile private var pendingOutboundTarget: String? = null

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
            val line = message.orEmpty()
            logCollector?.debug("LIBBOX", "[LIBBOX] $line")
            if (line.contains("connection upload handshake: no available network interface", ignoreCase = true)) {
                logCollector?.error("LIBBOX", "[LIBBOX] exact handshake failure: $line")
            }
            if (line.contains("outbound connection to ", ignoreCase = true)) {
                pendingOutboundTarget = Regex("outbound connection to ([^\\s]+)", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.getOrNull(1)
            }
            val outboundSucceeded = line.contains("connection upload finished", ignoreCase = true) ||
                line.contains("connection download finished", ignoreCase = true) ||
                line.contains("connection upload complete", ignoreCase = true) ||
                line.contains("connection download complete", ignoreCase = true)
            if (outboundSucceeded && !line.contains("no available network interface", ignoreCase = true) &&
                firstOutboundLogged.compareAndSet(false, true)
            ) {
                val endpoint = pendingOutboundTarget ?: "unknown"
                logCollector?.info("LIBBOX", "Outbound established: $endpoint via protect")
            }
            Log.d(TAG, line)
        }
        override fun connectSSHAgent(): Int {
            Log.w(TAG, "unsupported CommandServerHandler method: connectSSHAgent")
            return -1
        }
    }

    @Synchronized
    fun start(configJson: String) {
        require(configJson.isNotBlank()) { "Sing-box configuration is empty" }
        stopInternal()
        firstOutboundLogged.set(false)
        pendingOutboundTarget = null
        var server: CommandServer? = null
        try {
            Log.i(TAG, "Initializing libbox 1.14.3")
            setup(context)
            logCollector?.info(TAG, "libbox logger attached")
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
            Log.i(TAG, "libbox 1.14.3 started in the app process")
        } catch (t: Throwable) {
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
        val server = commandServer
        commandServer = null
        activeConfig = null
        if (server != null) {
            runCatching { server.closeService() }
                .onFailure { Log.w(TAG, "closeService failed", it) }
            runCatching { server.close() }
                .onFailure { Log.w(TAG, "CommandServer.close failed", it) }
        }
        platform.closeTun()
    }
}
