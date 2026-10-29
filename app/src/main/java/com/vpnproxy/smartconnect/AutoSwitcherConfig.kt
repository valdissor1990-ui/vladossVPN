package com.vpnproxy.smartconnect

/**
 * Continuity + battery thresholds.
 *
 * Defaults bias toward fewer wakeups while keeping hard-fail / blacklist aggressive
 * for unstable free pools. System Battery Saver and optional in-app economy mode
 * further stretch intervals (see [BatteryPolicy]).
 */
object AutoSwitcherConfig {
    /** Normal monitoring cadence — 2.5 min balances free-pool detection vs battery. */
    const val healthCheckIntervalMs = 150_000L
    /** System Battery Saver: stretch hard to cut background radio/CPU. */
    const val batterySaverHealthCheckIntervalMs = 480_000L
    /** In-app economy mode (user toggle): between normal and system saver. */
    const val economyHealthCheckIntervalMs = 300_000L
    const val suspectRttMs = 500L
    const val suspectJitterMs = 150L
    const val directFastRttMs = 100L
    const val microProbeMinMbps = 1.0
    /** Base cooldown after a switch; still prevents flapping. */
    const val switchCooldownMs = 3 * 60_000L
    const val switchWindowMs = 15 * 60_000L
    /** Keep at 3 — unit tests and anti-flap policy depend on this ceiling. */
    const val maxSwitchesPerWindow = 3
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
    /** Minimum relative score gain required for a background auto-switch. */
    const val minimumAutoSwitchScoreGain = 0.20f
    const val ruFastServerRttMs = 200L
    const val manualSwitchDelayMs = 50L
    const val serverBlacklistDurationMs = 15 * 60_000L
    /** Second+ fail on same node within window → longer ban. */
    const val serverBlacklistEscalatedMs = 45 * 60_000L
    /** After Connected, suppress automatic app-level switches. */
    const val postConnectWarmupMs = 60_000L
    const val ruTokenPattern = "(^|[^a-z0-9])ru([^a-z0-9]|$)"
    const val microProbeUrl = "https://proof.ovh.net/files/1Mb.dat"
    /** Extra outbounds packed into sing-box urltest group beside the primary. */
    const val urlTestStandbyCount = 2
    /** Prefer 90s when urltest standbys are enabled — less radio than 45s. */
    const val urlTestInterval = "90s"
    const val urlTestToleranceMs = 80
    const val urlTestProbeUrl = "https://www.gstatic.com/generate_204"

    // --- Battery economy policy flags (consumed by BatteryPolicy / AutoSwitcher) ---
    /** Skip micro-speed download probe under system Battery Saver. */
    const val skipMicroProbeInBatterySaver = true
    /** Skip ranking-based (score) auto-switch under system Battery Saver; hard-fail path remains. */
    const val skipRankingSwitchInBatterySaver = true
    /** SharedPreferences name / key for optional in-app economy toggle. */
    const val batteryPrefsName = "vpn_battery_policy_v1"
    const val batteryEconomyEnabledKey = "economy_mode"
}
