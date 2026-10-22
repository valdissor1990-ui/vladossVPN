package com.vpnproxy.service

import com.vpnproxy.core.singbox.SingBoxCore
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.util.DebugFlags
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns serialized access to the Sing-box core used by VpnProxyService.
 *
 * ConnectionCoordinator events, foreground notifications, traffic verification and service
 * keep-alive policy deliberately remain in VpnProxyService: they are Android/service orchestration
 * and moving them would risk changing reconnect and UI-visible behavior.
 */
internal class VpnLifecycleController(
    private val core: SingBoxCore,
    private val logCollector: LogCollector
) {
    private val lifecycleMutex = Mutex()
    @Volatile private var closed = false
    @Volatile private var lastCoreStopAtMs = 0L

    companion object {
        private const val TAG = "VpnLifecycleController"
    }

    suspend fun <T> withLifecycleLock(block: suspend () -> T): T =
        lifecycleMutex.withLock { block() }

    /**
     * Serializes a complete soft-restart transition. The callbacks retain Android/service side
     * effects in VpnProxyService while this controller owns ordering and the restart delay.
     */
    suspend fun restartSession(
        coreIsRunning: () -> Boolean,
        stopSession: suspend () -> Unit,
        startSession: suspend () -> Unit,
        restartDelayMs: Long
    ) = lifecycleMutex.withLock {
        check(!closed) { "VPN lifecycle controller is closed" }
        if (coreIsRunning()) {
            stopSession()
            lastCoreStopAtMs = System.currentTimeMillis()
            val wait = maxOf(restartDelayMs, DebugFlags.CORE_RESTART_COOLDOWN_MS)
            logCollector.info(TAG, "Core start cooldown ms=$wait (restartSession)")
            delay(wait)
        }
        check(!closed) { "VPN lifecycle controller is closed" }
        startSession()
    }

    fun startCore(configJson: String) {
        check(!closed) { "VPN lifecycle controller is closed" }
        core.start(configJson)
    }

    /**
     * Stop previous core if needed, wait cooldown, then start.
     * Prevents "initialize cache-file: timeout" on rapid restart.
     */
    suspend fun startCoreWithCooldown(configJson: String) {
        check(!closed) { "VPN lifecycle controller is closed" }
        logCollector.info(TAG, "Core stop requested")
        runCatching { core.stop() }
            .onFailure { logCollector.warn(TAG, "Core stop failed: ${it.message}") }
        lastCoreStopAtMs = System.currentTimeMillis()
        logCollector.info(TAG, "Core stopped")
        val sinceStop = System.currentTimeMillis() - lastCoreStopAtMs
        val cooldown = DebugFlags.CORE_RESTART_COOLDOWN_MS
        if (sinceStop < cooldown) {
            val wait = cooldown - sinceStop
            logCollector.info(TAG, "Core start cooldown ms=$wait")
            delay(wait)
        }
        logCollector.info(TAG, "Core start requested")
        try {
            core.start(configJson)
            logCollector.info(TAG, "Core started successfully")
        } catch (t: Throwable) {
            logCollector.error(TAG, "Core start failed: ${t.message}")
            throw t
        }
    }

    fun stopCore() {
        if (closed) return
        logCollector.info(TAG, "Core stop requested")
        core.stop()
        lastCoreStopAtMs = System.currentTimeMillis()
        logCollector.info(TAG, "Core stopped")
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { core.stop() }
            .onFailure { logCollector.warn(TAG, "Sing-box cleanup failed: ${it.message}") }
        lastCoreStopAtMs = System.currentTimeMillis()
    }
}
