package com.vpnproxy.smartconnect

import android.content.Context
import com.vpnproxy.core.singbox.SingboxConfigBuilder
import com.vpnproxy.core.singbox.SingboxProxyClient
import com.vpnproxy.service.VpnProxyService
import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "HealthChecker"
        private const val LATENCY_TIMEOUT_MS = 3000
        private const val LATENCY_TEST_URL = "https://www.gstatic.com/generate_204"
        private const val SPEED_TEST_URL = "https://speed.cloudflare.com/__down?bytes=1048576"
        private const val MAX_CONCURRENCY = 10
    }

    data class LatencyResult(
        val config: ParsedConfig,
        val latencyMs: Long,
        val success: Boolean
    )

    data class SpeedResult(
        val config: ParsedConfig,
        val speedMbps: Float,
        val success: Boolean
    )

    private val singboxProbeMutex = Mutex()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(LATENCY_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
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
            try {
                if (config.requiresSingBox()) {
                    return@withContext withSingboxClient(config) { client ->
                        val startedAt = System.nanoTime()
                        val request = Request.Builder().url(LATENCY_TEST_URL).head().build()
                        client.newCall(request).execute().use { response ->
                            val elapsed = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
                            LatencyResult(config, elapsed, success = response.code in 200..399)
                        }
                    }
                }

                val startedAt = System.currentTimeMillis()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(config.address, config.port), LATENCY_TIMEOUT_MS)
                }
                val elapsed = System.currentTimeMillis() - startedAt
                LatencyResult(config, elapsed, success = elapsed < LATENCY_TIMEOUT_MS)
            } catch (_: Exception) {
                LatencyResult(config, Long.MAX_VALUE, success = false)
            }
        }

    suspend fun testSpeed(configs: List<ParsedConfig>): List<SpeedResult> =
        coroutineScope {
            configs.map { config ->
                async(Dispatchers.IO) { testSingleSpeed(config) }
            }.awaitAll()
        }

    private suspend fun testSingleSpeed(config: ParsedConfig): SpeedResult =
        withContext(Dispatchers.IO) {
            try {
                val action: (OkHttpClient) -> SpeedResult = { client ->
                    val request = Request.Builder()
                        .url(SPEED_TEST_URL)
                        .header("User-Agent", "VPNProxy/1.0")
                        .build()
                    val startedAt = System.currentTimeMillis()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("Speed test HTTP ${response.code}")
                        val bytes = response.body?.bytes() ?: ByteArray(0)
                        val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
                        val speedMbps = (bytes.size * 8.0 / 1_000_000.0 / (elapsedMs / 1000.0)).toFloat()
                        SpeedResult(config, speedMbps, success = bytes.isNotEmpty())
                    }
                }
                if (config.requiresSingBox()) withSingboxClient(config, action) else action(httpClient)
            } catch (_: Exception) {
                SpeedResult(config, 0f, success = false)
            }
        }

    /**
     * If the VPN is already active, use its current route and never replace the active
     * Sing-box configuration. Otherwise start a temporary isolated proxy for this config.
     * A mutex prevents concurrent HY2/TUIC probes from racing over the shared loopback port.
     */
    private suspend fun <T> withSingboxClient(
        config: ParsedConfig,
        action: (OkHttpClient) -> T
    ): T {
        singboxProbeMutex.lock()
        try {
            val manageSingbox = !VpnProxyService.isRunning.value
            if (manageSingbox) {
                val started = SingboxProxyClient.start(context, SingboxConfigBuilder.build(config))
                if (!started) {
                    SingboxProxyClient.stop(context)
                    error("Sing-box failed to start for health check")
                }
            }

            val client = if (manageSingbox) {
                OkHttpClient.Builder()
                    .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", SingboxConfigBuilder.PROXY_PORT)))
                    .connectTimeout(LATENCY_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .callTimeout(15, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false)
                    .build()
            } else {
                // The active VPN route is the only safe probe while another server is connected.
                httpClient
            }

            try {
                action(client)
            } finally {
                if (manageSingbox) SingboxProxyClient.stop(context)
            }
        } finally {
            singboxProbeMutex.unlock()
        }
    }
}
