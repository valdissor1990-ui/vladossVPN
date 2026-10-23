package com.vpnproxy.smartconnect

import com.vpnproxy.smartconnect.HealthChecker.LatencyResult
import com.vpnproxy.smartconnect.HealthChecker.SpeedResult
import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scores reachable connection profiles using latency, measured throughput, and observed
 * probe success rate. Profile identity uses the complete connection key, not just endpoint.
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
        override fun compareTo(other: ScoredServer): Int = other.score.compareTo(this.score)
    }

    private data class ProbeHistory(var successes: Long = 0L, var attempts: Long = 0L) {
        fun rate(): Float = if (attempts == 0L) 0f else successes.toFloat() / attempts.toFloat()
    }

    private val history = ConcurrentHashMap<String, ProbeHistory>()

    private fun recordProbe(config: ParsedConfig, success: Boolean) {
        history.compute(config.connectionKey()) { _, previous ->
            val next = previous ?: ProbeHistory()
            synchronized(next) {
                next.attempts++
                if (success) next.successes++
            }
            next
        }
    }

    private fun successRate(config: ParsedConfig): Float {
        val item = history[config.connectionKey()] ?: return 0f
        return synchronized(item) { item.rate() }
    }

    fun computeScore(
        latencyResults: List<LatencyResult>,
        speedResults: List<SpeedResult> = emptyList()
    ): List<ScoredServer> {
        latencyResults.forEach { recordProbe(it.config, it.success) }
        val speedMap = speedResults
            .filter { it.success && it.speedMbps.isFinite() && it.speedMbps > 0f }
            .associateBy { it.config.connectionKey() }
        val maxSpeed = speedMap.values.maxOfOrNull { it.speedMbps }?.coerceAtLeast(0.001f) ?: 0.001f

        return latencyResults
            .filter { it.success && it.latencyMs != Long.MAX_VALUE }
            .map { lr ->
                val speed = speedMap[lr.config.connectionKey()]?.speedMbps ?: 0f
                val normalizedLatency = 1.0f / (lr.latencyMs.coerceAtLeast(1L).toFloat() / 100f)
                val normalizedSpeed = speed / maxSpeed
                val observedSuccessRate = successRate(lr.config)
                val score = 0.6f * normalizedLatency +
                    0.3f * normalizedSpeed +
                    0.1f * observedSuccessRate

                ScoredServer(
                    config = lr.config,
                    latencyMs = lr.latencyMs,
                    speedMbps = speed,
                    successRate = observedSuccessRate,
                    score = score
                )
            }
            .sorted()
    }

    /** Latency probe all profiles, speed-test only the five fastest reachable candidates. */
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
