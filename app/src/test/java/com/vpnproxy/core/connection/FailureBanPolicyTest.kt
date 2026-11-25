package com.vpnproxy.core.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class FailureBanPolicyTest {

    @Test
    fun remoteDialTimeoutWrappedByLibboxStillBans() {
        val message = "Unable to start libbox 1.14.3: dial tcp 203.0.113.7:443: i/o timeout"
        assertEquals(
            FailureBanPolicy.Action.BAN_NETWORK,
            FailureBanPolicy.action(VpnFailureReason.CoreError(message), message)
        )
    }

    @Test
    fun localStartTimeoutDoesNotBan() {
        // A local CommandServer / TUN setup timeout has no dial evidence: the server is fine.
        val message = "Unable to start libbox 1.14.3: context deadline exceeded"
        assertEquals(
            FailureBanPolicy.Action.NO_BAN,
            FailureBanPolicy.action(VpnFailureReason.CoreError(message), message)
        )
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
            FailureBanPolicy.Action.BAN_NETWORK,
            FailureBanPolicy.action(VpnFailureReason.CoreError(message), message)
        )
        assertEquals(
            FailureBanPolicy.Action.BAN_NETWORK,
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
        val tun = "Unable to start libbox 1.14.3: Не удалось создать TUN."
        assertEquals(
            FailureBanPolicy.Action.NO_BAN,
            FailureBanPolicy.action(VpnFailureReason.TunConflict, tun)
        )
    }

    @Test
    fun unknownCoreErrorBans() {
        val message = "native crash in worker thread"
        assertEquals(
            FailureBanPolicy.Action.BAN_OTHER,
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
        // "eof" inside another word must not be read as a remote EOF.
        assertEquals(
            FailureBanPolicy.Action.BAN_OTHER,
            FailureBanPolicy.action(VpnFailureReason.CoreError("geofence disabled"), "geofence disabled")
        )
        assertEquals(
            FailureBanPolicy.Action.BAN_NETWORK,
            FailureBanPolicy.action(VpnFailureReason.CoreError("read: unexpected eof"), "read: unexpected eof")
        )
    }
}
