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
 * The caller must ensure the VPN is running and not transitioning. This class never starts
 * a second core: it only uses the local proxy that the active VPN already exposes.
 *
 * Only a 2xx answer proves the control request reached the target. Redirects and HTTP errors
 * are classified by [ServiceChecker], not here.
 */
class HttpServiceProbe(
    private val proxyHost: String = "127.0.0.1",
    private val proxyPort: Int = SingboxConfigBuilder.PROXY_PORT,
    /** Overridden in tests to bypass the local proxy and talk to a stub server. */
    private val proxyOverride: Proxy? = null
) : ServiceProbe {

    companion object {
        private const val TIMEOUT_MS = 4_000
        private const val USER_AGENT = "vladossVPN/1.0"

        /** Bodies are drained with a cap so a huge page cannot stall the sweep. */
        private const val DRAIN_LIMIT_BYTES = 4_096
    }

    override suspend fun probe(target: ServiceTarget): ServiceProbeResult = withContext(Dispatchers.IO) {
        // HEAD first: it is cheap and most control endpoints answer it. Some CDNs reject HEAD,
        // so a failed HEAD falls back to a one-byte GET.
        val head = attempt(target, "HEAD")
        if (head.reachable) return@withContext head
        val get = attempt(target, "GET")
        // A successful GET carries no reason; only a failed one inherits HEAD's explanation.
        get.copy(reason = if (get.reachable) null else (get.reason ?: head.reason))
    }

    private suspend fun attempt(target: ServiceTarget, method: String): ServiceProbeResult {
        var connection: HttpURLConnection? = null
        val startedAt = System.nanoTime()
        return try {
            val url = URL(target.url)
            val proxy = proxyOverride
                ?: Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort))
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
            drain(active, method, code)
            ServiceProbeResult(
                reachable = code in 200..299,
                latencyMs = elapsedMs(startedAt),
                httpCode = code,
                reason = if (code in 200..299) null else "HTTP_$code"
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // CancellationException extends IllegalStateException in the JVM, so the generic
            // catch below would swallow a cancelled coroutine and report it as a probe failure.
            throw cancelled
        } catch (e: Exception) {
            ServiceProbeResult(
                reachable = false,
                latencyMs = elapsedMs(startedAt),
                reason = e.javaClass.simpleName
            )
        } finally {
            connection?.disconnect()
        }
    }

    /** Reads up to [DRAIN_LIMIT_BYTES] and closes the stream so the socket is not reset. */
    private suspend fun drain(connection: HttpURLConnection, method: String, code: Int) {
        if (method != "GET") return
        val stream = if (code in 200..399) {
            runCatching { connection.inputStream }.getOrNull()
        } else {
            runCatching { connection.errorStream }.getOrNull()
        }
        stream?.use { input ->
            // runInterruptible lets cancellation break a blocking read instead of waiting
            // for the socket read timeout.
            runInterruptible(Dispatchers.IO) {
                val buffer = ByteArray(512)
                var total = 0
                while (total < DRAIN_LIMIT_BYTES) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                }
            }
        }
    }

    private fun elapsedMs(startedAt: Long): Long =
        ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
}
