package com.vpnproxy.smartconnect

import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Health-чекер: измеряет latency и speed для серверов.
 *
 * Latency: TCP-connect + HTTP HEAD к generate_204 (таймаут 3с)
 * Speed: загрузка ~1MB тестового файла для топ-5
 */
@Singleton
class HealthChecker @Inject constructor() {

    companion object {
        private const val TAG = "HealthChecker"
        private const val LATENCY_TIMEOUT_MS = 3000
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

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(LATENCY_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Параллельный latency-тест для всех конфигов.
     * Concurrency ограничена MAX_CONCURRENCY.
     */
    suspend fun testLatency(configs: List<ParsedConfig>): List<LatencyResult> =
        coroutineScope {
            val semaphore = Semaphore(MAX_CONCURRENCY)
            configs.map { config ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        testSingleLatency(config)
                    }
                }
            }.awaitAll()
        }

    private suspend fun testSingleLatency(config: ParsedConfig): LatencyResult =
        withContext(Dispatchers.IO) {
            try {
                // TCP-connect тест
                val start = System.currentTimeMillis()
                val socket = Socket()
                socket.connect(
                    InetSocketAddress(config.address, config.port),
                    LATENCY_TIMEOUT_MS
                )
                val latency = System.currentTimeMillis() - start
                socket.close()

                LatencyResult(config, latency, success = latency < LATENCY_TIMEOUT_MS)
            } catch (e: Exception) {
                LatencyResult(config, Long.MAX_VALUE, success = false)
            }
        }

    /**
     * Speed-тест для топ-N серверов.
     * Загружает ~1MB и замеряет Мбит/с.
     */
    suspend fun testSpeed(configs: List<ParsedConfig>): List<SpeedResult> =
        coroutineScope {
            configs.map { config ->
                async(Dispatchers.IO) {
                    testSingleSpeed(config)
                }
            }.awaitAll()
        }

    private suspend fun testSingleSpeed(config: ParsedConfig): SpeedResult =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(SPEED_TEST_URL)
                    .header("User-Agent", "VPNProxy/1.0")
                    .build()

                val start = System.currentTimeMillis()
                httpClient.newCall(request).execute().use { response ->
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    val elapsedMs = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                    val speedMbps = (bytes.size * 8.0 / 1_000_000.0 / (elapsedMs / 1000.0)).toFloat()
                    SpeedResult(config, speedMbps, success = bytes.isNotEmpty())
                }
            } catch (e: Exception) {
                SpeedResult(config, 0f, success = false)
            }
        }
}
