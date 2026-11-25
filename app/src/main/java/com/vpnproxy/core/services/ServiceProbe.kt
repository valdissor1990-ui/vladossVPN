package com.vpnproxy.core.services

import com.vpnproxy.core.singbox.SingboxConfigBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/** Low-level probe result before it is mapped to a [ServiceCheckStatus]. */
data class ServiceProbeResult(
    val reachable: Boolean,
    val latencyMs: Long,
    val httpCode: Int? = null,
    val reason: String? = null
)

/** Allows tests to replace the network with a fake. */
interface ServiceProbe {
    suspend fun probe(target: ServiceTarget): ServiceProbeResult
}

/**
 * Probes a target through the active sing-box mixed inbound.
 *
 * The caller must ensure the VPN is running and not transitioning. This class
 * never starts a second core: it only uses the local proxy that the active VPN
 * already exposes.
 */
class HttpServiceProbe(
    private val proxyHost: String = "127.0.0.1",
    private val proxyPort: Int = SingboxConfigBuilder.PROXY_PORT
) : ServiceProbe {

    companion object {
        private const val TIMEOUT_MS = 4_000
        private const val USER_AGENT = "vladossVPN/1.0"
    }

    override suspend fun probe(target: ServiceTarget): ServiceProbeResult = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        val head = attempt(target, "HEAD")
        if (head.reachable) return@withContext head.copy(latencyMs = elapsedMs(startedAt))
        val get = attempt(target, "GET")
        get.copy(
            latencyMs = elapsedMs(startedAt),
            reason = get.reason ?: head.reason
        )
    }

    private suspend fun attempt(target: ServiceTarget, method: String): ServiceProbeResult {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(target.url)
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort))
            val active = (url.openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Cache-Control", "no-cache")
                if (method == "GET") setRequestProperty("Range", "bytes=0-0")
            }
            connection = active
            val code = runInterruptible(Dispatchers.IO) { active.responseCode }
            ServiceProbeResult(
                reachable = code in 200..399,
                latencyMs = 0L,
                httpCode = code,
                reason = if (code in 200..399) null else "HTTP_$code"
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ServiceProbeResult(
                reachable = false,
                latencyMs = 0L,
                reason = e.javaClass.simpleName
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun elapsedMs(startedAt: Long): Long =
        ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
}
