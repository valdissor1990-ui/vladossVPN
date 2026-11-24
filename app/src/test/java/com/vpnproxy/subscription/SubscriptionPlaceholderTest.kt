package com.vpnproxy.subscription

import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionPlaceholderTest {
    @Test
    fun loopbackAndUnspecifiedHostsArePlaceholders() {
        assertTrue(isPlaceholderAddress("127.0.0.1"))
        assertTrue(isPlaceholderAddress("127.10.20.30"))
        assertTrue(isPlaceholderAddress("0.0.0.0"))
        assertTrue(isPlaceholderAddress("0.1.2.3"))
        assertTrue(isPlaceholderAddress("localhost"))
        assertTrue(isPlaceholderAddress("LOCALHOST"))
        assertTrue(isPlaceholderAddress("::1"))
        assertTrue(isPlaceholderAddress("[::1]"))
        assertTrue(isPlaceholderAddress("::"))
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
            name = "📱 Лимит устройств"
        )
        assertFalse(stub.isValid())
    }
}
