package com.vpnproxy.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VpnFailureReasonTest {
    @Test
    fun libboxUnknownFieldIsFatalAndShowsConfigRepairMessage() {
        val reason = VpnFailureReason.fromMessage(
            """Unable to start libbox 1.14.3: outbounds[0].transport.extra: json: unknown field "extra""""
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
}
