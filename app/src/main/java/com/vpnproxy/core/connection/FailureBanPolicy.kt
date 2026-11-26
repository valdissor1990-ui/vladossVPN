package com.vpnproxy.core.connection

/**
 * Pure decision on whether a core failure justifies blacklisting the selected profile.
 *
 * The service used to inline these rules, so a wrong classification could only be caught on
 * a device. Keeping them here makes every branch testable and keeps the ordering explicit:
 * a local TUN/CommandServer failure must win over a network-looking substring, otherwise a
 * healthy server is banned for a device-side problem.
 */
internal object FailureBanPolicy {

    enum class Action {
        /** Malformed profile: short ban so the fallback can pick the next server immediately. */
        BAN_SHORT,

        /** Ordinary blacklist for the endpoint. */
        BAN,

        /** Device-side or local lifecycle failure: the profile is not at fault. */
        NO_BAN
    }

    /**
     * Strong evidence that the remote endpoint failed. These strings only appear in a dial
     * path, so they can override the libbox local-start wrapper.
     */
    private val REMOTE_DIAL_MARKERS = listOf(
        "dial tcp",
        "i/o timeout",
        "network is unreachable",
        "no route to host",
        "unreachable",
        "no route",
        "connection reset",
        "unexpected eof"
    )

    /**
     * Weak hints: a local CommandServer or a hung TUN setup can produce the same words. They
     * count only when the failure is not wrapped as a local core start.
     */
    private val WEAK_NETWORK_MARKERS = listOf(
        "timeout",
        "connection refused",
        "deadline exceeded"
    )

    /** `eof` must be a whole word: "geofence" is not a remote connection failure. */
    private val EOF_WORD = Regex("\\beof\\b")

    fun isBindFailure(errorMessage: String): Boolean {
        val m = errorMessage.lowercase()
        return m.contains("address already in use") || m.contains("bind:") || m.contains("eaddrinuse")
    }

    fun action(reason: VpnFailureReason, errorMessage: String): Action {
        val m = errorMessage.lowercase()
        if (isBindFailure(m)) return Action.NO_BAN
        if (VpnFailureReason.isConfigurationError(m)) return Action.BAN_SHORT

        val hasRemoteDialMarker = REMOTE_DIAL_MARKERS.any { m.contains(it) }

        // A local core start failure is decided only by strong dial evidence. A bare "timeout"
        // or a local "connection refused" from the CommandServer must not ban a healthy server.
        if (VpnFailureReason.isLocalCoreStartFailure(m)) {
            return if (hasRemoteDialMarker) Action.BAN else Action.NO_BAN
        }

        val hasNetworkMarker = hasRemoteDialMarker ||
            reason is VpnFailureReason.NetworkTimeout ||
            WEAK_NETWORK_MARKERS.any { m.contains(it) } ||
            EOF_WORD.containsMatchIn(m)
        if (hasNetworkMarker) return Action.BAN

        val isLocalReason = reason is VpnFailureReason.TunConflict ||
            reason is VpnFailureReason.AnotherVpnActive ||
            reason is VpnFailureReason.PermissionDenied
        if (isLocalReason) return Action.NO_BAN

        return Action.BAN
    }
}
