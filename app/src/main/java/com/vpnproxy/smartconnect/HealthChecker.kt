package com.vpnproxy.smartconnect

import android.content.Context
import com.vpnproxy.core.singbox.SingboxConfigBuilder
import com.vpnproxy.core.singbox.CorePortLease
import com.vpnproxy.core.singbox.SingboxProxyClient
import com.vpnproxy.service.VpnProxyService
import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Health checker for VLESS and UDP-based protocols.
 * Hysteria2/TUIC checks use Sing-box proxy mode when the VPN is down.
 */
@Singleton
class HealthChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkBucketProvider: NetworkBucketProvider
) {
    companion object {
        private const val TAG = "HealthChecker"
        private const val LATENCY_TIMEOUT_MS = 1500
        private const val LATENCY_TEST_URL = "https://www.gstatic.com/generate_204"
        private const val SPEED_TEST_URL = "https://proof.ovh.net/files/1Mb.dat"
        private const val MAX_CONCURRENCY = 10
    }

    data class LatencyResult(
        val config: ParsedConfig,
        val latencyMs: Long,
        val success: Boolean,
        val evidence: ProbeEvidence? = null
    )

    data class SpeedResult(
        val config: ParsedConfig,
        val speedMbps: Float,
        val success: Boolean,
        val evidence: ProbeEvidence? = null
    )

    fun currentNetworkSnapshot(): PhysicalNetworkSnapshot = networkBucketProvider.snapshot()

    private val singboxProbeMutex = Mutex()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(LATENCY_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(LATENCY_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout(LATENCY_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .build()

    suspend fun testLatency(configs: List<ParsedConfig>): List<LatencyResult> =
        coroutineScope {
            val semaphore = Semaphore(MAX_CONCURRENCY)
            configs.map { config ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { testSingleLatency(config) }
                }
            }.awaitAll()
        }

    private suspend fun testSingleLatency(config: ParsedConfig): LatencyResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val startNetwork = networkBucketProvider.snapshot()
            var attemptedSamples = 0
            var successfulSamples = 0
            val result = try {
                withSingboxClient(config) { client ->
                    val startedAtNanos = System.nanoTime()
                    val request = Request.Builder().url(LATENCY_TEST_URL).head().build()
                    attemptedSamples++
                    runInterruptible(Dispatchers.IO) {
                        client.newCall(request).execute().use { response ->
                            val elapsed = ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(1L)
                            val success = response.code in 200..399
                            if (success) successfulSamples++
                            LatencyResult(config, elapsed, success)
                        }
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                LatencyResult(config, Long.MAX_VALUE, success = false)
            }
            result.copy(
                evidence = ProbeEvidence(
                    method = ProbeMethod.ISOLATED_PROFILE_LATENCY,
                    startedAtEpochMs = startedAt,
                    completedAtEpochMs = System.currentTimeMillis(),
                    startNetwork = startNetwork,
                    endNetwork = networkBucketProvider.snapshot(),
                    attemptedSamples = attemptedSamples,
                    successfulSamples = successfulSamples
                )
            )
        }

    suspend fun testSpeed(configs: List<ParsedConfig>): List<SpeedResult> =
        coroutineScope {
            configs.map { config ->
                async(Dispatchers.IO) { testSingleSpeed(config) }
            }.awaitAll()
        }

    private suspend fun testSingleSpeed(config: ParsedConfig): SpeedResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val startNetwork = networkBucketProvider.snapshot()
            var attemptedSamples = 0
            var successfulSamples = 0
            val result = try {
                val action: suspend (OkHttpClient) -> SpeedResult = { client ->
                    val request = Request.Builder()
                        .url(SPEED_TEST_URL)
                        .header("User-Agent", "VPNProxy/1.0")
                        .build()
                    val speedStartedAt = System.currentTimeMillis()
                    attemptedSamples++
                    runInterruptible(Dispatchers.IO) {
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) error("Speed test HTTP ${response.code}")
                            val bytes = response.body?.bytes() ?: ByteArray(0)
                            val elapsedMs = (System.currentTimeMillis() - speedStartedAt).coerceAtLeast(1L)
                            val speedMbps = (bytes.size * 8.0 / 1_000_000.0 / (elapsedMs / 1000.0)).toFloat()
                            val success = bytes.isNotEmpty()
                            if (success) successfulSamples++
                            SpeedResult(config, speedMbps, success)
                        }
                    }
                }
                withSingboxClient(config, action)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                SpeedResult(config, 0f, success = false)
            }
            result.copy(
                evidence = ProbeEvidence(
                    method = ProbeMethod.ISOLATED_PROFILE_SPEED,
                    startedAtEpochMs = startedAt,
                    completedAtEpochMs = System.currentTimeMillis(),
                    startNetwork = startNetwork,
                    endNetwork = networkBucketProvider.snapshot(),
                    attemptedSamples = attemptedSamples,
                    successfulSamples = successfulSamples
                )
            )
        }

    /**
     * If the VPN is already active, use its current route and never replace the active
     * Sing-box configuration. Otherwise start a temporary isolated proxy for this config.
     * A mutex prevents concurrent HY2/TUIC probes from racing over the shared loopback port.
     */
    private suspend fun <T> withSingboxClient(
        config: ParsedConfig,
        action: suspend (OkHttpClient) -> T
    ): T {
        singboxProbeMutex.lock()
        try {
            // A transition can be in progress before isRunning flips to true.
            // Never mistake that small window for an idle core and start a second one.
            check(!VpnProxyService.isCoreTransitioning.value) {
                "VPN core is starting or stopping; health probe deferred"
            }
            val manageSingbox = !VpnProxyService.isRunning.value
            if (!manageSingbox) {
                // The active VPN route cannot prove that this arbitrary candidate works.
                // Fail closed instead of assigning the current tunnel's success to every profile.
                error("Cannot probe an alternative profile while the VPN core is active")
            }

            return CorePortLease.mutex.withLock {
                check(!VpnProxyService.isRunning.value && !VpnProxyService.isCoreTransitioning.value) {
                    "VPN core became active before health probe acquired the shared port lease"
                }
                val started = SingboxProxyClient.start(context, SingboxConfigBuilder.build(config))
                if (!started) {
                    SingboxProxyClient.stop(context)
                    error("Sing-box failed to start for health check")
                }
                val client = OkHttpClient.Builder()
                    .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", SingboxConfigBuilder.PROXY_PORT)))
                    .connectTimeout(LATENCY_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                    .readTimeout(LATENCY_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                    .callTimeout(LATENCY_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(false)
                    .build()
                try {
                    action(client)
                } finally {
                    SingboxProxyClient.stop(context)
                }
            }
        } finally {
            singboxProbeMutex.unlock()
        }
    }
}
