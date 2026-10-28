package com.vpnproxy.smartconnect

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.vpnproxy.smartconnect.HealthChecker.LatencyResult
import com.vpnproxy.smartconnect.HealthChecker.SpeedResult
import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scores reachable connection profiles using latency, measured throughput, and observed
 * probe success rate. Profile identity uses the complete connection key, not just endpoint.
 */
@Singleton
class ScoringEngine @Inject constructor(
    @ApplicationContext private val context: Context?
) {

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

    private val historyPreferences by lazy {
        context?.getSharedPreferences("server_scoring_history_v1", Context.MODE_PRIVATE)
    }
    private val history = ConcurrentHashMap<String, ProbeHistory>()

    private fun historyKey(config: ParsedConfig): String =
        MessageDigest.getInstance("SHA-256")
            .digest(config.connectionKey().toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun loadHistory(config: ParsedConfig): ProbeHistory {
        val key = historyKey(config)
        val prefs = historyPreferences ?: return ProbeHistory()
        return ProbeHistory(
            successes = prefs.getLong("${key}_successes", 0L),
            attempts = prefs.getLong("${key}_attempts", 0L)
        )
    }

    private fun recordProbe(config: ParsedConfig, success: Boolean) {
        val key = config.connectionKey()
        val next = history.computeIfAbsent(key) { loadHistory(config) }
        val snapshot = synchronized(next) {
            next.attempts++
            if (success) next.successes++
            next.successes to next.attempts
        }
        val storageKey = historyKey(config)
        historyPreferences?.edit()
            ?.putLong("${storageKey}_successes", snapshot.first)
            ?.putLong("${storageKey}_attempts", snapshot.second)
            ?.apply()
    }

    private fun successRate(config: ParsedConfig): Float {
        val item = history.computeIfAbsent(config.connectionKey()) { loadHistory(config) }
        return synchronized(item) { item.rate() }
    }

    /**
     * Record a hard connect/traffic failure (not a soft latency miss).
     * Counts as failed attempts so chronic free-pool nodes lose rank quickly.
     */
    fun recordHardFailure(config: ParsedConfig, weight: Int = 2) {
        val w = weight.coerceIn(1, 5)
        repeat(w) { recordProbe(config, success = false) }
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
                // Continuity: success history weighs as much as raw RTT — free nodes
                // often answer TCP/HTTP probes while collapsing under real traffic later.
                var score = 0.40f * normalizedLatency +
                    0.25f * normalizedSpeed +
                    0.35f * observedSuccessRate
                val hist = history.computeIfAbsent(lr.config.connectionKey()) { loadHistory(lr.config) }
                val attempts = synchronized(hist) { hist.attempts }
                if (attempts >= 5L && observedSuccessRate < 0.4f) {
                    score *= 0.5f
                }
                if (attempts >= 3L && observedSuccessRate < 0.2f) {
                    score *= 0.4f
                }

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
