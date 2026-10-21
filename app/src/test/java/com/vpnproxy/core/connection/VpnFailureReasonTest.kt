package com.vpnproxy.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnFailureReasonTest {
    @Test
    fun libboxUnknownFieldIsFatalAndShowsConfigRepairMessage() {
        val reason = VpnFailureReason.fromMessage(
            """Unable to start libbox 1.14.3: start or reload service: decode config: outbounds[0].transport.x_padding_bytes: json: unknown field "x_padding_bytes""""
        )

        assertFalse(reason.isRetryable())
        assertEquals(
            "Ошибка конфигурации сервера. Попробуйте обновить подписку.",
            reason.userMessage()
        )
    }

    @Test
    fun libboxIllegalStateExceptionIsFatal() {
        val reason = VpnFailureReason.fromMessage(
            "java.lang.IllegalStateException: invalid config"
        )

        assertFalse(reason.isRetryable())
        assertEquals(
            "Ошибка конфигурации сервера. Попробуйте обновить подписку.",
            reason.userMessage()
        )
    }

    @Test
    fun genericLibboxStartupFailureIsNotMisclassifiedAsConfigError() {
        val reason = VpnFailureReason.fromMessage(
            "Unable to start libbox 1.14.3: runtime startup failure"
        )

        assertTrue(reason.isRetryable())
        assertEquals(
            "Unable to start libbox 1.14.3: runtime startup failure",
            reason.userMessage()
        )
    }
}
