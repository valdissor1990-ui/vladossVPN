package com.vpnproxy.subscription

import com.vpnproxy.logging.LogCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HysteriaTuicParseTest {
    private val parser = SubscriptionParser(LogCollector())

    @Test
    fun connectionKeyKeepsTransportVariantsButIgnoresProviderAndDisplayName() {
        val config = parser.parseVlessUri(
            "vless://11111111-1111-1111-1111-111111111111@same.example.org:443" +
                "?type=tcp&security=tls&sni=same.example.org#Primary",
            providerTag = "Default"
        )!!
        val sameProfileFromAnotherProvider = config.copy(
            name = "Renamed",
            providerTag = "Provider 2"
        )
        val differentTransport = config.copy(transport = "xhttp", extra = "{}")

        assertEquals(config.connectionKey(), sameProfileFromAnotherProvider.connectionKey())
        assertFalse(config.connectionKey() == differentTransport.connectionKey())
    }

    @Test
    fun parsesHysteria2Uri() {
        val cfg = parser.parseHysteria2Uri(
            "hysteria2://secretpass@hy2.example.com:8443?sni=hy2.example.com#HY2-Node"
        )
        assertNotNull(cfg)
        assertEquals("hysteria2", cfg!!.transport)
        assertEquals("hy2.example.com", cfg.address)
        assertEquals(8443, cfg.port)
        assertEquals("secretpass", cfg.uuid)
        assertEquals("hy2.example.com", cfg.sni)
        assertEquals("secretpass", cfg.password)
        assertTrue(cfg.requiresSingBox())
    }

    @Test
    fun parsesTuicUri() {
        val cfg = parser.parseTuicUri(
            "tuic://11111111-1111-1111-1111-111111111111:pass@tuic.example.com:443?sni=tuic.example.com#TUIC-Node"
        )
        assertNotNull(cfg)
        assertEquals("tuic", cfg!!.transport)
        assertEquals("tuic.example.com", cfg.address)
        assertEquals("11111111-1111-1111-1111-111111111111", cfg.uuid)
        assertEquals("pass", cfg.password)
        assertTrue(cfg.requiresSingBox())

    }

    @Test
    fun parsesHy2AliasAndPreservesEncodedPassword() {
        val cfg = parser.parseHysteria2Uri(
            "hy2://secret%3Apass@hy2.example.com:443?sni=hy2.example.com#Alias"
        )
        assertNotNull(cfg)
        assertEquals("secret:pass", cfg!!.uuid)
        assertEquals("hysteria2", cfg.transport)
        assertEquals("Alias", cfg.name)
        assertEquals("secret:pass", cfg.password)
    }

    @Test
    fun parseMixedLinesIncludesHy2AndTuic() {
        val text = """
            hysteria2://pwd@hy2.example.com:443?sni=hy2.example.com#HY2
            tuic://00000000-0000-0000-0000-000000000002@tuic.example.com:443?sni=tuic.example.com#TUIC
        """.trimIndent()
        val configs = parser.parse(text, providerTag = "Test")
        assertTrue(configs.any { it.transport == "hysteria2" })
        assertTrue(configs.any { it.transport == "tuic" })
        assertTrue(configs.all { it.providerTag == "Test" })
    }
    @Test
    fun normalizesNullXhttpExtraToEmptyObjectWithoutWarning() {
        val cfg = parser.parseVlessUri(
            "vless://11111111-1111-1111-1111-111111111111@xhttp.example.org:443" +
                "?type=xhttp&security=reality&sni=www.example.org&fp=chrome&pbk=public-key&sid=a1b2&extra=null#XHTTP"
        )
        assertNotNull(cfg)
        assertEquals("{}", cfg!!.extra)
    }

    @Test
    fun normalizesMissingXhttpExtraToEmptyObject() {
        val cfg = parser.parseVlessUri(
            "vless://11111111-1111-1111-1111-111111111111@xhttp.example.org:443" +
                "?type=xhttp&security=reality&sni=www.example.org&fp=chrome&pbk=public-key&sid=a1b2#XHTTP"
        )
        assertNotNull(cfg)
        assertEquals("{}", cfg!!.extra)
    }

    @Test
    fun parsesVlessXhttpRealityUriAndExtraWithoutThrowing() {
        val cfg = parser.parseVlessUri(
            "vless://11111111-1111-1111-1111-111111111111@xhttp.example.org:443" +
                "?type=xhttp&security=reality&sni=www.example.org&fp=chrome&pbk=public-key&sid=a1b2" +
                "&path=%2Fxhttp&host=www.example.org&mode=stream-one" +
                "&extra=%7B%22x_padding_bytes%22%3A%22100-200%22%7D#XHTTP"
        )
        assertNotNull(cfg)
        assertEquals("xhttp", cfg!!.transport)
        assertEquals("stream-one", cfg.mode)
        assertEquals("/xhttp", cfg.path)
        assertEquals("www.example.org", cfg.host)
        assertEquals("public-key", cfg.publicKey)
        assertEquals("a1b2", cfg.shortId)
        assertTrue(cfg.requiresSingBox())
        assertTrue(cfg.extra.orEmpty().contains("x_padding_bytes"))
    }

}
