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
 * Validates actual traffic through the active Sing-box outbound.
 *
 * The app package is excluded from Android's VPN route to prevent routing loops, so a plain
 * HttpURLConnection would bypass the tunnel and test the underlying network instead. Connect
 * explicitly to Sing-box's local mixed inbound; it routes the request through the selected proxy.
 *
 * Uses SOCKS (not HTTP proxy) so the probe target hostname can be resolved on the proxy side
 * (socks5h-style), avoiding local DNS split-horizon against sing-box DoT.
 */
@Singleton
class TrafficValidator @Inject constructor(
    private val logCollector: LogCollector
) {
    companion object {
        private const val TAG = "TrafficValidator"
        private const val PROBE_URL = "https://cp.cloudflare.com/generate_204"
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 4_000
        private const val PORT_WAIT_TIMEOUT_MS = 2_000L
        private const val PORT_POLL_MS = 100
        private const val PORT_CONNECT_TIMEOUT_MS = 100
    }

    suspend fun checkTraffic(serverName: String? = null): Boolean = withContext(Dispatchers.IO) {
        val portReady = waitForPort(
            host = "127.0.0.1",
            port = SingboxConfigBuilder.PROXY_PORT,
            timeoutMs = PORT_WAIT_TIMEOUT_MS
        )
        if (!portReady) {
            logCollector.warn(
                TAG,
                "Port ${SingboxConfigBuilder.PROXY_PORT} not ready after ${PORT_WAIT_TIMEOUT_MS}ms",
                mapOf("server" to (serverName ?: "unknown"))
            )
            return@withContext false
        }

        val startedAtNs = System.nanoTime()
        var connection: HttpURLConnection? = null
        try {
            // SOCKS (mixed inbound accepts SOCKS) — prefer remote DNS over local resolve.
            val localProxy = Proxy(
                Proxy.Type.SOCKS,
                InetSocketAddress("127.0.0.1", SingboxConfigBuilder.PROXY_PORT)
            )
            connection = (URL(PROBE_URL).openConnection(localProxy) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            val status = connection.responseCode
            val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L
            val success = status == HttpURLConnection.HTTP_OK || status == HttpURLConnection.HTTP_NO_CONTENT
            logCollector.info(
                TAG,
                "Traffic probe completed",
                mapOf(
                    "server" to (serverName ?: "unknown"),
                    "proxy" to "socks5://127.0.0.1:${SingboxConfigBuilder.PROXY_PORT}",
                    "status" to status,
                    "elapsedMs" to elapsedMs,
                    "success" to success
                )
            )
            success
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L
            logCollector.warn(
                TAG,
                "Traffic probe failed",
                mapOf(
                    "server" to (serverName ?: "unknown"),
                    "proxy" to "socks5://127.0.0.1:${SingboxConfigBuilder.PROXY_PORT}",
                    "elapsedMs" to elapsedMs,
                    "exception" to e.javaClass.simpleName,
                    "message" to (e.message ?: "")
                )
            )
            false
        } finally {
            connection?.disconnect()
        }
    }

    /** Poll until local mixed inbound accepts TCP, or [timeoutMs] elapses. */
    private suspend fun waitForPort(host: String, port: Int, timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), PORT_CONNECT_TIMEOUT_MS)
                    return true
                }
            } catch (_: Exception) {
                delay(PORT_POLL_MS.toLong())
            }
        }
        return false
    }
}
