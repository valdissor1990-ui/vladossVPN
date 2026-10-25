package com.vpnproxy.core.connection

import com.vpnproxy.core.singbox.SingboxConfigBuilder
import com.vpnproxy.logging.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

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
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 4_000
        private const val PORT_WAIT_TIMEOUT_MS = 3_000L
        private const val PORT_POLL_MS = 100L
        private const val PORT_CONNECT_TIMEOUT_MS = 150
    }

    suspend fun checkTraffic(serverName: String? = null): Boolean = withContext(Dispatchers.IO) {
        val proxyHost = "127.0.0.1"
        val proxyPort = SingboxConfigBuilder.PROXY_PORT
        if (!waitForPort(proxyHost, proxyPort, PORT_WAIT_TIMEOUT_MS)) {
            logCollector.warn(
                TAG,
                "Local mixed inbound not ready after ${PORT_WAIT_TIMEOUT_MS}ms",
                mapOf("server" to (serverName ?: "unknown"), "port" to proxyPort)
            )
            return@withContext false
        }

        val localProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort))
        val failures = mutableListOf<String>()
        for (probeUrl in PROBE_URLS) {
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
                val success = status == HttpURLConnection.HTTP_OK ||
                    status == HttpURLConnection.HTTP_NO_CONTENT
                logCollector.info(
                    TAG,
                    "Traffic probe completed",
                    mapOf(
                        "server" to (serverName ?: "unknown"),
                        "target" to URL(probeUrl).host,
                        "proxy" to "http://$proxyHost:$proxyPort",
                        "status" to status,
                        "elapsedMs" to elapsedMs,
                        "success" to success
                    )
                )
                if (success) return@withContext true
                failures += "${URL(probeUrl).host}:HTTP_$status"
            } catch (e: Exception) {
                val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L
                val reason = "${URL(probeUrl).host}:${e.javaClass.simpleName}"
                failures += reason
                logCollector.warn(
                    TAG,
                    "Traffic probe failed",
                    mapOf(
                        "server" to (serverName ?: "unknown"),
                        "target" to URL(probeUrl).host,
                        "proxy" to "http://$proxyHost:$proxyPort",
                        "elapsedMs" to elapsedMs,
                        "exception" to e.javaClass.simpleName,
                        "message" to (e.message ?: "")
                    )
                )
            } finally {
                connection?.disconnect()
            }
        }

        logCollector.warn(
            TAG,
            "All traffic probe targets failed",
            mapOf("server" to (serverName ?: "unknown"), "failures" to failures.joinToString(","))
        )
        false
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
