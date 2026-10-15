package com.vpnproxy.core.ping

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PingService @Inject constructor() {

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

    /**
     * HTTP-пинг с retry: если первая попытка упала,
     * повторяем с backoff (300ms, 600ms).
     */
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

    private suspend fun singleHttpPing(target: String): PingResult =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            try {
                val request = Request.Builder()
                    .url(target)
                    .head()
                    .header("User-Agent", "VPNProxy/1.0")
                    .header("Cache-Control", "no-cache")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val rtt = System.currentTimeMillis() - start
                    val ok = response.isSuccessful || response.code == 204
                    PingResult(
                        target, rtt, ok, Method.HTTP,
                        error = if (ok) null else "HTTP ${response.code}"
                    )
                }
            } catch (e: Exception) {
                PingResult(target, -1, false, Method.HTTP, e.message ?: e::class.simpleName)
            }
        }

    /**
     * Комбинированный пинг:
     * HTTP Google → HTTP Cloudflare → ICMP 8.8.8.8 → TCP 8.8.8.8:53 → TCP 1.1.1.1:53
     */
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

    suspend fun icmpPing(host: String, count: Int = 2): PingResult =
        withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder(
                    "/system/bin/ping", "-c", count.toString(), "-W", "2", host
                ).redirectErrorStream(true).start()

                val output = process.inputStream.bufferedReader().readText()
                val finished = process.waitFor(4, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroy()
                    return@withContext PingResult(host, -1, false, Method.ICMP, "timeout")
                }

                val rttRegex = Regex("""=\s*[\d.]+/([\d.]+)/""")
                val match = rttRegex.find(output)
                val avgMs = match?.groupValues?.get(1)?.toFloatOrNull()?.toLong()

                if (avgMs != null) PingResult(host, avgMs, true, Method.ICMP)
                else PingResult(host, -1, false, Method.ICMP, "no rtt line")
            } catch (e: Exception) {
                PingResult(host, -1, false, Method.ICMP, e.message)
            }
        }

    suspend fun tcpPing(
        host: String,
        port: Int,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): PingResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                PingResult("$host:$port", System.currentTimeMillis() - start, true, Method.TCP)
            }
        } catch (e: Exception) {
            PingResult("$host:$port", -1, false, Method.TCP, e.message)
        }
    }

    suspend fun pingAllTargets(): List<PingResult> =
        HTTP_TARGETS.map { httpPing(it, retries = 0) }
}
