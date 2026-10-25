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

    @Test
    fun profileConfigurationErrorAllowsFallbackButRemainsFatalWithoutCandidates() {
        val reason = VpnFailureReason.fromMessage(
            """Unable to start libbox 1.14.3: outbounds[0].transport.session_id_placement: json: unknown field"""
        )

        assertFalse(reason.isRetryable())
        org.junit.Assert.assertEquals(true, reason.canFallbackToNextServer())
        org.junit.Assert.assertEquals(false, VpnFailureReason.PermissionDenied.canFallbackToNextServer())
    }

    @Test
    fun decodeConfigSchemaErrorsAllowFallback() {
        val reason = VpnFailureReason.fromMessage(
            "libbox failed to parse config: configuration schema mismatch"
        )
        assertFalse(reason.isRetryable())
        org.junit.Assert.assertEquals(true, reason.canFallbackToNextServer())
        assertEquals(
            "Ошибка конфигурации сервера. Попробуйте обновить подписку.",
            reason.userMessage()
        )
    }
}
