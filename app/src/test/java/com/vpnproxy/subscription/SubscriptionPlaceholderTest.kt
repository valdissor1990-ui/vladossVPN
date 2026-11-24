package com.vpnproxy.subscription

import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SubscriptionPlaceholderTest {
    @Test
    fun loopbackAndUnspecifiedHostsArePlaceholders() {
        assertTrue(isPlaceholderAddress("127.0.0.1"))
        assertTrue(isPlaceholderAddress("127.10.20.30"))
        assertTrue(isPlaceholderAddress("0.0.0.0"))
        assertTrue(isPlaceholderAddress("0.1.2.3"))
        assertTrue(isPlaceholderAddress("localhost"))
        assertTrue(isPlaceholderAddress("LOCALHOST"))
        assertTrue(isPlaceholderAddress("api.localhost"))
        assertTrue(isPlaceholderAddress("::1"))
        assertTrue(isPlaceholderAddress("[::1]"))
        assertTrue(isPlaceholderAddress("::"))
        assertTrue(isPlaceholderAddress("0:0:0:0:0:0:0:1"))
        assertTrue(isPlaceholderAddress("::ffff:127.0.0.1"))
    }

    @Test
    fun realHostsAreNotPlaceholders() {
        assertFalse(isPlaceholderAddress("1.1.1.1"))
        assertFalse(isPlaceholderAddress("8.8.8.8"))
        assertFalse(isPlaceholderAddress("example.com"))
        assertFalse(isPlaceholderAddress("2606:4700:4700::1111"))
        assertFalse(isPlaceholderAddress(""))
    }

    @Test
    fun deviceLimitStubProfileIsRejectedByValidation() {
        val stub = ParsedConfig(
            uuid = "00000000-0000-0000-0000-000000000000",
            address = "127.0.0.1",
            port = 443,
            transport = "tcp",
            security = "none",
            sni = null,
            path = null,
            host = null,
            mode = null,
            fingerprint = null,
            publicKey = null,
            shortId = null,
            flow = null,
            name = "Лимит устройств"
        )
        assertFalse(stub.isValid())
    }

    @Test
    fun placeholderInHostOrSniIsRejectedByValidation() {
        val base = ParsedConfig(
            uuid = "11111111-1111-1111-1111-111111111111",
            address = "203.0.113.10",
            port = 443,
            transport = "tcp",
            security = "tls",
            sni = "example.com",
            path = null,
            host = null,
            mode = null,
            fingerprint = null,
            publicKey = null,
            shortId = null,
            flow = null,
            name = "Real"
        )
        assertTrue(base.isValid())
        assertFalse(base.copy(host = "127.0.0.1").isValid())
        assertFalse(base.copy(sni = "::1").isValid())
    }

    @Test
    fun plainAndBase64DeviceLimitStubsAreDetected() {
        val stub = "vless://00000000-0000-0000-0000-000000000000@127.0.0.1:443?security=none#Device limit"
        assertTrue(looksLikeDeviceLimitStub(stub))
        assertTrue(looksLikeDeviceLimitStub(Base64.getEncoder().encodeToString(stub.toByteArray())))
        assertTrue(looksLikeDeviceLimitStub(Base64.getUrlEncoder().withoutPadding().encodeToString(stub.toByteArray())))
        assertTrue(looksLikeDeviceLimitStub("Subscription error: лимит устройств достигнут"))
    }

    @Test
    fun realSubscriptionIsNotAStub() {
        val real = "vless://11111111-1111-1111-1111-111111111111@203.0.113.10:443?security=tls#Real server"
        assertFalse(looksLikeDeviceLimitStub(real))
        assertFalse(looksLikeDeviceLimitStub(Base64.getEncoder().encodeToString(real.toByteArray())))
    }
}
