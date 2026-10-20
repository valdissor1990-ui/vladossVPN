package com.vpnproxy.smartconnect

import com.vpnproxy.core.singbox.SingboxConfigBuilder
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
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
internal data class MicroProbeResult(
    val bytesRead: Long,
    val elapsedMs: Long,
    val speedMbps: Double
)

internal class HealthMonitor(
    private val logCollector: LogCollector
) {
    private val microProbeClient = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", SingboxConfigBuilder.PROXY_PORT)))
        .connectTimeout(AutoSwitcherConfig.microProbeTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(AutoSwitcherConfig.microProbeTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout(AutoSwitcherConfig.microProbeTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

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

    suspend fun probeMicroSpeed(): MicroProbeResult {
        val startedAt = System.nanoTime()
        val request = Request.Builder()
            .url(AutoSwitcherConfig.microProbeUrl)
            .header("User-Agent", "VPNProxy/1.0")
            .header("Cache-Control", "no-cache")
            .build()
        val bytesRead = withContext(Dispatchers.IO) {
            microProbeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                val input = response.body?.byteStream()
                    ?: throw IllegalStateException("Empty micro-probe response")
                input.use { stream ->
                    val buffer = ByteArray(AutoSwitcherConfig.microProbeBufferBytes)
                    var total = 0L
                    while (total < AutoSwitcherConfig.microProbeBytes) {
                        val read = stream.read(
                            buffer,
                            0,
                            minOf(buffer.size.toLong(), AutoSwitcherConfig.microProbeBytes - total).toInt()
                        )
                        if (read < 0) break
                        total += read
                    }
                    total
                }
            }
        }
        val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
        val speedMbps = bytesRead * 8.0 / 1_000_000.0 / (elapsedMs / 1_000.0)
        return MicroProbeResult(bytesRead, elapsedMs, speedMbps)
    }

    fun calculateJitter(previousRttMs: Long?, currentRttMs: Long): Long =
        previousRttMs?.let { kotlin.math.abs(currentRttMs - it) } ?: 0L
}
