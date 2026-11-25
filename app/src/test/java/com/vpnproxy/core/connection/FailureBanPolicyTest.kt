package com.vpnproxy.core.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class FailureBanPolicyTest {

    @Test
    fun remoteDialTimeoutWrappedByLibboxStillBans() {
        val message = "Unable to start libbox 1.14.3: dial tcp 203.0.113.7:443: i/o timeout"
        assertEquals(
            FailureBanPolicy.Action.BAN,
            FailureBanPolicy.action(VpnFailureReason.CoreError(message), message)
        )
    }

    @Test
    fun localStartTimeoutDoesNotBan() {
        // A local CommandServer / TUN setup timeout has no dial evidence: the server is fine.
        listOf(
            "Unable to start libbox 1.14.3: context deadline exceeded",
            "Unable to start libbox 1.14.3: start service: timeout"
        ).forEach { message ->
            assertEquals(
                message,
                FailureBanPolicy.Action.NO_BAN,
                FailureBanPolicy.action(VpnFailureReason.CoreError(message), message)
            )
            // Even a typed NetworkTimeout wrapped as a local start failure must not ban.
            assertEquals(
                message,
                FailureBanPolicy.Action.NO_BAN,
                FailureBanPolicy.action(VpnFailureReason.NetworkTimeout(""), message)
            )
        }
    }

    @Test
    fun localConnectionRefusedDoesNotBan() {
        val message = "Unable to start libbox 1.14.3: connect to CommandServer: connection refused"
        assertEquals(
            FailureBanPolicy.Action.NO_BAN,
            FailureBanPolicy.action(VpnFailureReason.CoreError(message), message)
        )
    }

    @Test
    fun plainTimeoutWithoutLocalWrapperBans() {
        val message = "connection timeout to proxy.example.org"
        assertEquals(
            FailureBanPolicy.Action.BAN,
            FailureBanPolicy.action(VpnFailureReason.CoreError(message), message)
        )
        assertEquals(
            FailureBanPolicy.Action.BAN,
            FailureBanPolicy.action(VpnFailureReason.NetworkTimeout(""), "x")
        )
    }

    @Test
    fun configurationErrorGetsShortBan() {
        val message = """Unable to start libbox 1.14.3: json: unknown field "extra""""
        assertEquals(
            FailureBanPolicy.Action.BAN_SHORT,
            FailureBanPolicy.action(VpnFailureReason.CoreError(message), message)
        )
    }

    @Test
    fun bindCollisionAndLocalReasonsNeverBan() {
        val bind = "listen tcp 127.0.0.1:10808: bind: address already in use"
        assertEquals(
            FailureBanPolicy.Action.NO_BAN,
            FailureBanPolicy.action(VpnFailureReason.BindError, bind)
        )
        val tun = "Unable to start libbox 1.14.3: tun conflict"
        assertEquals(
            FailureBanPolicy.Action.NO_BAN,
            FailureBanPolicy.action(VpnFailureReason.TunConflict, tun)
        )
        assertEquals(
            FailureBanPolicy.Action.NO_BAN,
            FailureBanPolicy.action(VpnFailureReason.PermissionDenied, "permission denied")
        )
    }

    @Test
    fun unknownCoreErrorBans() {
        val message = "native crash in worker thread"
        assertEquals(
            FailureBanPolicy.Action.BAN,
            FailureBanPolicy.action(VpnFailureReason.CoreError(message), message)
        )
    }

    @Test
    fun localLibboxStartFailureDoesNotBan() {
        val message = "Unable to start libbox 1.14.3: native init failed"
        assertEquals(
            FailureBanPolicy.Action.NO_BAN,
            FailureBanPolicy.action(VpnFailureReason.CoreError(message), message)
        )
    }

    @Test
    fun eofIsMatchedAtAWordBoundary() {
        // "eof" inside another word, including the colon form, is not a remote EOF.
        listOf("geofence disabled", "geofence: disabled", "geofencing is off").forEach { message ->
            assertEquals(
                message,
                FailureBanPolicy.Action.BAN,
                FailureBanPolicy.action(VpnFailureReason.CoreError(message), message)
            )
        }
        listOf("read: unexpected eof", "EOF", "eof while reading").forEach { message ->
            assertEquals(
                message,
                FailureBanPolicy.Action.BAN,
                FailureBanPolicy.action(VpnFailureReason.CoreError(message), message)
            )
        }
        // Both EOF cases ban, so prove the boundary through the local-wrapper guard instead.
        val localWrapped = "Unable to start libbox 1.14.3: geofence: disabled"
        assertEquals(
            FailureBanPolicy.Action.NO_BAN,
            FailureBanPolicy.action(VpnFailureReason.CoreError(localWrapped), localWrapped)
        )
        val localWrappedEof = "Unable to start libbox 1.14.3: unexpected eof"
        assertEquals(
            FailureBanPolicy.Action.BAN,
            FailureBanPolicy.action(VpnFailureReason.CoreError(localWrappedEof), localWrappedEof)
        )
    }

    @Test
    fun remoteUnreachableMarkersBan() {
        listOf(
            "dial udp 198.51.100.4:443: unreachable",
            "no route to host",
            "network is unreachable",
            "read: connection reset by peer"
        ).forEach { message ->
            assertEquals(
                message,
                FailureBanPolicy.Action.BAN,
                FailureBanPolicy.action(VpnFailureReason.CoreError(message), message)
            )
        }
    }
}
