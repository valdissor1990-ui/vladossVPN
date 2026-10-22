package com.vpnproxy.core.connection

import com.vpnproxy.core.singbox.SingboxConfigBuilder
import com.vpnproxy.logging.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validates actual traffic through the active Sing-box outbound.
 *
 * The app package is excluded from Android's VPN route to prevent routing loops, so a plain
 * HttpURLConnection would bypass the tunnel and test the underlying network instead. Connect
 * explicitly to Sing-box's local mixed inbound; it routes the request through the selected proxy.
 */
@Singleton
class TrafficValidator @Inject constructor(
    private val logCollector: LogCollector
) {
    companion object {
        private const val TAG = "TrafficValidator"
        private const val PROBE_URL = "https://cp.cloudflare.com/generate_204"
    }

    suspend fun checkTraffic(serverName: String? = null, passiveDiagnostic: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val startedAtNs = System.nanoTime()
        var connection: HttpURLConnection? = null
        try {
            val localProxy = Proxy(
                Proxy.Type.HTTP,
                InetSocketAddress("127.0.0.1", SingboxConfigBuilder.PROXY_PORT)
            )
            connection = (URL(PROBE_URL).openConnection(localProxy) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4_000
                readTimeout = 4_000
                instanceFollowRedirects = false
                useCaches = false
            }
            val status = connection.responseCode
            val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L
            val success = status == HttpURLConnection.HTTP_OK || status == HttpURLConnection.HTTP_NO_CONTENT
            logCollector.info(
                TAG,
                "Traffic probe completed",
                mapOf(
                    "server" to (serverName ?: "unknown"),
                    "proxy" to "127.0.0.1:${SingboxConfigBuilder.PROXY_PORT}",
                    "status" to status,
                    "elapsedMs" to elapsedMs,
                    "success" to success
                )
            )
            success
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L
            if (passiveDiagnostic) {
                logCollector.warn(TAG, "Passive traffic probe exception=${e.javaClass.simpleName}: ${e.message}")
            }
            logCollector.warn(
                TAG,
                "Traffic probe failed",
                mapOf(
                    "server" to (serverName ?: "unknown"),
                    "proxy" to "127.0.0.1:${SingboxConfigBuilder.PROXY_PORT}",
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
}
