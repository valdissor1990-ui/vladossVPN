package com.vpnproxy.smartconnect

import com.vpnproxy.core.singbox.SingboxConfigBuilder
import com.vpnproxy.logging.LogCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * Owns the low-level direct-vs-proxy socket probes used to validate a suspect server.
 * Protecting a direct socket is supplied by the service so the Android VPN API stays there.
 */
internal class HealthMonitor(
    private val logCollector: LogCollector
) {
    suspend fun probeSocket(
        host: String,
        port: Int,
        protectFromVpn: Boolean,
        protectSocket: (Socket) -> Boolean
    ): ProbeResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        try {
            val socketProxy = if (protectFromVpn) Proxy.NO_PROXY else Proxy(
                Proxy.Type.SOCKS,
                InetSocketAddress("127.0.0.1", SingboxConfigBuilder.PROXY_PORT)
            )
            Socket(socketProxy).use { socket ->
                if (protectFromVpn) {
                    val protected = protectSocket(socket)
                    logCollector.info(
                        "AutoSwitcher",
                        "Direct probe protect(socket) invoked: result=$protected, target=$host:$port"
                    )
                    if (!protected) return@withContext ProbeResult(Long.MAX_VALUE, false)
                }
                runInterruptible(Dispatchers.IO) {
                    socket.connect(InetSocketAddress(host, port), AutoSwitcherConfig.directProbeTimeoutMs)
                }
                val elapsed = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
                ProbeResult(elapsed, true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logCollector.warn(
                "AutoSwitcher",
                "Socket probe failed for $host:$port protect=$protectFromVpn: ${e.message}"
            )
            ProbeResult(Long.MAX_VALUE, false)
        }
    }

    fun calculateJitter(previousRttMs: Long?, currentRttMs: Long): Long =
        previousRttMs?.let { kotlin.math.abs(currentRttMs - it) } ?: 0L
}
