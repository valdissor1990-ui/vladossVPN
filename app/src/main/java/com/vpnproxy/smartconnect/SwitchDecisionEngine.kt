package com.vpnproxy.smartconnect

import java.util.ArrayDeque

internal enum class NetworkFaultKind { NONE, NETWORK_DEGRADATION, SERVER_DEGRADATION }
internal data class ProbeResult(val latencyMs: Long, val success: Boolean)

internal data class ProbeEvidence(
    val directLatencyMs: Long,
    val directSuccess: Boolean,
    val vpnLatencyMs: Long,
    val vpnSuccess: Boolean,
    val jitterMs: Long,
    val timestampMs: Long = System.currentTimeMillis()
)

/** Pure classification helpers retained as top-level functions for existing unit-test callers. */
internal fun classifyProbeFault(
    direct: ProbeResult,
    vpn: ProbeResult,
    suspectRttMs: Long = AutoSwitcherConfig.suspectRttMs,
    directFastRttMs: Long = AutoSwitcherConfig.directFastRttMs
): NetworkFaultKind {
    val directBad = !direct.success || direct.latencyMs > suspectRttMs
    val directFast = direct.success && direct.latencyMs < directFastRttMs
    val vpnBad = !vpn.success || vpn.latencyMs > suspectRttMs
    return when {
        directBad && !vpnBad -> NetworkFaultKind.NETWORK_DEGRADATION
        directFast && vpnBad -> NetworkFaultKind.SERVER_DEGRADATION
        else -> NetworkFaultKind.NONE
    }
}

internal fun shouldSwitchAfterEvidence(
    serverConfirmations: Int,
    networkConfirmations: Int,
    directPathHealthy: Boolean
): Boolean = directPathHealthy &&
    serverConfirmations >= AutoSwitcherConfig.serverDegradationConfirmations &&
    networkConfirmations < AutoSwitcherConfig.networkDegradationConfirmations

/**
 * Holds only switch-policy evidence and cooldown bookkeeping. It does not choose/rank servers;
 * AutoSwitcher remains responsible for applying decisions to the current server.
 */
internal class SwitchDecisionEngine {
    private var serverEvidence = 0
    private var networkEvidence = 0
    private var cooldownUntil = 0L
    private val recentSwitchesMs = ArrayDeque<Long>()
    private val recentEvidence = ArrayDeque<ProbeEvidence>()

    val consecutiveServerEvidence: Int get() = synchronized(this) { serverEvidence }
    val consecutiveNetworkEvidence: Int get() = synchronized(this) { networkEvidence }
    val cooldownUntilMs: Long get() = synchronized(this) { cooldownUntil }

    fun classify(direct: ProbeResult, vpn: ProbeResult): NetworkFaultKind =
        classifyProbeFault(direct, vpn)

    @Synchronized
    fun recordEvidence(direct: ProbeResult, vpn: ProbeResult, jitterMs: Long, nowMs: Long = System.currentTimeMillis()) {
        trimEvidence(nowMs)
        recentEvidence.addLast(ProbeEvidence(direct.latencyMs, direct.success, vpn.latencyMs, vpn.success, jitterMs, nowMs))
        while (recentEvidence.size > AutoSwitcherConfig.evidenceHistoryLimit) recentEvidence.removeFirst()
    }

    @Synchronized
    fun latestEvidence(nowMs: Long = System.currentTimeMillis()): ProbeEvidence? {
        trimEvidence(nowMs)
        return recentEvidence.lastOrNull()
    }

    private fun trimEvidence(nowMs: Long) {
        while (recentEvidence.isNotEmpty() && nowMs - recentEvidence.peekFirst().timestampMs > AutoSwitcherConfig.evidenceWindowMs) {
            recentEvidence.removeFirst()
        }
    }

    @Synchronized
    fun shouldSwitch(directPathHealthy: Boolean): Boolean =
        shouldSwitchAfterEvidence(serverEvidence, networkEvidence, directPathHealthy)

    @Synchronized
    fun record(classification: NetworkFaultKind): Pair<Int, Int> {
        when (classification) {
            NetworkFaultKind.NETWORK_DEGRADATION -> {
                networkEvidence++
                serverEvidence = 0
            }
            NetworkFaultKind.SERVER_DEGRADATION -> {
                serverEvidence++
                networkEvidence = 0
            }
            NetworkFaultKind.NONE -> {
                serverEvidence = 0
                networkEvidence = 0
            }
        }
        return serverEvidence to networkEvidence
    }

    @Synchronized
    fun isInCooldown(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs < cooldownUntil

    @Synchronized
    fun canAttemptSwitch(nowMs: Long = System.currentTimeMillis()): Boolean {
        trimSwitchWindow(nowMs)
        return recentSwitchesMs.size < AutoSwitcherConfig.maxSwitchesPerWindow
    }

    /** Returns true when the policy limit has been reached after recording this switch. */
    /**
     * Background ranking may only replace a healthy current server when the candidate has
     * a material, measurable advantage. A missing current score is not enough evidence:
     * callers must use the existing failure-validation/fallback path instead.
     */
    fun shouldSwitchForRanking(
        currentScore: Float?,
        candidateScore: Float,
        minimumRelativeGain: Float = AutoSwitcherConfig.minimumAutoSwitchScoreGain
    ): Boolean {
        if (currentScore == null || !currentScore.isFinite() || currentScore <= 0f) return false
        if (!candidateScore.isFinite() || candidateScore <= 0f) return false
        if (!minimumRelativeGain.isFinite() || minimumRelativeGain < 0f) return false
        return candidateScore >= currentScore * (1f + minimumRelativeGain)
    }

    @Synchronized
    fun recordSuccessfulSwitch(nowMs: Long = System.currentTimeMillis()): Boolean {
        trimSwitchWindow(nowMs)
        recentSwitchesMs.addLast(nowMs)
        cooldownUntil = nowMs + AutoSwitcherConfig.switchCooldownMs
        trimSwitchWindow(nowMs)
        return recentSwitchesMs.size >= AutoSwitcherConfig.maxSwitchesPerWindow
    }

    @Synchronized
    fun switchCountInWindow(nowMs: Long = System.currentTimeMillis()): Int {
        trimSwitchWindow(nowMs)
        return recentSwitchesMs.size
    }

    private fun trimSwitchWindow(nowMs: Long) {
        while (recentSwitchesMs.isNotEmpty() &&
            nowMs - recentSwitchesMs.peekFirst() > AutoSwitcherConfig.switchWindowMs
        ) {
            recentSwitchesMs.removeFirst()
        }
    }
}
