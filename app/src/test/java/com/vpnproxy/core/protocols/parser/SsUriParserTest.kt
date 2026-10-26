package com.vpnproxy.core.protocols.parser

import com.vpnproxy.core.protocols.model.ParseResult
import com.vpnproxy.core.protocols.model.ShadowsocksConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SsUriParserTest {

    private val tag = "prov"

    @Test
    fun `sip002 base64 userinfo current method`() {
        val userinfo = java.util.Base64.getEncoder()
            .encodeToString("aes-256-gcm:secret".toByteArray(Charsets.UTF_8))
        val r = SsUriParser.parse("ss://$userinfo@1.2.3.4:8388#SS", tag)
        assertIs<ParseResult.Success<*>>(r)
        val c = assertIs<ShadowsocksConfig>(r.value)
        assertEquals("aes-256-gcm", c.method)
        assertEquals("secret", c.password)
        assertEquals("1.2.3.4", c.server)
        assertEquals(8388, c.port)
    }

    @Test
    fun `sip002 plaintext method password`() {
        val r = SsUriParser.parse("ss://chacha20-ietf-poly1305:pw@1.2.3.4:8388#SS", tag)
        assertIs<ParseResult.Success<*>>(r)
        val c = assertIs<ShadowsocksConfig>(r.value)
        assertEquals("chacha20-ietf-poly1305", c.method)
        assertEquals("pw", c.password)
    }

    @Test
    fun `legacy base64 whole authority`() {
        val inner = "aes-256-gcm:secret@1.2.3.4:8388"
        val encoded = java.util.Base64.getEncoder()
            .encodeToString(inner.toByteArray(Charsets.UTF_8))
        val r = SsUriParser.parse("ss://$encoded#SS", tag)
        assertIs<ParseResult.Success<*>>(r)
        val c = assertIs<ShadowsocksConfig>(r.value)
        assertEquals("aes-256-gcm", c.method)
        assertEquals("1.2.3.4", c.server)
    }

    @Test
    fun `2022 blake3 method accepted`() {
        val userinfo = java.util.Base64.getEncoder()
            .encodeToString("2022-blake3-aes-256-gcm:keykeykeykeykeykeykeykeykeykeykey=".toByteArray(Charsets.UTF_8))
        val r = SsUriParser.parse("ss://$userinfo@1.2.3.4:8388#SS", tag)
        assertIs<ParseResult.Success<*>>(r)
        val c = assertIs<ShadowsocksConfig>(r.value)
        assertEquals("2022-blake3-aes-256-gcm", c.method)
    }

    @Test
    fun `legacy cipher warns but succeeds`() {
        val userinfo = java.util.Base64.getEncoder()
            .encodeToString("aes-256-cfb:secret".toByteArray(Charsets.UTF_8))
        val r = SsUriParser.parse("ss://$userinfo@1.2.3.4:8388#SS", tag)
        assertIs<ParseResult.Success<*>>(r)
        assertTrue(r.warnings.any { it.contains("deprecated method") })
    }

    @Test
    fun `unknown cipher unsupported`() {
        val userinfo = java.util.Base64.getEncoder()
            .encodeToString("bogus-cipher:secret".toByteArray(Charsets.UTF_8))
        val r = SsUriParser.parse("ss://$userinfo@1.2.3.4:8388#SS", tag)
        assertIs<ParseResult.Unsupported>(r)
        assertTrue(r.reason.contains("unknown cipher"))
    }

    @Test
    fun `empty password skipped`() {
        val userinfo = java.util.Base64.getEncoder()
            .encodeToString("aes-256-gcm:".toByteArray(Charsets.UTF_8))
        val r = SsUriParser.parse("ss://$userinfo@1.2.3.4:8388#SS", tag)
        assertIs<ParseResult.Skip>(r)
    }

    @Test
    fun `plugin parsed with warning`() {
        val userinfo = java.util.Base64.getEncoder()
            .encodeToString("aes-256-gcm:secret".toByteArray(Charsets.UTF_8))
        val r = SsUriParser.parse("ss://$userinfo@1.2.3.4:8388?plugin=obfs-local%3Bobfs%3Dhttp#SS", tag)
        assertIs<ParseResult.Success<*>>(r)
        val c = assertIs<ShadowsocksConfig>(r.value)
        assertEquals("obfs-local", c.plugin)
        assertTrue(r.warnings.any { it.contains("ss plugin parsed") })
    }

    @Test
    fun `uot preserved in extra with warning`() {
        val userinfo = java.util.Base64.getEncoder()
            .encodeToString("aes-256-gcm:secret".toByteArray(Charsets.UTF_8))
        val r = SsUriParser.parse("ss://$userinfo@1.2.3.4:8388?uot=1#SS", tag)
        assertIs<ParseResult.Success<*>>(r)
        val c = assertIs<ShadowsocksConfig>(r.value)
        assertEquals("1", c.extra["udp_over_tcp"])
        assertTrue(r.warnings.any { it.contains("udp_over_tcp") })
    }
}
