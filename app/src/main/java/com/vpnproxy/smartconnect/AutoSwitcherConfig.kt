package com.vpnproxy.smartconnect

/**
 * Continuity Engine thresholds — tuned for unstable free-subscription pools
 * without weakening hard validation (still requires evidence before switch).
 */
object AutoSwitcherConfig {
    /** Health cadence: tighter than legacy 150s so free-node death is noticed sooner. */
    const val healthCheckIntervalMs = 90_000L
    const val batterySaverHealthCheckIntervalMs = 240_000L
    const val suspectRttMs = 500L
    const val suspectJitterMs = 150L
    const val directFastRttMs = 100L
    const val microProbeMinMbps = 1.0
    /** Base cooldown after a switch; still prevents flapping. */
    const val switchCooldownMs = 3 * 60_000L
    const val switchWindowMs = 15 * 60_000L
    const val maxSwitchesPerWindow = 4
    const val directProbeTimeoutMs = 1_500
    const val microProbeTimeoutMs = 3_000
    const val microProbeBytes = 51_200L
    const val serverDegradationConfirmations = 2
    const val networkDegradationConfirmations = 2
    const val evidenceWindowMs = 10 * 60_000L
    const val maxConsecutiveErrors = 3
    const val rttDegradationFactor = 2.5
    const val microProbeBufferBytes = 8 * 1_024
    const val directProbePort = 53
    const val evidenceHistoryLimit = 12
    /** Relative score gain for background auto-switch (slightly lower = faster promote of better node). */
    const val minimumAutoSwitchScoreGain = 0.15f
    const val ruFastServerRttMs = 200L
    const val manualSwitchDelayMs = 50L
    const val serverBlacklistDurationMs = 15 * 60_000L
    /** Second+ fail on same node within window → longer ban. */
    const val serverBlacklistEscalatedMs = 45 * 60_000L
    /** After Connected, suppress automatic app-level switches (core urltest still active). */
    const val postConnectWarmupMs = 60_000L
    const val ruTokenPattern = "(^|[^a-z0-9])ru([^a-z0-9]|$)
    const val microProbeUrl = "https://proof.ovh.net/files/1Mb.dat"
    /** Extra outbounds packed into sing-box urltest group beside the primary. */
    const val urlTestStandbyCount = 2
    const val urlTestInterval = "45s"
    const val urlTestToleranceMs = 80
    const val urlTestProbeUrl = "https://www.gstatic.com/generate_204"
}
