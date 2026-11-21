package com.vpnproxy.core.connection

import com.vpnproxy.core.probe.ProbeOutcome
import com.vpnproxy.logging.LogCollector
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class TrafficValidatorTest {

    private val validator = TrafficValidator(LogCollector())

    @Test
    fun socketTimeout_isTimeout() {
        assertEquals(ProbeOutcome.TIMEOUT, validator.mapException(SocketTimeoutException("connect timed out")))
    }

    @Test
    fun unknownHost_isDnsError() {
        assertEquals(ProbeOutcome.DNS_ERROR, validator.mapException(UnknownHostException("api.example.org")))
    }

    @Test
    fun connectionRefused_isRefused() {
        assertEquals(ProbeOutcome.REFUSED, validator.mapException(ConnectException("Connection refused")))
    }

    @Test
    fun networkUnreachableConnectException_isUnreachable_notRefused() {
        // On Android ENETUNREACH can surface as ConnectException; it must not be
        // reported as a refused connection.
        assertEquals(
            ProbeOutcome.UNREACHABLE,
            validator.mapException(ConnectException("Network is unreachable"))
        )
        assertEquals(
            ProbeOutcome.UNREACHABLE,
            validator.mapException(SocketException("Network is unreachable"))
        )
    }

    @Test
    fun messageTimeout_onGenericException_isTimeout() {
        assertEquals(ProbeOutcome.TIMEOUT, validator.mapException(RuntimeException("connect timed out")))
    }

    @Test
    fun resolveMessage_onGenericException_isDnsError() {
        assertEquals(
            ProbeOutcome.DNS_ERROR,
            validator.mapException(SocketException("Unable to resolve host \"example.org\": No address associated"))
        )
    }

    @Test
    fun unknownFailure_isIoError() {
        assertEquals(ProbeOutcome.IO_ERROR, validator.mapException(RuntimeException("something odd")))
    }
}
