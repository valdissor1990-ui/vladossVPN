package com.vpnproxy.core.connection

import com.vpnproxy.core.probe.ProbeOutcome
import com.vpnproxy.core.singbox.SingboxConfigBuilder
import com.vpnproxy.logging.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** One probe target attempt (Cloudflare / gstatic / …). */
data class TrafficProbeTargetResult(
    val host: String,
    val outcome: ProbeOutcome,
    val statusCode: Int? = null,
    val elapsedMs: Long,
    val detail: String? = null
) {
    val success: Boolean get() = outcome == ProbeOutcome.CONNECTED
}

/** Aggregate traffic validation through the local mixed inbound. */
data class TrafficProbeReport(
    val success: Boolean,
    val serverName: String?,
    val portReady: Boolean,
    val targets: List<TrafficProbeTargetResult>,
    val durationMs: Long
)

/**
 * Validates real outbound traffic through the active Sing-box mixed inbound.
 *
 * The app package is excluded from Android's VPN route to prevent routing loops, so the probe
 * must explicitly use the local HTTP proxy. A single public probe endpoint is not sufficient:
 * a CDN or captive/network filter can reset that host while another HTTPS destination works.
 */
@Singleton
class TrafficValidator @Inject constructor(
    private val logCollector: LogCollector
) {
    companion object {
        private const val TAG = "TrafficValidator"
        private val PROBE_URLS = listOf(
            "https://cp.cloudflare.com/generate_204",
            "https://connectivitycheck.gstatic.com/generate_204"
        )
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 8_000
        private const val PORT_WAIT_TIMEOUT_MS = 3_000L
        private const val PORT_POLL_MS = 100L
        private const val PORT_CONNECT_TIMEOUT_MS = 150
    }

    /** Compatibility wrapper — prefer [probeTraffic] when outcome detail is needed. */
    suspend fun checkTraffic(serverName: String? = null): Boolean =
        probeTraffic(serverName).success

    suspend fun probeTraffic(serverName: String? = null): TrafficProbeReport = withContext(Dispatchers.IO) {
        val startedAll = System.nanoTime()
        val proxyHost = "127.0.0.1"
        val proxyPort = SingboxConfigBuilder.PROXY_PORT
        if (!waitForPort(proxyHost, proxyPort, PORT_WAIT_TIMEOUT_MS)) {
            logCollector.warn(
                TAG,
                "Local mixed inbound not ready after ${PORT_WAIT_TIMEOUT_MS}ms",
                mapOf("server" to (serverName ?: "unknown"), "port" to proxyPort)
            )
            val elapsed = (System.nanoTime() - startedAll) / 1_000_000L
            return@withContext TrafficProbeReport(
                success = false,
                serverName = serverName,
                portReady = false,
                targets = emptyList(),
                durationMs = elapsed
            )
        }

        val localProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort))
        val results = mutableListOf<TrafficProbeTargetResult>()

        for (probeUrl in PROBE_URLS) {
            val host = URL(probeUrl).host
            val startedAtNs = System.nanoTime()
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(probeUrl).openConnection(localProxy) as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("User-Agent", "VPNProxy-TrafficValidator/1.0")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                val status = connection.responseCode
                val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L
                val ok = status == HttpURLConnection.HTTP_OK ||
                    status == HttpURLConnection.HTTP_NO_CONTENT
                val outcome = if (ok) ProbeOutcome.CONNECTED else ProbeOutcome.IO_ERROR
                val target = TrafficProbeTargetResult(
                    host = host,
                    outcome = outcome,
                    statusCode = status,
                    elapsedMs = elapsedMs,
                    detail = if (ok) null else "HTTP_$status"
                )
                results += target
                logCollector.info(
                    TAG,
                    "Traffic probe completed",
                    mapOf(
                        "server" to (serverName ?: "unknown"),
                        "target" to host,
                        "proxy" to "http://$proxyHost:$proxyPort",
                        "status" to status,
                        "elapsedMs" to elapsedMs,
                        "outcome" to outcome.name,
                        "success" to ok
                    )
                )
                if (ok) {
                    val elapsed = (System.nanoTime() - startedAll) / 1_000_000L
                    return@withContext TrafficProbeReport(
                        success = true,
                        serverName = serverName,
                        portReady = true,
                        targets = results.toList(),
                        durationMs = elapsed
                    )
                }
            } catch (e: Exception) {
                val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L
                val outcome = mapException(e)
                results += TrafficProbeTargetResult(
                    host = host,
                    outcome = outcome,
                    statusCode = null,
                    elapsedMs = elapsedMs,
                    detail = e.message?.take(120)
                )
                logCollector.warn(
                    TAG,
                    "Traffic probe failed",
                    mapOf(
                        "server" to (serverName ?: "unknown"),
                        "target" to host,
                        "proxy" to "http://$proxyHost:$proxyPort",
                        "elapsedMs" to elapsedMs,
                        "outcome" to outcome.name,
                        "exception" to e.javaClass.simpleName,
                        "message" to (e.message ?: "")
                    )
                )
            } finally {
                connection?.disconnect()
            }
        }

        val elapsed = (System.nanoTime() - startedAll) / 1_000_000L
        logCollector.warn(
            TAG,
            "All traffic probe targets failed",
            mapOf(
                "server" to (serverName ?: "unknown"),
                "failures" to results.joinToString(",") { "${it.host}:${it.outcome.name}" }
            )
        )
        TrafficProbeReport(
            success = false,
            serverName = serverName,
            portReady = true,
            targets = results.toList(),
            durationMs = elapsed
        )
    }

    private fun mapException(e: Exception): ProbeOutcome = when (e) {
        is SocketTimeoutException -> ProbeOutcome.TIMEOUT
        is UnknownHostException -> ProbeOutcome.DNS_ERROR
        is ConnectException -> ProbeOutcome.REFUSED
        else -> {
            val msg = (e.message ?: "").lowercase()
            when {
                msg.contains("timeout") -> ProbeOutcome.TIMEOUT
                msg.contains("unreachable") || msg.contains("network is unreachable") ->
                    ProbeOutcome.UNREACHABLE
                msg.contains("refused") -> ProbeOutcome.REFUSED
                msg.contains("unable to resolve") || msg.contains("unknown host") ->
                    ProbeOutcome.DNS_ERROR
                else -> ProbeOutcome.IO_ERROR
            }
        }
    }

    /** Poll until the local mixed inbound accepts TCP, or [timeoutMs] elapses. */
    private suspend fun waitForPort(host: String, port: Int, timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), PORT_CONNECT_TIMEOUT_MS)
                    return true
                }
            } catch (_: Exception) {
                delay(PORT_POLL_MS)
            }
        }
        return false
    }
}
