package com.vpnproxy.core.singbox

import android.content.Context
import android.util.Log
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.logging.LogCollectorHolder
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

/**
 * Owns the libbox 1.14.3 CommandServer in the application's main process.
 * All core start/stop/reload entry points are serialized by lifecycleMutex.
 */
class SingBoxCore(
    private val context: Context,
    private val platform: SingBoxPlatform,
    private val logCollector: LogCollector? = LogCollectorHolder.get()
) {
    companion object {
        private const val TAG = "SingBoxCore"
        // Raised from 5s/1s after 1.0.791 races: temporary profile-ping cores +
        // forceSelect left 10809 occupied long enough for both wait windows to fail.
        private const val PORT_RELEASE_TIMEOUT_MS = 12_000L
        private const val PORT_POLL_INTERVAL_MS = 150L
        private const val MIN_STOP_START_COOLDOWN_MS = 1_800L
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

    private val lifecycleScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + CoroutineName("SingBoxCoreLifecycle")
    )
    private val lifecycleMutex = Mutex()
    private val lifecycleGeneration = LifecycleGenerationGate()

    @Volatile private var currentServer: CommandServer? = null
    @Volatile private var activeConfig: String? = null
    @Volatile private var lastStopAtMs: Long = 0L

    private val handler = object : CommandServerHandler {
        override fun serviceStop() {
            logCollector?.info(TAG, "Native callback: serviceStop")
            // Keep native callbacks non-blocking; cleanup is serialized with all other lifecycle work.
            lifecycleScope.launch {
                stopAsync().await().onFailure {
                    logCollector?.error(TAG, "serviceStop failed: ${it.message}")
                }
            }
        }

        override fun serviceReload() {
            // Diagnostic build: only VpnProxyService may initiate a lifecycle transition.
            logCollector?.warn(TAG, "Native callback: serviceReload — ignored")
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
            // This callback crosses JNI; collector/logcat failures must never escape into libbox.
            try {
                logCollector?.debug("LIBBOX", "[LIBBOX] $line")
                if (line.contains("connection upload handshake: no available network interface", ignoreCase = true)) {
                    logCollector?.error("LIBBOX", "[LIBBOX] exact handshake failure: $line")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "writeDebugMessage could not append to LogCollector; callback suppressed", t)
            }
            try {
                Log.d(TAG, line)
            } catch (_: Throwable) {
                // Android logging must not escape the native callback either.
            }
        }

        override fun connectSSHAgent(): Int {
            Log.w(TAG, "unsupported CommandServerHandler method: connectSSHAgent")
            return -1
        }
    }

    /** Queues a complete stop/wait/start transition on the single lifecycle executor. */
    fun startAsync(configJson: String): Deferred<Result<Unit>> {
        val generation = lifecycleGeneration.next()
        logCollector?.info(TAG, "Lifecycle request: start generation=$generation inFlight=true")
        return lifecycleScope.async {
            lifecycleMutex.withLock {
                runCatching { startSuspended(configJson, generation) }
            }
        }
    }

    /** Queues a serialized stop without blocking the caller/native callback. */
    fun stopAsync(): Deferred<Result<Unit>> {
        val generation = lifecycleGeneration.next()
        logCollector?.info(TAG, "Lifecycle request: stop generation=$generation inFlight=true")
        return lifecycleScope.async {
            lifecycleMutex.withLock {
                runCatching { stopSuspended() }
            }
        }
    }

    /** Compatibility API for existing synchronous call sites. */
    fun startBlocking(configJson: String): Result<Unit> {
        val generation = lifecycleGeneration.next()
        logCollector?.info(TAG, "Lifecycle request: start generation=$generation inFlight=true")
        return runBlocking {
            lifecycleMutex.withLock { runCatching { startSuspended(configJson, generation) } }
        }
    }

    /** Compatibility API for existing synchronous call sites. */
    fun stopBlocking(): Result<Unit> {
        val generation = lifecycleGeneration.next()
        logCollector?.info(TAG, "Lifecycle request: stop generation=$generation inFlight=true")
        return runBlocking {
            lifecycleMutex.withLock { runCatching { stopSuspended() } }
        }
    }

    /** Legacy source-compatible wrapper; lifecycle serialization is owned here. */
    fun start(configJson: String) {
        startBlocking(configJson).getOrThrow()
    }

    /** Legacy source-compatible wrapper; lifecycle serialization is owned here. */
    fun stop() {
        stopBlocking().getOrThrow()
    }

    /**
     * Debug builds write libbox's own log stream to app-private storage as a second
     * diagnostic source. Release builds keep file logging disabled; callback logging
     * through CommandServerHandler remains active in both variants.
     */
    private fun withDebugNativeLogOutput(configJson: String): String {
        if (!com.vpnproxy.BuildConfig.DEBUG) return configJson
        return try {
            val root = JSONObject(configJson)
            val log = root.optJSONObject("log") ?: JSONObject()
            val logFile = File(File(context.filesDir, "logs").apply { mkdirs() }, "libbox.log")
            log.put("level", "debug")
            log.put("timestamp", true)
            log.put("output", logFile.absolutePath)
            root.put("log", log)
            logCollector?.info(TAG, "Debug native libbox file logging enabled: ${logFile.absolutePath}")
            root.toString()
        } catch (t: Throwable) {
            logCollector?.warn(TAG, "Could not enable debug native libbox file logging: ${t.javaClass.simpleName}: ${t.message}")
            configJson
        }
    }

    private suspend fun startSuspended(configJson: String, generation: Long) {
        require(configJson.isNotBlank()) { "Sing-box configuration is empty" }
        if (!lifecycleGeneration.isCurrent(generation)) {
            logCollector?.warn(TAG, "Lifecycle skip stale start generation=$generation current=${lifecycleGeneration.current()}")
            throw kotlinx.coroutines.CancellationException("Stale lifecycle start generation=$generation")
        }
        logCollector?.info(TAG, "Core lifecycle: stop phase begin generation=$generation")
        // Close once, then perform one authoritative wait below. Previously stopSuspended()
        // waited 12s and startSuspended() immediately waited another 12s for the same port,
        // doubling every failed attempt before any retry could happen.
        stopSuspended(waitForPort = false)

        var portFreed = waitForPortFree("127.0.0.1", SingboxConfigBuilder.PROXY_PORT, PORT_RELEASE_TIMEOUT_MS)
        if (!portFreed) {
            logCollector?.warn(TAG, "Lifecycle generation=$generation port ${SingboxConfigBuilder.PROXY_PORT} still occupied; retrying close on retained CommandServer handle")
            stopSuspended(waitForPort = false)
            // Brief extra settle time after close retry, then perform one bounded wait.
            delay(400)
            portFreed = waitForPortFree("127.0.0.1", SingboxConfigBuilder.PROXY_PORT, PORT_RELEASE_TIMEOUT_MS)
        }
        if (!portFreed) {
            val message = "Port ${SingboxConfigBuilder.PROXY_PORT} still in use after retry; generation=$generation"
            logCollector?.error(TAG, message)
            throw IllegalStateException(message)
        }
        // The listener is confirmed gone; only now release the retained handle.
        currentServer = null
        if (!lifecycleGeneration.isCurrent(generation)) {
            logCollector?.warn(TAG, "Lifecycle skip stale start generation=$generation after port wait; current=${lifecycleGeneration.current()}")
            throw kotlinx.coroutines.CancellationException("Stale lifecycle start generation=$generation")
        }
        logCollector?.info(TAG, "Core lifecycle: port ${SingboxConfigBuilder.PROXY_PORT} free=true")

        val sinceStop = System.currentTimeMillis() - lastStopAtMs
        if (sinceStop < MIN_STOP_START_COOLDOWN_MS) {
            val remaining = MIN_STOP_START_COOLDOWN_MS - sinceStop
            delay(remaining)
            logCollector?.info(TAG, "Core lifecycle: cooldown applied ${remaining}ms")
        } else {
            logCollector?.info(TAG, "Core lifecycle: cooldown applied 0ms")
        }

        if (!lifecycleGeneration.isCurrent(generation)) {
            logCollector?.warn(TAG, "Lifecycle skip stale start generation=$generation after cooldown; current=${lifecycleGeneration.current()}")
            throw kotlinx.coroutines.CancellationException("Stale lifecycle start generation=$generation")
        }
        logCollector?.info(TAG, "Core lifecycle: start phase begin generation=$generation")
        var server: CommandServer? = null
        try {
            setup(context)
            logCollector?.info(TAG, "libbox logger attached")
            if (!lifecycleGeneration.isCurrent(generation)) {
                logCollector?.warn(TAG, "Lifecycle skip stale start generation=$generation immediately before native start; current=${lifecycleGeneration.current()}")
                throw kotlinx.coroutines.CancellationException("Stale lifecycle start generation=$generation")
            }
            val portFreeBeforeStart = waitForPortFree("127.0.0.1", SingboxConfigBuilder.PROXY_PORT, 2_000L)
            if (!portFreeBeforeStart) {
                throw IllegalStateException("Port ${SingboxConfigBuilder.PROXY_PORT} not free before start — generation=$generation")
            }
            logCollector?.info(TAG, "Port ${SingboxConfigBuilder.PROXY_PORT} confirmed free, starting CommandServer")
            val createdServer = CommandServer(handler, platform)
            server = createdServer
            val effectiveConfig = withDebugNativeLogOutput(configJson)
            createdServer.start()
            createdServer.startOrReloadService(effectiveConfig, OverrideOptions())
            currentServer = createdServer
            activeConfig = effectiveConfig
            logCollector?.info(TAG, "Core lifecycle: started successfully generation=$generation")
            Log.i(TAG, "libbox 1.14.3 started in the app process")
        } catch (c: kotlinx.coroutines.CancellationException) {
            server?.let { created ->
                runCatching { created.closeService() }
                runCatching { created.close() }
            }
            runCatching { platform.closeTun() }
            currentServer = null
            activeConfig = null
            throw c
        } catch (t: Throwable) {
            logCollector?.error(TAG, "Core lifecycle: start failed: ${t.message}")
            Log.e(TAG, "Failed to initialize/start libbox", t)
            server?.let { created ->
                runCatching { created.closeService() }
                    .onFailure { logCollector?.warn(TAG, "CommandServer.closeService cleanup failed: ${it.message}") }
                runCatching { created.close() }
                    .onFailure { logCollector?.warn(TAG, "CommandServer.close cleanup failed: ${it.message}") }
            }
            runCatching { platform.closeTun() }
                .onFailure { logCollector?.warn(TAG, "TUN cleanup after libbox startup failure failed: ${it.message}") }
            currentServer = null
            activeConfig = null
            throw IllegalStateException("Unable to start libbox 1.14.3: ${t.message}", t)
        }
    }

    private suspend fun stopSuspended(waitForPort: Boolean = true) {
        // Do not discard the only handle before close has completed. If libbox still
        // owns its listener after the first close request, a retry must be able to reach
        // the same CommandServer instead of logging "no active CommandServer".
        val server = currentServer
        activeConfig = null
        logCollector?.info(TAG, "Core lifecycle: closeService requested")
        if (server != null) {
            runCatching { server.closeService() }
                .onFailure { logCollector?.warn(TAG, "closeService failed: ${it.message}") }
            logCollector?.info(TAG, "Core lifecycle: close requested")
            runCatching { server.close() }
                .onFailure { logCollector?.warn(TAG, "close failed: ${it.message}") }
        } else {
            logCollector?.info(TAG, "Core lifecycle: close requested (no active CommandServer)")
        }
        runCatching { platform.closeTun() }
            .onFailure { logCollector?.warn(TAG, "closeTun failed: ${it.message}") }
        if (waitForPort) {
            val portFreed = waitForPortFree("127.0.0.1", SingboxConfigBuilder.PROXY_PORT, PORT_RELEASE_TIMEOUT_MS)
            logCollector?.info(TAG, "Port ${SingboxConfigBuilder.PROXY_PORT} freed=${portFreed} after stop")
            if (portFreed) {
                if (currentServer === server) currentServer = null
            } else {
                logCollector?.warn(TAG, "Port ${SingboxConfigBuilder.PROXY_PORT} still occupied after ${PORT_RELEASE_TIMEOUT_MS}ms; retaining CommandServer handle for retry")
            }
        }
        lastStopAtMs = System.currentTimeMillis()
        logCollector?.info(TAG, "Core lifecycle: stop completed at ${lastStopAtMs}")
    }

    /**
     * Tests whether a TCP listener is still accepting connections on the proxy port.
     * A refused/failed connect means no listener is reachable; TIME_WAIT is not directly observable
     * by this probe, so bind failures remain guarded by the start error path.
     */
    private suspend fun waitForPortFree(host: String, port: Int, timeoutMs: Long): Boolean {
        val startedAtMs = System.currentTimeMillis()
        val deadline = startedAtMs + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val occupied = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 100)
                    true
                }
            }.getOrDefault(false)
            if (!occupied) {
                logCollector?.debug(TAG, "Port $port wait completed free=true elapsedMs=${System.currentTimeMillis() - startedAtMs}")
                return true
            }
            delay(PORT_POLL_INTERVAL_MS)
        }
        logCollector?.warn(TAG, "Port $port wait completed free=false elapsedMs=${System.currentTimeMillis() - startedAtMs} timeoutMs=$timeoutMs")
        return false
    }
}
