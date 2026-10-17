package com.vpnproxy.core.ping

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Чистая математика агрегации RTT для multi-target ping.
 * Не ходит в сеть — только считает по готовым [TargetResult].
 */
object LatencyCalculator {

    private const val INVALID_SCORE = 9999.0
    private const val MIN_SAMPLES = 3

    fun compute(results: List<TargetResult>): LatencyBreakdown {
        val rtts = results
            .filter { it.success && it.rttMs != null && it.rttMs >= 0 }
            .map { it.rttMs!!.toDouble() }
            .sorted()

        if (rtts.size < MIN_SAMPLES) {
            return LatencyBreakdown(
                medianMs = null,
                p95Ms = null,
                jitterMs = null,
                compositeScore = INVALID_SCORE,
                perTarget = results,
                successCount = rtts.size
            )
        }

        val median = percentile(rtts, 0.50)
        val p95 = percentile(rtts, 0.95)
        val jitter = stdDev(rtts)
        val score = (0.4 * median) + (0.3 * p95) + (0.3 * min(jitter, 100.0))

        return LatencyBreakdown(
            medianMs = median,
            p95Ms = p95,
            jitterMs = jitter,
            compositeScore = score,
            perTarget = results,
            successCount = rtts.size
        )
    }

    /** Линейная интерполяция перцентиля для sorted ascending list. */
    internal fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        if (sorted.size == 1) return sorted[0]
        val rank = p * (sorted.size - 1)
        val lo = rank.toInt()
        val hi = (lo + 1).coerceAtMost(sorted.lastIndex)
        val frac = rank - lo
        return sorted[lo] * (1.0 - frac) + sorted[hi] * frac
    }

    internal fun stdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance)
    }
}
