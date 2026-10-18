package com.vpnproxy.smartconnect

import android.content.Context
import android.util.Log
import com.vpnproxy.core.singbox.SingboxConfigBuilder
import com.vpnproxy.core.singbox.SingboxProxyClient
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.service.VpnProxyService
import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Deep probe runs entirely on Sing-box in proxy-only mode. It refuses to replace an active VPN
 * configuration and always closes the temporary CommandServer after collecting measurements.
 */
@Singleton
class DeepProbeEngine @Inject constructor(
    private val healthChecker: HealthChecker,
    private val logCollector: LogCollector,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "DeepProbe"
        private const val LATENCY_URL = "https://www.gstatic.com/generate_204"
        private const val SPEED_URL = "https://speed.cloudflare.com/__down?bytes=100000"
        private const val LATENCY_SAMPLES = 4
        private const val CONNECT_TIMEOUT_MS = 2_000L
        private const val READ_TIMEOUT_MS = 4_000L

        fun scoreMetrics(metrics: List<ProbeMetrics>): List<ProbeMetrics> {
            if (metrics.isEmpty()) return emptyList()
            val latency = metrics.map { it.medianLatencyMs.toDouble() }
            val speed = metrics.map { it.speedMbps }
            val jitter = metrics.map { it.jitterMs.toDouble() }
            val loss = metrics.map { it.lossRate }
            return metrics.map { m ->
                val latencyScore = lowerIsBetter(m.medianLatencyMs.toDouble(), latency)
                val speedScore = higherIsBetter(m.speedMbps, speed)
                val jitterScore = lowerIsBetter(m.jitterMs.toDouble(), jitter)
                val lossScore = lowerIsBetter(m.lossRate, loss)
                m.copy(
                    latencyScore = latencyScore,
                    speedScore = speedScore,
                    jitterScore = jitterScore,
                    lossScore = lossScore,
                    normalizedScore = (0.4 * latencyScore + 0.3 * speedScore +
                        0.2 * jitterScore + 0.1 * lossScore).coerceIn(0.0, 1.0)
                )
            }.sortedByDescending { it.normalizedScore }
        }

        private fun lowerIsBetter(value: Double, values: List<Double>): Double {
            val minValue = values.minOrNull() ?: return 1.0
            val maxValue = values.maxOrNull() ?: return 1.0
            if (maxValue <= minValue) return 1.0
            return ((maxValue - value) / (maxValue - minValue)).coerceIn(0.0, 1.0)
        }

        private fun higherIsBetter(value: Double, values: List<Double>): Double {
            val minValue = values.minOrNull() ?: return 1.0
            val maxValue = values.maxOrNull() ?: return 1.0
            if (maxValue <= minValue) return 1.0
            return ((value - minValue) / (maxValue - minValue)).coerceIn(0.0, 1.0)
        }
    }

    data class ProbeMetrics(
        val config: ParsedConfig,
        val medianLatencyMs: Long,
        val speedMbps: Double,
        val jitterMs: Long,
        val lossRate: Double,
        val healthOk: Boolean,
        val latencyScore: Double = 0.0,
        val speedScore: Double = 0.0,
        val jitterScore: Double = 0.0,
        val lossScore: Double = 0.0,
        val normalizedScore: Double = 0.0
    )

    suspend fun run(configs: List<ParsedConfig>): List<ProbeMetrics> = withContext(Dispatchers.IO) {
        check(!VpnProxyService.isRunning.value) { "Отключите VPN для глубокого анализа" }
        require(configs.isNotEmpty()) { "Нет серверов для глубокого анализа" }

        val ports = allocatePorts(configs.size)
        val configJson = SingboxConfigBuilder.buildMulti(configs, ports)
        check(!VpnProxyService.isRunning.value) { "Отключите VPN для глубокого анализа" }
        logCollector.info(TAG, "Starting temporary Sing-box proxy core for ${configs.size} candidates")
        check(SingboxProxyClient.start(context, configJson)) {
            "Не удалось запустить Sing-box для глубокого анализа"
        }

        try {
            val results = configs.mapIndexed { index, config ->
                try {
                    val client = OkHttpClient.Builder()
                        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", ports[index])))
                        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .callTimeout(6, TimeUnit.SECONDS)
                        .retryOnConnectionFailure(false)
                        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
                        .build()

                    val samples = mutableListOf<Long>()
                    var failures = 0
                    repeat(LATENCY_SAMPLES) {
                        try {
                            val request = Request.Builder().url(LATENCY_URL).head().build()
                            val start = System.nanoTime()
                            client.newCall(request).execute().use { response ->
                                val elapsed = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
                                if (response.code in 200..399) samples += elapsed else failures++
                            }
                        } catch (_: Exception) {
                            failures++
                        }
                    }

                    val median = if (samples.isEmpty()) Long.MAX_VALUE else samples.sorted()[samples.size / 2]
                    val jitter = if (samples.size < 2) Long.MAX_VALUE else samples.maxOrNull()!! - samples.minOrNull()!!
                    var speed = 0.0
                    var speedOk = false
                    try {
                        val request = Request.Builder().url(SPEED_URL).header("User-Agent", "VPNProxy/1.0").build()
                        val start = System.nanoTime()
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val bytes = response.body?.bytes()?.size ?: 0
                                val elapsedMs = ((System.nanoTime() - start) / 1_000_000.0).coerceAtLeast(1.0)
                                speed = bytes * 8.0 / 1_000_000.0 / (elapsedMs / 1000.0)
                                speedOk = bytes > 0
                            }
                        }
                    } catch (_: Exception) {
                        speedOk = false
                    }
                    ProbeMetrics(
                        config = config,
                        medianLatencyMs = median,
                        speedMbps = speed,
                        jitterMs = jitter,
                        lossRate = failures.toDouble() / LATENCY_SAMPLES,
                        healthOk = samples.isNotEmpty() && speedOk
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    Log.w(TAG, "Probe failed for ${config.name}", t)
                    ProbeMetrics(config, Long.MAX_VALUE, 0.0, Long.MAX_VALUE, 1.0, false)
                }
            }
            scoreMetrics(results)
        } finally {
            SingboxProxyClient.stop(context)
            logCollector.info(TAG, "Temporary Sing-box deep probe stopped")
        }
    }

    private fun allocatePorts(count: Int): List<Int> {
        val ports = LinkedHashSet<Int>()
        repeat(count) {
            ServerSocket(0).use { socket -> ports += socket.localPort }
        }
        return ports.toList()
    }
}
