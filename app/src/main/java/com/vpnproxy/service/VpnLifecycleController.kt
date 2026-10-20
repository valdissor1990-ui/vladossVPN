package com.vpnproxy.service

import com.vpnproxy.core.singbox.SingBoxCore
import com.vpnproxy.logging.LogCollector
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

    suspend fun <T> withLifecycleLock(block: suspend () -> T): T =
        lifecycleMutex.withLock { block() }

    fun startCore(configJson: String) {
        check(!closed) { "VPN lifecycle controller is closed" }
        core.start(configJson)
    }

    fun stopCore() {
        core.stop()
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { core.stop() }
            .onFailure { logCollector.warn("VpnLifecycleController", "Sing-box cleanup failed: ${it.message}") }
    }
}
