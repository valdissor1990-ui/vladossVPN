package com.vpnproxy.core.ping

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Пинг-сервис.
 *
 * Три метода измерения RTT:
 * 1. pingHost() — ICMP через системный /system/bin/ping (если доступен)
 * 2. httpPing() — HTTP HEAD к google.com/generate_204 (работает всегда, даже за NAT)
 * 3. tcpPing() — TCP connect к host:port
 */
@Singleton
class PingService @Inject constructor() {

    companion object {
        private const val TAG = "PingService"

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
     * HTTP-пинг Google: HEAD/GET к generate_204.
     * Работает поверх VPN и без VPN.
     */
    suspend fun httpPing(target: String = HTTP_TARGETS[0]): PingResult =
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
                PingResult(target, -1, false, Method.HTTP, e.message)
            }
        }

    suspend fun icmpPing(host: String, count: Int = 3): PingResult =
        withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder(
                    "/system/bin/ping", "-c", count.toString(),
                    "-W", "2", host
                ).redirectErrorStream(true).start()

                val output = process.inputStream.bufferedReader().readText()
                val finished = process.waitFor(5, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroy()
                    return@withContext PingResult(host, -1, false, Method.ICMP, "timeout")
                }

                val rttRegex = Regex("""=\\s*[\\d.]+/([\\d.]+)/""")
                val match = rttRegex.find(output)
                val avgMs = match?.groupValues?.get(1)?.toFloatOrNull()?.toLong()

                if (avgMs != null) {
                    PingResult(host, avgMs, true, Method.ICMP)
                } else {
                    PingResult(host, -1, false, Method.ICMP, "parse failed")
                }
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
                val rtt = System.currentTimeMillis() - start
                PingResult("$host:$port", rtt, true, Method.TCP)
            }
        } catch (e: Exception) {
            PingResult("$host:$port", -1, false, Method.TCP, e.message)
        }
    }

    suspend fun pingDefault(): PingResult {
        val http = httpPing()
        if (http.success) return http

        val icmp = icmpPing("8.8.8.8")
        if (icmp.success) return icmp

        return tcpPing("8.8.8.8", 53)
    }

    suspend fun pingAllTargets(): List<PingResult> = coroutineScope {
        HTTP_TARGETS.map { target ->
            async(Dispatchers.IO) { httpPing(target) }
        }.awaitAll()
    }
}
