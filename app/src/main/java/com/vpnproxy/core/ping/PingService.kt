package com.vpnproxy.core.ping

import android.content.Context
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PingService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val HTTP_TARGETS = listOf(
            "https://www.google.com/generate_204",
            "https://cloudflare.com/cdn-cgi/trace",
            "https://1.1.1.1"
        )
        const val DEFAULT_TIMEOUT_MS = 3000
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(DEFAULT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(DEFAULT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout(DEFAULT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    data class PingResult(
        val target: String,
        val rttMs: Long,
        val success: Boolean,
        val method: Method,
        val error: String? = null
    )

    enum class Method { ICMP, HTTP, TCP }

    suspend fun httpPing(target: String = HTTP_TARGETS[0], retries: Int = 2): PingResult =
        withContext(Dispatchers.IO) {
            var lastResult: PingResult? = null
            repeat(retries + 1) { attempt ->
                val result = singleHttpPing(target)
                if (result.success) return@withContext result
                lastResult = result
                if (attempt < retries) delay(300L * (attempt + 1))
            }
            lastResult ?: PingResult(target, -1, false, Method.HTTP, "no attempts")
        }

    private suspend fun singleHttpPing(target: String): PingResult = withContext(Dispatchers.IO) {
        val start = SystemClock.elapsedRealtime()
        try {
            val request = Request.Builder().url(target).head()
                .header("User-Agent", "VPNProxy/1.0")
                .header("Cache-Control", "no-cache").build()
            httpClient.newCall(request).execute().use { response ->
                val rtt = SystemClock.elapsedRealtime() - start
                val ok = response.isSuccessful || response.code == 204
                PingResult(target, rtt, ok, Method.HTTP, if (ok) null else "HTTP ${response.code}")
            }
        } catch (e: Exception) {
            PingResult(target, -1, false, Method.HTTP, e.message ?: e::class.simpleName)
        }
    }

    /** Legacy API retained for existing callers. */
    suspend fun pingDefault(): PingResult {
        val http = httpPing()
        if (http.success) return http
        val cf = httpPing(HTTP_TARGETS[1], retries = 0)
        if (cf.success) return cf
        val icmp = icmpPing("8.8.8.8")
        if (icmp.success) return icmp
        val tcp1 = tcpPing("8.8.8.8", 53)
        if (tcp1.success) return tcp1
        return tcpPing("1.1.1.1", 53)
    }

    suspend fun icmpPing(host: String, count: Int = 2): PingResult = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("/system/bin/ping", "-c", count.toString(), "-W", "2", host)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(4, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return@withContext PingResult(host, -1, false, Method.ICMP, "timeout")
            }
            val match = Regex("""=\s*[\d.]+/([\d.]+)/""").find(output)
            val avgMs = match?.groupValues?.get(1)?.toFloatOrNull()?.toLong()
            if (avgMs != null) PingResult(host, avgMs, true, Method.ICMP)
            else PingResult(host, -1, false, Method.ICMP, "no rtt line")
        } catch (e: Exception) {
            PingResult(host, -1, false, Method.ICMP, e.message)
        }
    }

    suspend fun tcpPing(host: String, port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): PingResult =
        withContext(Dispatchers.IO) {
            val start = SystemClock.elapsedRealtime()
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs.coerceAtLeast(1))
                    PingResult("$host:$port", SystemClock.elapsedRealtime() - start, true, Method.TCP)
                }
            } catch (e: Exception) {
                PingResult("$host:$port", -1, false, Method.TCP, e.message)
            }
        }

    suspend fun pingAllTargets(): List<PingResult> = HTTP_TARGETS.map { httpPing(it, retries = 0) }

    suspend fun pingAll(
        targets: List<PingTarget> = PingTargets.forRegion(PingTargets.detectRegion(context)),
        concurrency: Int = 10,
        timeoutMs: Long = 2000L
    ): MultiPingResult {
        val region = PingTargets.detectRegion(context)
        if (targets.isEmpty()) return MultiPingResult(emptyList(), region, SystemClock.elapsedRealtime())
        require(concurrency > 0) { "concurrency must be greater than zero" }
        require(timeoutMs > 0) { "timeoutMs must be greater than zero" }
        val semaphore = Semaphore(concurrency)
        val results = coroutineScope {
            targets.map { target ->
                async(Dispatchers.IO) { semaphore.withPermit { pingOne(target, timeoutMs) } }
            }.awaitAll()
        }
        return MultiPingResult(results, region, SystemClock.elapsedRealtime())
    }

    private suspend fun pingOne(target: PingTarget, timeoutMs: Long): TargetResult {
        val timestamp = SystemClock.elapsedRealtime()
        return try {
            val result = withTimeoutOrNull(timeoutMs) {
                val addresses = withTimeoutOrNull(1000L) {
                    withContext(Dispatchers.IO) {
                        runCatching { InetAddress.getAllByName(target.host) }.getOrElse { emptyArray() }
                    }
                }
                val address = addresses?.firstOrNull { it is Inet4Address } as? Inet4Address
                if (address == null) {
                    TargetResult(target.id, null, false, if (addresses == null) "dns_timeout" else "dns_fail", timestamp)
                } else {
                    var socket: Socket? = null
                    try {
                        socket = Socket()
                        val start = SystemClock.elapsedRealtime()
                        socket.connect(InetSocketAddress(address, target.port), timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt())
                        TargetResult(target.id, SystemClock.elapsedRealtime() - start, true, timestamp = timestamp)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        TargetResult(target.id, null, false, e.message ?: "connect_fail", timestamp)
                    } finally {
                        runCatching { socket?.close() }
                    }
                }
            }
            result ?: TargetResult(target.id, null, false, "timeout", timestamp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TargetResult(target.id, null, false, e.message ?: e::class.simpleName ?: "error", timestamp)
        }
    }
}
