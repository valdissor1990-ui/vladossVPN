package com.vpnproxy.core.singbox

import com.vpnproxy.logging.LogCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.HttpURLConnection
import java.net.URL
import android.os.SystemClock

data class TrafficProbeResult(val success: Boolean, val endpoint: String, val status: Int?, val elapsedMs: Long, val detail: String) {
    override fun toString(): String =
        "success=$success endpoint=$endpoint status=${status ?: "n/a"} elapsedMs=$elapsedMs $detail"
}

object DiagnosticProbe {
    private val endpoints = listOf(
        "http://cp.cloudflare.com/generate_204",
        "http://neverssl.com",
        "https://www.gstatic.com/generate_204"
    )

    suspend fun runOnce(serverName: String, logCollector: LogCollector): TrafficProbeResult = withContext(Dispatchers.IO) {
        var last = TrafficProbeResult(false, endpoints.first(), null, 0, "no endpoint attempted")
        for (endpoint in endpoints) {
            val started = SystemClock.elapsedRealtime()
            logCollector.info("PassiveProbe", "PassiveProbe start server=$serverName proxy=http://127.0.0.1:10809 endpoint=$endpoint")
            var connection: HttpURLConnection? = null
            try {
                val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", SingboxConfigBuilder.PROXY_PORT))
                connection = URL(endpoint).openConnection(proxy) as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.connect()
                val status = connection.responseCode
                val elapsed = SystemClock.elapsedRealtime() - started
                val success = status in 200..399
                last = TrafficProbeResult(success, endpoint, status, elapsed, "")
                logCollector.info("PassiveProbe", "PassiveProbe success=$success endpoint=$endpoint status=$status elapsedMs=$elapsed")
                if (success) return@withContext last
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val elapsed = SystemClock.elapsedRealtime() - started
                val message = t.message.orEmpty().replace('\n', ' ')
                last = TrafficProbeResult(false, endpoint, null, elapsed, "exception=${t.javaClass.name} message=$message")
                logCollector.warn("PassiveProbe", "PassiveProbe success=false endpoint=$endpoint elapsedMs=$elapsed exception=${t.javaClass.name} message=$message")
            } finally {
                connection?.disconnect()
            }
        }
        last
    }
}
