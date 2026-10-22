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

data class TrafficProbeResult(
    val success: Boolean,
    val elapsedMs: Long,
    val statusCode: Int? = null,
    val exception: Throwable? = null
)

/**
 * Probes the active Sing-box outbound through its local mixed inbound. The app package may be
 * excluded from Android's VPN route, so the probe must explicitly use the local HTTP proxy.
 */
@Singleton
class TrafficValidator @Inject constructor(
    private val logCollector: LogCollector
) {
    companion object {
        private const val TAG = "TrafficValidator"
        private const val PROBE_URL = "https://cp.cloudflare.com/generate_204"
    }

    suspend fun checkTraffic(serverName: String? = null, passiveDiagnostic: Boolean = false): Boolean {
        val result = probeTraffic(serverName)
        if (passiveDiagnostic && result.exception != null) {
            logCollector.warn(
                TAG,
                "Passive traffic probe exception=${result.exception.javaClass.simpleName}: ${result.exception.message}"
            )
        }
        return result.success
    }

    suspend fun probeTraffic(serverName: String? = null): TrafficProbeResult = withContext(Dispatchers.IO) {
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
                    "proxy" to "http://127.0.0.1:${SingboxConfigBuilder.PROXY_PORT}",
                    "status" to status,
                    "elapsedMs" to elapsedMs,
                    "success" to success
                )
            )
            TrafficProbeResult(success = success, elapsedMs = elapsedMs, statusCode = status)
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L
            logCollector.warn(
                TAG,
                "Traffic probe failed",
                mapOf(
                    "server" to (serverName ?: "unknown"),
                    "proxy" to "http://127.0.0.1:${SingboxConfigBuilder.PROXY_PORT}",
                    "elapsedMs" to elapsedMs,
                    "exception" to e.javaClass.simpleName,
                    "message" to (e.message ?: "")
                )
            )
            TrafficProbeResult(success = false, elapsedMs = elapsedMs, exception = e)
        } finally {
            connection?.disconnect()
        }
    }
}
