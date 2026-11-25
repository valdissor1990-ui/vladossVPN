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

        /** The endpoint looks unreachable: ordinary blacklist. */
        BAN_NETWORK,

        /** Unknown core failure: ordinary blacklist. */
        BAN_OTHER,

        /** Device-side or local lifecycle failure: the profile is not at fault. */
        NO_BAN
    }

    private val REMOTE_DIAL_MARKERS = listOf(
        "dial tcp",
        "i/o timeout",
        "network is unreachable",
        "no route to host",
        "unexpected eof",
        "eof:"
    )

    /** Weak hints: also produced by a local socket, so they never override a local failure. */
    private val WEAK_NETWORK_MARKERS = listOf(
        "timeout",
        "connection refused",
        "deadline exceeded"
    )

    fun isBindFailure(errorMessage: String): Boolean {
        val m = errorMessage.lowercase()
        return m.contains("address already in use") || m.contains("bind:") || m.contains("eaddrinuse")
    }

    fun action(reason: VpnFailureReason, errorMessage: String): Action {
        val m = errorMessage.lowercase()
        if (isBindFailure(m)) return Action.NO_BAN
        if (VpnFailureReason.isConfigurationError(m)) return Action.BAN_SHORT

        val isLocalCoreFailure = VpnFailureReason.isLocalCoreStartFailure(m)
        // A remote dial failure can be wrapped by the same libbox startup prefix. A local
        // CommandServer timeout or a hung TUN setup carries no dial marker and must not ban
        // a healthy server, so network detection needs explicit remote-side evidence.
        val hasRemoteDialMarker = REMOTE_DIAL_MARKERS.any { m.contains(it) }
        val hasNetworkMarker = hasRemoteDialMarker ||
            reason is VpnFailureReason.NetworkTimeout ||
            WEAK_NETWORK_MARKERS.any { m.contains(it) }
        if (hasNetworkMarker && (!isLocalCoreFailure || hasRemoteDialMarker)) {
            return Action.BAN_NETWORK
        }

        val isLocalReason = reason is VpnFailureReason.TunConflict ||
            reason is VpnFailureReason.AnotherVpnActive ||
            reason is VpnFailureReason.PermissionDenied
        if (isLocalCoreFailure || isLocalReason) return Action.NO_BAN

        return Action.BAN_OTHER
    }
}
