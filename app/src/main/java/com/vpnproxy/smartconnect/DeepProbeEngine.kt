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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reserve all ephemeral ports at once. Closing each ServerSocket immediately after
 * reading its port can cause the OS to return that same port for the next profile.
 */
internal fun allocateProbePorts(count: Int): List<Int> {
    require(count > 0) { "At least one probe port is required" }
    val reservations = ArrayList<ServerSocket>(count)
    try {
        repeat(count) { reservations += ServerSocket(0) }
        val ports = reservations.map { it.localPort }
        check(ports.size == ports.toSet().size) {
            "OS returned duplicate ephemeral ports for profile probes"
        }
        return ports
    } finally {
        reservations.forEach { socket -> runCatching { socket.close() } }
    }
}

/**
 * Deep probe runs entirely on Sing-box in proxy-only mode. It refuses to replace an active VPN
 * configuration and always closes the temporary CommandServer after collecting measurements.
 */
@Singleton
class DeepProbeEngine @Inject constructor(
    private val logCollector: LogCollector,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "DeepProbe"
        private const val LATENCY_URL = "https://www.gstatic.com/generate_204"
        private const val SPEED_URL = "https://proof.ovh.net/files/1Mb.dat"
        private const val LATENCY_SAMPLES = 4
        private const val CONNECT_TIMEOUT_MS = 2_000L
        private const val READ_TIMEOUT_MS = 4_000L

        fun summarizeProfileLatency(config: ParsedConfig, samples: List<Long>): ProfilePingResult {
            val valid = samples.filter { it > 0L }.sorted()
            val success = valid.size >= 2
            val middle = valid.size / 2
            val median = when {
                !success -> Long.MAX_VALUE
                valid.size % 2 == 0 -> (valid[middle - 1] + valid[middle]) / 2L
                else -> valid[middle]
            }
            return ProfilePingResult(
                config = config,
                latencyMs = median,
                success = success,
                successfulSamples = valid.size
            )
        }
        fun scoreMetrics(metrics: List<ProbeMetrics>): List<ProbeMetrics> {
            if (metrics.isEmpty()) return emptyList()
            val latency = metrics.map { it.medianLatencyMs.toDouble() }
            val speed = metrics.map { it.speedMbps }
            val jitter = metrics.map { it.jitterMs.toDouble() }
            val loss = metrics.map { it.lossRate }
            return metrics.map { m ->
                if (!m.healthOk) {
                    return@map m.copy(
                        latencyScore = 0.0, speedScore = 0.0, jitterScore = 0.0,
                        lossScore = 0.0, normalizedScore = 0.0
                    )
                }
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

    private val running = AtomicBoolean(false)

    /** Cancel any in-flight probe; safe to call from service stop / disconnect. */
    fun cancel() {
        if (running.compareAndSet(true, false)) {
            runCatching { SingboxProxyClient.stop(context) }
            logCollector.info(TAG, "DeepProbe cancelled — temporary core stopped")
        }
    }

    data class ProfilePingResult(
        val config: ParsedConfig,
        val latencyMs: Long,
        val success: Boolean,
        val successfulSamples: Int
    )

    /**
     * Measures HTTP latency through each profile's own Sing-box outbound.
     * It does not use the currently active VPN route and refuses to run while the VPN is active.
     * One temporary core hosts isolated loopback inbounds; results are published in small batches.
     */
    suspend fun pingProfiles(
        configs: List<ParsedConfig>,
        stopAfterSuccessfulBatch: Boolean = false,
        stopAfterSuccessfulCount: Int? = null,
        onBatch: (List<ProfilePingResult>) -> Unit = {}
    ): List<ProfilePingResult> = withContext(Dispatchers.IO) {
        if (configs.isEmpty()) return@withContext emptyList()
        if (!running.compareAndSet(false, true)) {
            logCollector.warn(TAG, "Profile ping already running — skip")
            return@withContext configs.map { ProfilePingResult(it, Long.MAX_VALUE, false, 0) }
        }
        try {
            check(!VpnProxyService.isRunning.value) {
                "Проверка отдельных профилей доступна после отключения VPN"
            }
            ensureActive()
            val ports = allocatePorts(configs.size)
            val configJson = SingboxConfigBuilder.buildMulti(configs, ports)
            ensureActive()
            check(!VpnProxyService.isRunning.value) {
                "VPN стал активен до начала проверки профилей"
            }
            check(running.get() && isActive) { "Profile ping cancelled" }
            val started = SingboxProxyClient.start(context, configJson)
            check(started) { "Sing-box failed to start for profile ping" }

            try {
                val output = mutableListOf<ProfilePingResult>()
                val semaphore = Semaphore(8)
                for (indices in configs.indices.toList().chunked(8)) {
                    ensureActive()
                    if (!running.get()) throw CancellationException("Profile ping cancelled")
                    val batch = coroutineScope {
                        indices.map { index ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    val client = OkHttpClient.Builder()
                                        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", ports[index])))
                                        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                                        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                                        .callTimeout(3, TimeUnit.SECONDS)
                                        .retryOnConnectionFailure(false)
                                        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
                                        .build()
                                    val samples = mutableListOf<Long>()
                                    for (attempt in 0 until 3) {
                                        if (samples.size >= 2 || samples.size + (3 - attempt) < 2) break
                                        ensureActive()
                                        if (!running.get()) throw CancellationException("Profile ping cancelled")
                                        try {
                                            val request = Request.Builder().url(LATENCY_URL).head().build()
                                            val elapsed = runInterruptible(Dispatchers.IO) {
                                                val start = System.nanoTime()
                                                client.newCall(request).execute().use { response ->
                                                    if (response.code !in 200..399) error("HTTP ${response.code}")
                                                    ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
                                                }
                                            }
                                            samples += elapsed
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            // A failed request is not a numeric ping sample.
                                        }
                                    }
                                    summarizeProfileLatency(configs[index], samples)
                                }
                            }
                        }.awaitAll()
                    }
                    output += batch
                    onBatch(batch)
                    if (stopAfterSuccessfulBatch && batch.any { it.success }) break
                    if (stopAfterSuccessfulCount != null && output.count { it.success } >= stopAfterSuccessfulCount.coerceAtLeast(1)) {
                        logCollector.info(TAG, "Early scan stop: ${output.count { it.success }} reachable profiles found (target=$stopAfterSuccessfulCount)")
                        break
                    }
                }
                return@withContext output
            } finally {
                SingboxProxyClient.stop(context)
                logCollector.info(TAG, "Temporary Sing-box profile-ping core stopped")
            }
        } finally {
            running.set(false)
        }
    }

    suspend fun run(configs: List<ParsedConfig>): List<ProbeMetrics> = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(false, true)) {
            logCollector.warn(TAG, "DeepProbe already running — skip")
            return@withContext emptyList()
        }
        try {
            check(!VpnProxyService.isRunning.value) { "Отключите VPN для глубокого анализа" }
            require(configs.isNotEmpty()) { "Нет серверов для глубокого анализа" }
            ensureActive()

            val ports = allocatePorts(configs.size)
            val configJson = SingboxConfigBuilder.buildMulti(configs, ports)
            ensureActive()
            check(!VpnProxyService.isRunning.value) { "Отключите VPN для глубокого анализа" }
            if (!running.get() || !isActive) {
                logCollector.info(TAG, "DeepProbe aborted before core start")
                return@withContext emptyList()
            }
            logCollector.info(TAG, "Starting temporary Sing-box proxy core for ${configs.size} candidates")
            val started = try {
                SingboxProxyClient.start(context, configJson)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                logCollector.error(TAG, "Sing-box start failed for DeepProbe — no retry", t)
                return@withContext emptyList()
            }
            if (!started) {
                logCollector.error(TAG, "Sing-box failed to start for DeepProbe — no retry")
                return@withContext emptyList()
            }

            try {
                val results = configs.mapIndexed { index, config ->
                    ensureActive()
                    if (!running.get()) throw CancellationException("DeepProbe cancelled")
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
                            ensureActive()
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
                            healthOk = samples.size >= 2 && speedOk
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
        } finally {
            running.set(false)
        }
    }

    private fun allocatePorts(count: Int): List<Int> = allocateProbePorts(count)
}
