package com.vpnproxy.core.protocols.parser

import com.vpnproxy.core.protocols.model.ParseResult
import com.vpnproxy.core.protocols.model.TlsKind
import com.vpnproxy.core.protocols.model.TransportKind
import com.vpnproxy.core.protocols.model.VlessConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VlessUriParserTest {

    private val tag = "test-provider"
    private val uuid = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun `xhttp over tls parses transport and utls`() {
        val r = VlessUriParser.parse(
            "vless://$uuid@78.159.246.85:2053?type=xhttp&security=tls&sni=fitindoor.net&host=fitindoor.net&path=%2Fapi%2Fv1%2Fupload&mode=auto&fp=chrome#VK",
            tag
        )
        assertIs<ParseResult.Success<*>>(r)
        val c = assertIs<VlessConfig>(r.value)
        assertEquals(TlsKind.TLS, c.tlsKind)
        assertEquals("fitindoor.net", c.tls?.serverName)
        assertEquals("chrome", c.tls?.utls?.fingerprint)
        assertEquals(TransportKind.XHTTP, c.transport?.type)
        assertEquals("/api/v1/upload", c.transport?.xhttp?.path)
        assertEquals("auto", c.transport?.xhttp?.mode)
        assertEquals("VK", c.remark)
    }

    @Test
    fun `reality explicit sets reality keys`() {
        val r = VlessUriParser.parse(
            "vless://$uuid@45.12.75.242:26424?security=reality&sni=ya.ru&fp=qq&pbk=l8AubqcxQO-HRFJy4pZL1vbLOo-eXit69s-XulSELE0&sid=23103e1e#Beget",
            tag
        )
        assertIs<ParseResult.Success<*>>(r)
        val c = assertIs<VlessConfig>(r.value)
        assertEquals(TlsKind.REALITY, c.tlsKind)
        assertEquals("l8AubqcxQO-HRFJy4pZL1vbLOo-eXit69s-XulSELE0", c.tls?.reality?.publicKey)
        assertEquals("23103e1e", c.tls?.reality?.shortId)
    }

    @Test
    fun `fix 11 pbk without security yields reality`() {
        val r = VlessUriParser.parse(
            "vless://$uuid@45.12.75.242:26424?sni=ya.ru&pbk=l8AubqcxQO-HRFJy4pZL1vbLOo-eXit69s-XulSELE0&sid=23103e1e",
            tag
        )
        assertIs<ParseResult.Success<*>>(r)
        val c = assertIs<VlessConfig>(r.value)
        assertEquals(TlsKind.REALITY, c.tlsKind)
        assertEquals("l8AubqcxQO-HRFJy4pZL1vbLOo-eXit69s-XulSELE0", c.tls?.reality?.publicKey)
    }

    @Test
    fun `fix 11 explicit none keeps none and warns`() {
        val r = VlessUriParser.parse(
            "vless://$uuid@45.12.75.242:26424?security=none&pbk=l8AubqcxQO-HRFJy4pZL1vbLOo-eXit69s-XulSELE0",
            tag
        )
        assertIs<ParseResult.Success<*>>(r)
        val c = assertIs<VlessConfig>(r.value)
        assertEquals(TlsKind.NONE, c.tlsKind)
        assertTrue(r.warnings.any { it.contains("reality keys present") })
    }

    @Test
    fun `grpc without serviceName is null not empty`() {
        val r = VlessUriParser.parse(
            "vless://$uuid@188.225.38.216:443?type=grpc&security=tls&sni=lalibobi.online",
            tag
        )
        assertIs<ParseResult.Success<*>>(r)
        val c = assertIs<VlessConfig>(r.value)
        assertEquals(TransportKind.GRPC, c.transport?.type)
        assertEquals(null, c.transport?.grpc?.serviceName)
    }

    @Test
    fun `ipv6 bracketed host parses`() {
        val r = VlessUriParser.parse(
            "vless://$uuid@[2001:db8::1]:443?security=tls&sni=example.com",
            tag
        )
        assertIs<ParseResult.Success<*>>(r)
        val c = assertIs<VlessConfig>(r.value)
        assertEquals("2001:db8::1", c.server)
        assertEquals(443, c.port)
    }

    @Test
    fun `duplicate query takes first`() {
        val r = VlessUriParser.parse(
            "vless://$uuid@1.2.3.4:443?type=ws&type=grpc&security=tls&sni=a.com",
            tag
        )
        assertIs<ParseResult.Success<*>>(r)
        val c = assertIs<VlessConfig>(r.value)
        assertEquals(TransportKind.WS, c.transport?.type)
    }

    @Test
    fun `invalid uuid skips`() {
        val r = VlessUriParser.parse("vless://not-a-uuid@1.2.3.4:443", tag)
        assertIs<ParseResult.Skip>(r)
    }

    @Test
    fun `bad port skips`() {
        val r = VlessUriParser.parse("vless://$uuid@1.2.3.4:99999", tag)
        assertIs<ParseResult.Skip>(r)
    }

    @Test
    fun `unknown transport unsupported`() {
        val r = VlessUriParser.parse("vless://$uuid@1.2.3.4:443?type=kcp", tag)
        assertIs<ParseResult.Unsupported>(r)
    }

    @Test
    fun `quic without tls unsupported`() {
        val r = VlessUriParser.parse("vless://$uuid@1.2.3.4:443?type=quic", tag)
        assertIs<ParseResult.Unsupported>(r)
    }

    @Test
    fun `unknown flow warns but succeeds`() {
        val r = VlessUriParser.parse("vless://$uuid@1.2.3.4:443?flow=weird&security=tls&sni=a.com", tag)
        assertIs<ParseResult.Success<*>>(r)
        assertTrue(r.warnings.any { it.contains("unsupported VLESS flow") })
    }

    @Test
    fun `unknown params preserved in extra`() {
        val r = VlessUriParser.parse("vless://$uuid@1.2.3.4:443?security=tls&sni=a.com&zzz=1&yyy=2", tag)
        assertIs<ParseResult.Success<*>>(r)
        val c = assertIs<VlessConfig>(r.value)
        assertEquals("1", c.extra["zzz"])
        assertEquals("2", c.extra["yyy"])
    }
}
