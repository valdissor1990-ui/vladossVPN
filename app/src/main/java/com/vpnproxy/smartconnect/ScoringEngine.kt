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
        val score: Float,
        val evidence: ProbeEvidence? = null
    ) : Comparable<ScoredServer> {
        override fun compareTo(other: ScoredServer): Int = other.score.compareTo(this.score)
    }

    private data class ProbeHistory(var successes: Long = 0L, var attempts: Long = 0L) {
        fun rate(): Float = if (attempts == 0L) 0f else successes.toFloat() / attempts.toFloat()
    }

    companion object {
        /** Bound history so an old bad period cannot penalize a recovered endpoint forever. */
        private const val MAX_HISTORY_ATTEMPTS = 20L
        private const val PROBE_EVIDENCE_MAX_AGE_MS = 90_000L
    }

    private val historyPreferences by lazy {
        context?.getSharedPreferences("server_scoring_history_v2", Context.MODE_PRIVATE)
    }
    private val history = ConcurrentHashMap<String, ProbeHistory>()
    private val networkBucketProvider by lazy { context?.let(::NetworkBucketProvider) }

    private fun historyKey(config: ParsedConfig, networkBucket: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("${networkBucket}|${config.connectionKey()}".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun loadHistory(config: ParsedConfig, networkBucket: String): ProbeHistory {
        val key = historyKey(config, networkBucket)
        val prefs = historyPreferences ?: return ProbeHistory()
        return ProbeHistory(
            successes = prefs.getLong("${key}_successes", 0L),
            attempts = prefs.getLong("${key}_attempts", 0L)
        )
    }

    private fun recordProbe(config: ParsedConfig, success: Boolean, networkBucket: String) {
        val key = historyKey(config, networkBucket)
        val next = history.computeIfAbsent(key) { loadHistory(config, networkBucket) }
        val snapshot = synchronized(next) {
            // Exponential aging in attempt space: once the window is full, halve
            // the old evidence before adding the new result. This gives recent
            // probes more influence and lets recovered servers regain rank.
            while (next.attempts >= MAX_HISTORY_ATTEMPTS) {
                next.successes /= 2L
                next.attempts /= 2L
            }
            next.attempts++
            if (success) next.successes++
            next.successes to next.attempts
        }
        historyPreferences?.edit()
            ?.putLong("${key}_successes", snapshot.first)
            ?.putLong("${key}_attempts", snapshot.second)
            ?.apply()
    }

    private fun successRate(config: ParsedConfig, networkBucket: String): Float {
        val key = historyKey(config, networkBucket)
        val item = history.computeIfAbsent(key) { loadHistory(config, networkBucket) }
        return synchronized(item) { item.rate() }
    }

    /** Current coarse physical transport bucket, retained for existing call sites. */
    fun currentNetworkBucket(): String = networkBucketProvider?.currentBucket() ?: "other"

    fun recordHardFailure(
        config: ParsedConfig,
        weight: Int = 2,
        networkBucket: String = currentNetworkBucket()
    ) {
        val w = weight.coerceIn(1, 5)
        repeat(w) { recordProbe(config, success = false, networkBucket = networkBucket) }
    }

    fun computeScore(
        latencyResults: List<LatencyResult>,
        speedResults: List<SpeedResult> = emptyList(),
        currentNetworkSnapshot: PhysicalNetworkSnapshot? = null
    ): List<ScoredServer> {
        val currentNetwork = currentNetworkSnapshot ?: networkBucketProvider?.snapshot()
        val usableLatencyResults = latencyResults.filter { result ->
            val evidence = result.evidence
            evidence == null || currentNetwork == null ||
                evidence.isFresh(currentNetwork, maxAgeMs = PROBE_EVIDENCE_MAX_AGE_MS)
        }
        if (usableLatencyResults.isEmpty()) return emptyList()

        // Attribute evidence to the network on which it was actually measured, not the
        // network that happens to be active when asynchronous scoring finally runs.
        val networkBucket = usableLatencyResults.firstNotNullOfOrNull {
            it.evidence?.startNetwork?.bucket
        } ?: currentNetwork?.bucket ?: currentNetworkBucket()

        usableLatencyResults.forEach { result ->
            recordProbe(result.config, result.success, result.evidence?.startNetwork?.bucket ?: networkBucket)
        }

        val speedMap = speedResults
            .filter { result ->
                result.success &&
                    result.speedMbps.isFinite() &&
                    result.speedMbps > 0f &&
                    (result.evidence == null || currentNetwork == null ||
                        result.evidence.isFresh(currentNetwork, maxAgeMs = PROBE_EVIDENCE_MAX_AGE_MS))
            }
            .associateBy { it.config.connectionKey() }

        val maxSpeed = speedMap.values.maxOfOrNull { it.speedMbps }?.coerceAtLeast(0.001f) ?: 0.001f

        return usableLatencyResults
            .filter { it.success && it.latencyMs != Long.MAX_VALUE }
            .map { latencyResult ->
                val speedResult = speedMap[latencyResult.config.connectionKey()]?.takeIf { speed ->
                    val latencyEvidence = latencyResult.evidence
                    val speedEvidence = speed.evidence
                    latencyEvidence == null || speedEvidence == null ||
                        latencyEvidence.startNetwork.sameNetwork(speedEvidence.startNetwork)
                }
                val speed = speedResult?.speedMbps ?: 0f
                val normalizedLatency = 1.0f / (latencyResult.latencyMs.coerceAtLeast(1L).toFloat() / 100f)
                val normalizedSpeed = speed / maxSpeed
                val evidenceBucket = latencyResult.evidence?.startNetwork?.bucket ?: networkBucket
                val observedSuccessRate = successRate(latencyResult.config, evidenceBucket)
                // Continuity: success history weighs as much as raw RTT — free nodes
                // often answer TCP/HTTP probes while collapsing under real traffic later.
                var score = 0.40f * normalizedLatency +
                    0.25f * normalizedSpeed +
                    0.35f * observedSuccessRate
                val key = historyKey(latencyResult.config, evidenceBucket)
                val hist = history.computeIfAbsent(key) { loadHistory(latencyResult.config, evidenceBucket) }
                val attempts = synchronized(hist) { hist.attempts }
                if (attempts >= 5L && observedSuccessRate < 0.4f) {
                    score *= 0.5f
                }
                if (attempts >= 3L && observedSuccessRate < 0.2f) {
                    score *= 0.4f
                }

                ScoredServer(
                    config = latencyResult.config,
                    latencyMs = latencyResult.latencyMs,
                    speedMbps = speed,
                    successRate = observedSuccessRate,
                    score = score,
                    evidence = latencyResult.evidence
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
        val currentNetwork = networkBucketProvider?.snapshot()
        val top5 = latencyResults
            .filter { result ->
                result.success && (
                    result.evidence == null || currentNetwork == null ||
                        result.evidence.isFresh(currentNetwork, maxAgeMs = PROBE_EVIDENCE_MAX_AGE_MS)
                )
            }
            .sortedBy { it.latencyMs }
            .take(5)

        val speedResults = if (top5.isNotEmpty()) {
            healthChecker.testSpeed(top5.map { it.config })
        } else emptyList()

        val networkAfterMeasurements = healthChecker.currentNetworkSnapshot()
        return computeScore(latencyResults, speedResults, networkAfterMeasurements)
    }
}
