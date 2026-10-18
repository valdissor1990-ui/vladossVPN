package com.vpnproxy.service

/**
 * Pure watchdog decision policy, separated so reconnect grace behavior can be unit-tested.
 */
internal object WatchdogReconnectPolicy {
    const val MAX_RECONNECT_GRACE_MS = 30_000L

    fun shouldWaitForReconnect(
        vpnRunning: Boolean,
        reconnecting: Boolean,
        graceWaitedMs: Long
    ): Boolean = !vpnRunning &&
        reconnecting &&
        graceWaitedMs < MAX_RECONNECT_GRACE_MS
}
