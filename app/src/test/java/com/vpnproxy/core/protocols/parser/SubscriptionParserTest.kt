package com.vpnproxy.core.protocols.parser

import com.vpnproxy.core.protocols.model.ParseResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SubscriptionParserTest {

    private val parser = SubscriptionParser()
    private val tag = "prov"
    private val uuid = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun `plain list parses multiple`() {
        val raw = """
            vless://$uuid@1.2.3.4:443?security=tls&sni=a.com#A
            # comment line
            trojan://pass@5.6.7.8:443?sni=b.com#B
        """.trimIndent()
        val r = parser.parse(raw, tag)
        assertIs<ParseResult.Success<*>>(r)
        assertEquals(2, r.value.size)
    }

    @Test
    fun `base64 blob detected and parsed`() {
        val inner = "vless://$uuid@1.2.3.4:443?security=tls&sni=a.com#A"
        val encoded = java.util.Base64.getEncoder()
            .encodeToString(inner.toByteArray(Charsets.UTF_8))
        val r = parser.parse(encoded, tag)
        assertIs<ParseResult.Success<*>>(r)
        assertEquals(1, r.value.size)
    }

    @Test
    fun `unknown format skipped`() {
        val r = parser.parse("this is not a subscription", tag)
        assertIs<ParseResult.Skip>(r)
    }

    @Test
    fun `empty input skipped`() {
        val r = parser.parse("", tag)
        assertIs<ParseResult.Skip>(r)
    }

    @Test
    fun `all invalid lines yield skip with aggregated reasons`() {
        val raw = "vless://bad\nvmess://@@@\nss://nope"
        val r = parser.parse(raw, tag)
        assertIs<ParseResult.Skip>(r)
        assertTrue(r.reason.contains("no valid configs found"))
    }

    @Test
    fun `warnings aggregated not spammed`() {
        val raw = """
            vless://$uuid@1.2.3.4:443?security=tls&sni=a.com&flow=weird#A
            vless://$uuid@1.2.3.5:443?security=tls&sni=b.com&flow=weird#B
        """.trimIndent()
        val r = parser.parse(raw, tag)
        assertIs<ParseResult.Success<*>>(r)
        val flowWarns = r.warnings.count { it.contains("unsupported VLESS flow") }
        assertEquals(2, flowWarns)
    }
}
