package com.vpnproxy.subscription

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `looksLikeConfigUri` decides whether pasted text (typed, pasted or decoded from a QR code) is a
 * single config link rather than a subscription URL or a JSON document.
 */
class SubscriptionParserConfigUriTest {

    @Test
    fun supportedSchemesAreRecognised() {
        val links = listOf(
            "vless://uuid@example.com:443",
            "hysteria2://secret@example.com:443",
            "hy2://secret@example.com:443",
            "tuic://uuid:secret@example.com:443",
            "ss://method:password@example.com:8388",
            "vmess://eyJ2IjoiMiJ9",
            "trojan://secret@example.com:443",
            "socks://user:pass@example.com:1080",
            "socks5://user:pass@example.com:1080"
        )

        links.forEach { assertTrue(SubscriptionParser.looksLikeConfigUri(it), it) }
    }

    @Test
    fun schemeMatchIsCaseInsensitiveAndIgnoresSurroundingWhitespace() {
        assertTrue(SubscriptionParser.looksLikeConfigUri("  VLESS://uuid@example.com:443  "))
        assertTrue(SubscriptionParser.looksLikeConfigUri("\nHy2://secret@example.com:443"))
    }

    @Test
    fun subscriptionUrlsAndPlainTextAreRejected() {
        assertFalse(SubscriptionParser.looksLikeConfigUri("https://provider.example/sub?token=1"))
        assertFalse(SubscriptionParser.looksLikeConfigUri("http://provider.example/sub"))
        assertFalse(SubscriptionParser.looksLikeConfigUri("{\"outbounds\":[]}"))
        assertFalse(SubscriptionParser.looksLikeConfigUri("just some text"))
        assertFalse(SubscriptionParser.looksLikeConfigUri(""))
        assertFalse(SubscriptionParser.looksLikeConfigUri("   "))
        assertFalse(SubscriptionParser.looksLikeConfigUri("vless"))
    }

    @Test
    fun unsupportedSchemeIsRejected() {
        assertFalse(SubscriptionParser.looksLikeConfigUri("wireguard://key@example.com:51820"))
    }
}
