package com.vpnproxy.subscription

import com.vpnproxy.logging.LogCollector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SubscriptionParserUnicodeFragmentTest {
    private val parser = SubscriptionParser(LogCollector())

    @Test
    fun vlessUriWithRawFlagEmojiAndUnicodeName_isParsed() {
        val config = parser.parseVlessUri(
            "vless://550e8400-e29b-41d4-a716-446655440000@132.243.230.251:443" +
                "?encryption=none&type=xhttp&security=reality&sni=www.kinopoisk.ru" +
                "&fp=chrome&pbk=ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmno&sid=1234" +
                "&path=%2Fentry-xh&mode=auto#🇷🇺 Россия"
        )

        assertNotNull(config)
        assertEquals("🇷🇺 Россия", config.name)
        assertEquals("132.243.230.251", config.address)
        assertEquals(443, config.port)
        assertEquals("xhttp", config.transport)
    }


    @Test
    fun unsupportedShadowsocksCipher_isSkippedBeforeCoreStartup() {
        val configs = parser.parse(
            "ss://chacha20-poly1305:password@192.0.2.1:8388#unsupported"
        )

        assertEquals(0, configs.size)
    }

    @Test
    fun rawShadowsocksUserInfoWithSupportedCipher_isParsed() {
        val configs = parser.parse(
            "ss://chacha20-ietf-poly1305:password@192.0.2.1:8388#supported"
        )

        assertEquals(1, configs.size)
        assertEquals("chacha20-ietf-poly1305", configs.single().security)
        assertEquals("supported", configs.single().name)
    }

    @Test
    fun malformedPercentEscapeInDisplayName_doesNotDiscardConfig() {
        val config = parser.parseVlessUri(
            "vless://550e8400-e29b-41d4-a716-446655440000@192.0.2.10:443" +
                "?security=tls&sni=example.com#Free%ZZServer"
        )

        assertNotNull(config)
        assertEquals("Free%ZZServer", config.name)
    }
}
