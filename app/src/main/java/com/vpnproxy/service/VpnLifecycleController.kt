package com.vpnproxy.service

import com.vpnproxy.core.singbox.SingBoxCore
import com.vpnproxy.logging.LogCollector
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.delay
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
            logCollector.info("VpnLifecycleController", "Restart lifecycle: stop requested; restartDelayMs=$restartDelayMs")
            stopSession()
            delay(restartDelayMs)
            logCollector.info("VpnLifecycleController", "Restart lifecycle: cooldown elapsed; starting same selected session")
        }
        check(!closed) { "VPN lifecycle controller is closed" }
        startSession()
    }

    fun startCore(configJson: String) {
        check(!closed) { "VPN lifecycle controller is closed" }
        core.startBlocking(configJson).getOrThrow()
    }

    fun stopCore() {
        if (closed) return
        core.stopBlocking().getOrThrow()
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { core.stopBlocking().getOrThrow() }
            .onFailure { logCollector.warn("VpnLifecycleController", "Sing-box cleanup failed: ${it.message}") }
    }
}
