package com.vpnproxy.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun wrappedTunConflictKeepsFatalLockAndBlocksFallback() {
        // Sing-box prefixes every startup failure with "Unable to start libbox …", including
        // a real TUN conflict. The typed reason must still be TunConflict.
        val reason = VpnFailureReason.fromMessage(
            "Unable to start libbox 1.14.3: Не удалось создать TUN. Закройте другое VPN и повторите."
        )

        assertTrue(reason is VpnFailureReason.TunConflict)
        assertFalse(reason.isRetryable())
        assertFalse(reason.canFallbackToNextServer())
        assertFalse(reason.isPresetRetryable())
    }

    @Test
    fun wrappedConfigErrorMentionsTunAllowsFallback() {
        // The app always has a tun inbound, so a config error text mentions "tun" without
        // being a conflict. It must stay a fatal-but-fallback CoreError, not a TUN lock.
        val reason = VpnFailureReason.fromMessage(
            """Unable to start libbox 1.14.3: start inbound/tun[tun-in]: json: unknown field "mtu""""
        )

        assertTrue(reason is VpnFailureReason.CoreError)
        assertFalse(reason.isRetryable())
        assertTrue(reason.canFallbackToNextServer())
        assertFalse(reason.isPresetRetryable())
    }

    @Test
    fun wrappedTunSetupNotPermittedStaysRetryable() {
        // Setting up the tun interface is a device-side step. It is not a conflict with
        // another VPN, so it must not take the fatal lock or block server fallback.
        listOf(
            "Unable to start libbox 1.14.3: start inbound/tun[tun-in]: configure tun interface: operation not permitted",
            "Unable to start libbox 1.14.3: set tun address: operation not permitted"
        ).forEach { message ->
            val reason = VpnFailureReason.fromMessage(message)
            assertTrue(message, reason is VpnFailureReason.CoreError)
            assertTrue(message, reason.isRetryable())
            assertTrue(message, reason.canFallbackToNextServer())
            assertFalse(message, reason.isPresetRetryable())
        }
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
    fun presetAutopilotOnlyAcceptsTransportFailures() {
        assertTrue(VpnFailureReason.NetworkTimeout("timeout").isPresetRetryable())
        assertTrue(VpnFailureReason.CoreError("handshake timeout").isPresetRetryable())
        assertFalse(VpnFailureReason.Unknown.isPresetRetryable())
        assertFalse(VpnFailureReason.BindError.isPresetRetryable())
        assertFalse(VpnFailureReason.PermissionDenied.isPresetRetryable())
        assertFalse(VpnFailureReason.TunConflict.isPresetRetryable())
        assertFalse(VpnFailureReason.AnotherVpnActive.isPresetRetryable())
        assertFalse(VpnFailureReason.CoreError("""json: unknown field "extra"""").isPresetRetryable())
        assertFalse(VpnFailureReason.CoreError("Unable to start libbox 1.14.3: native init failed").isPresetRetryable())
    }

    @Test
    fun localLibboxStartFailureStaysRetryableAndFallsBackWithoutPresetChange() {
        val reason = VpnFailureReason.CoreError("Unable to start libbox 1.14.3: native init failed")

        // Coordinator retries and may try the next server: a local start failure is not a
        // reason to lock the actor. The autopilot, however, must not rewrite TLS settings.
        assertTrue(reason.isRetryable())
        assertTrue(reason.canFallbackToNextServer())
        assertFalse(reason.isPresetRetryable())
    }

    @Test
    fun localTunCreationFailureDoesNotConsumePresetAndStillFallsBack() {
        // The real path wraps the platform reason in "Unable to start libbox …", so the
        // classification must survive the same string the service sees.
        val reason = VpnFailureReason.fromMessage(
            "Unable to start libbox 1.14.3: Sing-box openTun failed: cannot create fd"
        )

        // Creating TUN is a device-side step; a different server cannot fix it either, but the
        // coordinator still retries and keeps the fallback path open.
        assertTrue(reason.isRetryable())
        assertTrue(reason.canFallbackToNextServer())
        assertFalse(reason.isPresetRetryable())
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
