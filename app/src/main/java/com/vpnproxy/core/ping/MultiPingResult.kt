package com.vpnproxy.core.ping

data class TargetResult(
    val targetId: String,
    val rttMs: Long?,
    val success: Boolean,
    val errorMessage: String? = null,
    val timestamp: Long
)

data class MultiPingResult(
    val results: List<TargetResult>,
    val region: PingRegion,
    val timestamp: Long
) {
    val successRate: Double
        get() = if (results.isEmpty()) 0.0 else results.count { it.success }.toDouble() / results.size

    val weightedAvgMs: Double?
        get() {
            val weights = PingTargets.forRegion(region).associate { it.id to it.weight }
            val successful = results.filter { it.success && it.rttMs != null && (weights[it.targetId] ?: 0.0) > 0.0 }
            if (successful.isEmpty()) return null
            val totalWeight = successful.sumOf { weights[it.targetId] ?: 0.0 }
            if (totalWeight <= 0.0) return null
            return successful.sumOf { (weights[it.targetId] ?: 0.0) * requireNotNull(it.rttMs) } / totalWeight
        }
}
