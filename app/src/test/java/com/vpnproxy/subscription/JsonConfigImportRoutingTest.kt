package com.vpnproxy.subscription

import com.vpnproxy.logging.LogCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the parser used by JSON import, rather than checking only the first character. */
class JsonConfigImportRoutingTest {
    private val parser = SubscriptionParser(LogCollector())

    @Test
    fun parsesSupportedVlessJsonObject() {
        val sample = """
            {
              "protocol": "vless",
              "settings": {
                "vnext": [{
                  "address": "1.2.3.4",
                  "port": 443,
                  "users": [{"id": "11111111-1111-4111-8111-111111111111", "flow": ""}]
                }]
              },
              "streamSettings": {
                "network": "tcp",
                "security": "tls",
                "tlsSettings": {"serverName": "example.org"}
              }
            }
        """.trimIndent()

        val parsed = parser.parse(sample, providerTag = "imported")

        assertEquals(1, parsed.size)
        assertEquals("1.2.3.4", parsed.single().address)
        assertEquals(443, parsed.single().port)
        assertEquals("11111111-1111-4111-8111-111111111111", parsed.single().uuid)
        assertEquals("imported", parsed.single().providerTag)
    }

    @Test
    fun parsesSupportedVlessJsonArray() {
        val sample = """
            [{
              "protocol": "vless",
              "settings": {
                "vnext": [{
                  "address": "server.example",
                  "port": 8443,
                  "users": [{"id": "22222222-2222-4222-8222-222222222222"}]
                }]
              }
            }]
        """.trimIndent()

        val parsed = parser.parse(sample)

        assertEquals(1, parsed.size)
        assertEquals("server.example", parsed.single().address)
        assertEquals(8443, parsed.single().port)
    }

    @Test
    fun parsesSingBoxVlessOutbound() {
        val sample = """
            {
              "type": "vless",
              "tag": "my-server",
              "server": "singbox.example",
              "server_port": 443,
              "uuid": "33333333-3333-4333-8333-333333333333",
              "tls": {
                "enabled": true,
                "server_name": "example.org",
                "utls": {"enabled": true, "fingerprint": "chrome"}
              },
              "transport": {"type": "ws", "path": "/vpn", "headers": {"Host": "example.org"}}
            }
        """.trimIndent()

        val parsed = parser.parse(sample, providerTag = "sing-box")

        assertEquals(1, parsed.size)
        assertEquals("singbox.example", parsed.single().address)
        assertEquals("ws", parsed.single().transport)
        assertEquals("/vpn", parsed.single().path)
        assertEquals("example.org", parsed.single().sni)
        assertEquals("sing-box", parsed.single().providerTag)
    }

    @Test
    fun rejectsMalformedJsonAndUnsupportedPayloads() {
        assertTrue(parser.parse("""{"protocol":""").isEmpty())
        assertTrue(parser.parse("""{"protocol":"unsupported","settings":{}}""").isEmpty())
        assertTrue(parser.parse("""{"type":"vless","server":"1.2.3.4","server_port":443}""").isEmpty())
    }
}
