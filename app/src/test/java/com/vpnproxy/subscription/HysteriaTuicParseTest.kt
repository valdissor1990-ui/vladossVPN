package com.vpnproxy.subscription

import com.vpnproxy.logging.LogCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HysteriaTuicParseTest {
    private val parser = SubscriptionParser(LogCollector())

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
        assertTrue(cfg.requiresSingBox())
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
}
