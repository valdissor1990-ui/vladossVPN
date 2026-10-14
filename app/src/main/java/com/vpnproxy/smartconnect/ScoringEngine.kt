package com.vpnproxy.smartconnect

import com.vpnproxy.smartconnect.HealthChecker.LatencyResult
import com.vpnproxy.smartconnect.HealthChecker.SpeedResult
import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Скоринг серверов.
 *
 * score = 0.6·(1/latency) + 0.3·(speed/maxSpeed) + 0.1·successRate
 */
@Singleton
class ScoringEngine @Inject constructor() {

    data class ScoredServer(
        val config: ParsedConfig,
        val latencyMs: Long,
        val speedMbps: Float,
        val successRate: Float,
        val score: Float
    ) : Comparable<ScoredServer> {
        override fun compareTo(other: ScoredServer): Int =
            other.score.compareTo(this.score) // descending
    }

    /**
     * Вычисляет скоринг на основе latency и speed результатов.
     */
    fun computeScore(
        latencyResults: List<LatencyResult>,
        speedResults: List<SpeedResult> = emptyList()
    ): List<ScoredServer> {
        val speedMap = speedResults.associateBy { it.config.address + it.config.port }
        val maxSpeed = speedResults.maxOfOrNull { it.speedMbps }?.coerceAtLeast(0.001f) ?: 0.001f

        return latencyResults
            .filter { it.success }
            .map { lr ->
                val speed = speedMap[lr.config.address + lr.config.port]?.speedMbps ?: 0f
                val normalizedLatency = 1.0f / (lr.latencyMs.coerceAtLeast(1L).toFloat() / 100f)
                val normalizedSpeed = speed / maxSpeed
                val successRate = 1.0f // TODO: накопление статистики успехов

                val score = 0.6f * normalizedLatency +
                            0.3f * normalizedSpeed +
                            0.1f * successRate

                ScoredServer(
                    config = lr.config,
                    latencyMs = lr.latencyMs,
                    speedMbps = speed,
                    successRate = successRate,
                    score = score
                )
            }
            .sorted()
    }

    /**
     * Полный цикл: latency-тест → топ-5 → speed-тест → скоринг.
     */
    suspend fun rankAll(
        configs: List<ParsedConfig>,
        healthChecker: HealthChecker
    ): List<ScoredServer> {
        val latencyResults = healthChecker.testLatency(configs)
        val top5 = latencyResults
            .filter { it.success }
            .sortedBy { it.latencyMs }
            .take(5)

        val speedResults = if (top5.isNotEmpty()) {
            healthChecker.testSpeed(top5.map { it.config })
        } else emptyList()

        return computeScore(latencyResults, speedResults)
    }
}
