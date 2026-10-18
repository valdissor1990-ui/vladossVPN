package com.vpnproxy.smartconnect

import android.util.Log
import com.vpnproxy.core.xray.XrayCore
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.service.VpnProxyService
import com.vpnproxy.smartconnect.HealthChecker.LatencyResult
import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.Proxy
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Multi-dimensional probing through one temporary Xray instance. This is a regular
 * application component, not an Android Service. It must never run alongside the VPN TUN.
 */
@Singleton
class DeepProbeEngine @Inject constructor(
    private val healthChecker: HealthChecker,
    private val xrayCore: XrayCore,
    private val logCollector: LogCollector
) {
    companion object {
        private const val TAG = "DeepProbe"
        private const val TOP_COUNT = 5
        private const val FAST_FILTER_ROUNDS = 3
        private const val SOCKS_PORT_BASE = 10900
        private const val DOWNLOAD_BYTES = 100_000L
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val REQUEST_TIMEOUT_MS = 3_000L
        private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=100000"

        /** Pure scoring helper, also exercised by unit tests. Scores are always in [0, 1]. */
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
                val score = (0.4 * latencyScore) + (0.3 * speedScore) +
                    (0.2 * jitterScore) + (0.1 * lossScore)
                m.copy(
                    latencyScore = latencyScore,
                    speedScore = speedScore,
                    jitterScore = jitterScore,
                    lossScore = lossScore,
                    normalizedScore = score.coerceIn(0.0, 1.0)
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

    private data class FastCandidate(val config: ParsedConfig, val medianLatencyMs: Long)
    private data class SampleResult(val metrics: ProbeMetrics, val port: Int)

    suspend fun run(configs: List<ParsedConfig>): List<ProbeMetrics> {
        check(!VpnProxyService.isRunning.value && !xrayCore.isRunning()) {
            "Отключите VPN для глубокого анализа"
        }
        require(configs.isNotEmpty()) { "Нет серверов для глубокого анализа" }
        check(xrayCore.tryAcquireDeepProbeLease()) {
            "Отключите VPN для глубокого анализа"
        }

        try {
            val candidates = fastFilter(configs).take(TOP_COUNT)
            check(candidates.isNotEmpty()) { "Не удалось найти доступные серверы для глубокого анализа" }

            // All ServerSockets are closed before Xray starts. Ports are distinct and never use 10808.
            val ports = allocatePorts(candidates.size)
            val configJson = buildMultiServerConfig(candidates, ports)
            logCollector.info(TAG, "Starting one bare Xray instance for ${candidates.size} servers; ports=${ports.joinToString()}")
            var startAttempted = false
            try {
                check(!VpnProxyService.isRunning.value) { "Отключите VPN для глубокого анализа" }
                startAttempted = true
                val started = xrayCore.start(configJson, tunFd = 0)
                check(started) { "Не удалось запустить Xray для глубокого анализа" }

                val results = mutableListOf<ProbeMetrics>()
                // Deliberately sequential: each server's measurements finish before the next begins.
                candidates.forEachIndexed { index, candidate ->
                    results += probeOne(candidate, ports[index])
                }
                val scored = scoreMetrics(results)
                logCollector.info(TAG, "Deep probe complete: ${scored.size} servers scored")
                return scored
            } finally {
                // Exactly one explicit stop call after the single temporary Xray start attempt.
                if (startAttempted) {
                    val stopped = xrayCore.stop()
                    logCollector.info(TAG, "Deep-probe Xray stopped=$stopped")
                }
            }
        } finally {
            xrayCore.releaseDeepProbeLease()
        }
    }

    private suspend fun fastFilter(configs: List<ParsedConfig>): List<FastCandidate> {
        val samples = configs.associateWith { mutableListOf<Long>() }
        repeat(FAST_FILTER_ROUNDS) {
            check(!VpnProxyService.isRunning.value && !xrayCore.isRunning()) {
                "Отключите VPN для глубокого анализа"
            }
            val round: List<LatencyResult> = healthChecker.testLatency(configs)
            round.forEach { result ->
                if (result.success && result.latencyMs > 0L && result.latencyMs < Long.MAX_VALUE) {
                    samples[result.config]?.add(result.latencyMs)
                }
            }
        }
        return samples.mapNotNull { (config, times) ->
            if (times.isEmpty()) null else FastCandidate(config, median(times))
        }.sortedBy { it.medianLatencyMs }
    }

    private fun allocatePorts(count: Int): List<Int> {
        val ports = linkedSetOf<Int>()
        var attempts = 0
        while (ports.size < count && attempts < count * 30) {
            attempts++
            // Port 0 asks the OS for an available ephemeral port (normally >= 32768).
            val port = ServerSocket(0).use { it.localPort }
            if (port >= SOCKS_PORT_BASE && port != 10808 && port !in ports) ports += port
        }
        check(ports.size == count) { "Не удалось выделить свободные SOCKS-порты от $SOCKS_PORT_BASE" }
        return ports.toList()
    }

    private fun buildMultiServerConfig(candidates: List<FastCandidate>, ports: List<Int>): String {
        val inbounds = JSONArray()
        val outbounds = JSONArray()
        val rules = JSONArray()
        candidates.forEachIndexed { index, candidate ->
            val inboundTag = "deep-in-$index"
            val outboundTag = "deep-out-$index"
            inbounds.put(JSONObject().apply {
                put("tag", inboundTag)
                put("listen", "127.0.0.1")
                put("port", ports[index])
                put("protocol", "socks")
                put("settings", JSONObject().put("auth", "noauth").put("udp", false))
            })
            val outbound = JSONObject(candidate.config.toXrayOutbound().toString())
                .put("tag", outboundTag)
            outbounds.put(outbound)
            rules.put(JSONObject().apply {
                put("type", "field")
                put("inboundTag", JSONArray().put(inboundTag))
                put("outboundTag", outboundTag)
            })
        }
        return JSONObject().apply {
            put("log", JSONObject().put("loglevel", "warning"))
            put("inbounds", inbounds)
            put("outbounds", outbounds)
            put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
        }.toString()
    }

    private suspend fun probeOne(candidate: FastCandidate, port: Int): ProbeMetrics =
        withContext(Dispatchers.IO) {
            val rtts = mutableListOf<Long>()
            repeat(5) {
                try {
                    val start = System.nanoTime()
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)
                    }
                    rtts += ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
                } catch (e: Exception) {
                    Log.w(TAG, "SOCKS TCP probe failed port=$port", e)
                }
            }
            val lossRate = (5 - rtts.size) / 5.0
            val latency = if (rtts.isEmpty()) CONNECT_TIMEOUT_MS.toLong() else median(rtts)
            val jitter = if (rtts.size < 2) CONNECT_TIMEOUT_MS.toLong() else (rtts.maxOrNull()!! - rtts.minOrNull()!!)

            val client = OkHttpClient.Builder()
                .proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
                .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
                .cache(null)
                .connectTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build()
            var speedMbps = 0.0
            var downloadOk = false
            try {
                val request = Request.Builder().url(DOWNLOAD_URL)
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", "VPNProxy-DeepProbe/2")
                    .build()
                val start = System.nanoTime()
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Download HTTP ${response.code}" }
                    val stream = response.body?.byteStream() ?: error("Download response body is empty")
                    var total = 0L
                    stream.use { input ->
                        val buffer = ByteArray(8 * 1024)
                        while (total < DOWNLOAD_BYTES) {
                            val read = input.read(buffer, 0, min(buffer.size.toLong(), DOWNLOAD_BYTES - total).toInt())
                            if (read < 0) break
                            total += read
                        }
                    }
                    val elapsedMs = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
                    speedMbps = total * 8.0 / 1_000_000.0 / (elapsedMs / 1_000.0)
                    downloadOk = total >= DOWNLOAD_BYTES
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download probe failed for ${candidate.config.name}", e)
            }

            val healthOk = try {
                probeDnsOverTcp(port)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "DNS health probe to 1.1.1.1:53 failed for ${candidate.config.name}", e)
                false
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }

            logCollector.info(
                TAG,
                "Probe ${candidate.config.name}: latency=${latency}ms jitter=${jitter}ms loss=${lossRate} speed=${"%.3f".format(java.util.Locale.US, speedMbps)}Mbps health=$healthOk"
            )
            ProbeMetrics(
                config = candidate.config,
                medianLatencyMs = latency,
                speedMbps = if (downloadOk) speedMbps else 0.0,
                jitterMs = jitter,
                lossRate = lossRate,
                healthOk = healthOk
            )
        }

    /** DNS-over-TCP health query through this server's local SOCKS inbound. */
    private fun probeDnsOverTcp(port: Int): Boolean {
        val query = buildDnsQuery("example.com")
        return Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))).use { socket ->
            socket.soTimeout = REQUEST_TIMEOUT_MS.toInt()
            socket.connect(InetSocketAddress("1.1.1.1", 53), CONNECT_TIMEOUT_MS)
            val output = DataOutputStream(socket.getOutputStream())
            output.writeShort(query.size)
            output.write(query)
            output.flush()
            val input = DataInputStream(socket.getInputStream())
            val responseLength = input.readUnsignedShort()
            if (responseLength < 12) return@use false
            val response = ByteArray(responseLength)
            input.readFully(response)
            val flags = ((response[2].toInt() and 0xff) shl 8) or (response[3].toInt() and 0xff)
            (flags and 0x8000) != 0 && (flags and 0x000f) == 0
        }
    }

    private fun buildDnsQuery(domain: String): ByteArray {
        val output = ByteArrayOutputStream()
        val id = SecureRandom().nextInt(65_536)
        output.write(byteArrayOf(
            ((id shr 8) and 0xff).toByte(), (id and 0xff).toByte(),
            0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        ))
        domain.split(".").forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            output.write(bytes.size)
            output.write(bytes)
        }
        output.write(0)
        output.write(byteArrayOf(0x00, 0x01, 0x00, 0x01)) // A / IN
        return output.toByteArray()
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
}
