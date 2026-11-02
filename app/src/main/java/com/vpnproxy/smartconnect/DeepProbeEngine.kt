package com.vpnproxy.smartconnect

import android.content.Context
import android.util.Log
import com.vpnproxy.core.singbox.SingboxConfigBuilder
import com.vpnproxy.core.singbox.CorePortLease
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
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
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

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
        // Independent endpoints avoid declaring every profile dead when one probe host
        // is blocked or degraded on the current access network.
        private val PROFILE_PING_URLS = listOf(
            "https://cp.cloudflare.com/generate_204",
            "https://connectivitycheck.gstatic.com/generate_204"
        )
        private const val SPEED_URL = "https://proof.ovh.net/files/1Mb.dat"
        private const val LATENCY_SAMPLES = 4
        private const val PROFILE_PING_ATTEMPTS = 4
        private const val CONNECT_TIMEOUT_MS = 3_500L
        private const val READ_TIMEOUT_MS = 5_000L

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
    private val cancellationGeneration = AtomicLong(0L)

    /** Cancel the active probe without releasing its slot before its coroutine has exited. */
    fun cancel() {
        cancellationGeneration.incrementAndGet()
        if (running.get()) {
            runCatching { SingboxProxyClient.stop(context) }
            logCollector.info(TAG, "DeepProbe cancellation requested — temporary core stopped")
        } else {
            logCollector.info(TAG, "DeepProbe cancellation generation advanced (no active probe)")
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
        val probeGeneration = cancellationGeneration.get()
        awaitProbeSlot()
        CorePortLease.mutex.withLock {
        try {
            checkProbeActive(probeGeneration)
            check(!VpnProxyService.isRunning.value && !VpnProxyService.isCoreTransitioning.value) {
                "Проверка отдельных профилей доступна только при полностью остановленном VPN"
            }
            checkProbeActive(probeGeneration)
            val ports = allocatePorts(configs.size)
            val configJson = SingboxConfigBuilder.buildMulti(configs, ports)
            checkProbeActive(probeGeneration)
            check(!VpnProxyService.isRunning.value && !VpnProxyService.isCoreTransitioning.value) {
                "VPN начал запускаться до начала проверки профилей"
            }
            checkProbeActive(probeGeneration)
            val started = SingboxProxyClient.start(context, configJson)
            check(started) { "Sing-box failed to start for profile ping" }
            logCollector.info(TAG, "Profile probe session started: generation=$probeGeneration profiles=${configs.size} attemptsPerProfile=$PROFILE_PING_ATTEMPTS requiredSuccessSamples=2 concurrency=8 endpoints=${PROFILE_PING_URLS.size}")

            try {
                val output = mutableListOf<ProfilePingResult>()
                val semaphore = Semaphore(8)
                for (indices in configs.indices.toList().chunked(8)) {
                    checkProbeActive(probeGeneration)
                    val batch = coroutineScope {
                        indices.map { index ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    val client = OkHttpClient.Builder()
                                        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", ports[index])))
                                        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                                        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                                        .callTimeout(7, TimeUnit.SECONDS)
                                        .retryOnConnectionFailure(false)
                                        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
                                        .build()
                                    val samples = mutableListOf<Long>()
                                    for (attempt in 0 until PROFILE_PING_ATTEMPTS) {
                                        if (samples.size >= 2 || samples.size + (PROFILE_PING_ATTEMPTS - attempt) < 2) break
                                        checkProbeActive(probeGeneration)
                                        try {
                                            val probeUrl = PROFILE_PING_URLS[attempt % PROFILE_PING_URLS.size]
                                            val request = Request.Builder().url(probeUrl).get().build()
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
                                        } catch (failure: Exception) {
                                            logCollector.warn(
                                                TAG,
                                                "Profile probe failed: profile=${configs[index].name} attempt=${attempt + 1} " +
                                                    "endpoint=${PROFILE_PING_URLS[attempt % PROFILE_PING_URLS.size]} " +
                                                    "reason=${failure.javaClass.simpleName}: ${failure.message}"
                                            )
                                        }
                                    }
                                    val result = summarizeProfileLatency(configs[index], samples)
                                    logCollector.info(
                                        TAG,
                                        "Profile probe result: generation=$probeGeneration profile=${configs[index].name} protocol=${configs[index].transport} security=${configs[index].security} success=${result.success} successfulSamples=${result.successfulSamples}/$PROFILE_PING_ATTEMPTS medianRttMs=${result.latencyMs}"
                                    )
                                    result
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
                val succeeded = output.count { it.success }
                logCollector.info(TAG, "Profile probe session complete: generation=$probeGeneration total=${output.size} succeeded=$succeeded failed=${output.size - succeeded}")
                return@withContext output
            } finally {
                SingboxProxyClient.stop(context)
                logCollector.info(TAG, "Temporary Sing-box profile-ping core stopped")
            }
        } finally {
            running.set(false)
        }
        }
    }

    suspend fun run(configs: List<ParsedConfig>): List<ProbeMetrics> = withContext(Dispatchers.IO) {
        if (configs.isEmpty()) return@withContext emptyList()
        val probeGeneration = cancellationGeneration.get()
        awaitProbeSlot()
        CorePortLease.mutex.withLock {
        try {
            checkProbeActive(probeGeneration)
            check(!VpnProxyService.isRunning.value && !VpnProxyService.isCoreTransitioning.value) { "Отключите VPN и дождитесь полной остановки ядра для глубокого анализа" }
            require(configs.isNotEmpty()) { "Нет серверов для глубокого анализа" }
            checkProbeActive(probeGeneration)

            val ports = allocatePorts(configs.size)
            val configJson = SingboxConfigBuilder.buildMulti(configs, ports)
            checkProbeActive(probeGeneration)
            check(!VpnProxyService.isRunning.value && !VpnProxyService.isCoreTransitioning.value) { "Отключите VPN и дождитесь полной остановки ядра для глубокого анализа" }
            checkProbeActive(probeGeneration)
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
                    checkProbeActive(probeGeneration)
                    try {
                        val client = OkHttpClient.Builder()
                            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", ports[index])))
                            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .callTimeout(8, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(false)
                            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
                            .build()

                        val samples = mutableListOf<Long>()
                        var failures = 0
                        repeat(LATENCY_SAMPLES) {
                            checkProbeActive(probeGeneration)
                            try {
                                val request = Request.Builder().url(LATENCY_URL).head().build()
                                val start = System.nanoTime()
                                client.newCall(request).execute().use { response ->
                                    val elapsed = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
                                    if (response.code in 200..399) samples += elapsed else failures++
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                failures++
                                logCollector.warn(TAG, "Latency probe failed: profile=${config.name} reason=${failure.javaClass.simpleName}: ${failure.message}")
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
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            speedOk = false
                            logCollector.warn(TAG, "Speed probe failed: profile=${config.name} reason=${failure.javaClass.simpleName}: ${failure.message}")
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
    }

    /** Wait for the single temporary Sing-box core slot instead of fabricating failed results. */
    private suspend fun awaitProbeSlot() {
        while (!running.compareAndSet(false, true)) {
            currentCoroutineContext().ensureActive()
            delay(50L)
        }
    }

    /** Propagate coroutine cancellation and explicit DeepProbe cancellation to every probe path. */
    private suspend fun checkProbeActive(generation: Long) {
        currentCoroutineContext().ensureActive()
        if (cancellationGeneration.get() != generation) {
            throw CancellationException("DeepProbe cancelled")
        }
    }

    /**
     * Reserve all ephemeral ports at the same time. Closing each ServerSocket inside
     * the loop allowed the OS to immediately hand the same port to the next iteration.
     * Sing-box requires one distinct inbound port per profile.
     */
    private fun allocatePorts(count: Int): List<Int> {
        require(count > 0) { "At least one inbound port is required" }
        val reservations = ArrayList<ServerSocket>(count)
        try {
            repeat(count) {
                reservations += ServerSocket().apply {
                    reuseAddress = false
                    bind(InetSocketAddress("127.0.0.1", 0))
                }
            }
            val ports = reservations.map { it.localPort }
            check(ports.distinct().size == count) {
                "Failed to reserve $count distinct inbound ports"
            }
            return ports
        } finally {
            reservations.forEach { socket -> runCatching { socket.close() } }
        }
    }
}
