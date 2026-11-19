package com.vpnproxy.smartconnect

/**
 * Physical underlay identity captured when a probe runs. The network identifier is a
 * one-way digest; raw Android network handles are never persisted or logged.
 */
data class PhysicalNetworkSnapshot(
    val bucket: String,
    val networkId: String,
    val generation: Long,
    val capturedAtEpochMs: Long
) {
    fun sameNetwork(other: PhysicalNetworkSnapshot): Boolean =
        bucket == other.bucket && networkId == other.networkId && generation == other.generation
}

enum class ProbeMethod {
    ISOLATED_PROFILE_LATENCY,
    ISOLATED_PROFILE_SPEED,
    DEEP_PROFILE_LATENCY,
    DEEP_DIAGNOSTIC
}

/**
 * Evidence attached to a measurement rather than inferred later from the current connection.
 * A result is reusable only when the probe stayed on one physical network and that exact
 * network generation is still current.
 */
data class ProbeMeasurementEvidence(
    val method: ProbeMethod,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long,
    val startNetwork: PhysicalNetworkSnapshot,
    val endNetwork: PhysicalNetworkSnapshot,
    val attemptedSamples: Int,
    val successfulSamples: Int
) {
    val networkStable: Boolean
        get() = startNetwork.sameNetwork(endNetwork)

    val successRate: Float
        get() = if (attemptedSamples <= 0) 0f
        else successfulSamples.toFloat() / attemptedSamples.toFloat()

    fun isFresh(
        currentNetwork: PhysicalNetworkSnapshot,
        nowEpochMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long
    ): Boolean {
        val ageMs = nowEpochMs - completedAtEpochMs
        return startedAtEpochMs > 0L &&
            completedAtEpochMs >= startedAtEpochMs &&
            maxAgeMs >= 0L &&
            networkStable &&
            endNetwork.sameNetwork(currentNetwork) &&
            ageMs in 0..maxAgeMs &&
            attemptedSamples > 0 &&
            successfulSamples in 0..attemptedSamples
    }
}
