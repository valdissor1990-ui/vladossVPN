package com.vpnproxy.core.ping

/**
 * Агрегированная статистика multi-target ping (Фича 3).
 */
data class LatencyBreakdown(
    val medianMs: Double?,
    val p95Ms: Double?,
    val jitterMs: Double?,
    /** Меньше = лучше. 9999.0 = недостаточно успешных проб. */
    val compositeScore: Double,
    val perTarget: List<TargetResult>,
    val successCount: Int
) {
    val isValid: Boolean get() = compositeScore < 9999.0 && successCount >= 3

    fun displayScore(): String =
        if (!isValid) "N/A" else compositeScore.toInt().coerceAtLeast(0).toString()
}
