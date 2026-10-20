package com.vpnproxy.smartconnect

/**
 * Named thresholds used by AutoSwitcher. Values intentionally match the existing policy;
 * this object centralizes configuration without changing server-selection heuristics.
 */
object AutoSwitcherConfig {
    const val healthCheckIntervalMs = 150_000L
    const val suspectRttMs = 500L
    const val suspectJitterMs = 150L
    const val directFastRttMs = 100L
    const val microProbeMinMbps = 1.0
    const val switchCooldownMs = 5 * 60_000L
    const val switchWindowMs = 15 * 60_000L
    const val maxSwitchesPerWindow = 3
    const val directProbeTimeoutMs = 1_500
    const val microProbeTimeoutMs = 3_000
    const val microProbeBytes = 51_200L
    const val serverDegradationConfirmations = 2
    const val networkDegradationConfirmations = 2
    const val evidenceWindowMs = 10 * 60_000L
    const val maxConsecutiveErrors = 3
    const val rttDegradationFactor = 3.0
    const val microProbeBufferBytes = 8 * 1_024
    const val directProbePort = 53
    const val evidenceHistoryLimit = 12
    const val ruFastServerRttMs = 200L
    const val manualSwitchDelayMs = 50L
    const val ruTokenPattern = "(^|[^a-z0-9])ru([^a-z0-9]|$)"
    const val microProbeUrl = "https://proof.ovh.net/files/1Mb.dat"
}
