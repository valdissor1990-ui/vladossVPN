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
    fun disabledSingBoxRealityDoesNotOverrideTls() {
        val sample = """
            {
              "type": "vless",
              "server": "tls.example",
              "server_port": 443,
              "uuid": "44444444-4444-4444-8444-444444444444",
              "tls": {
                "enabled": true,
                "server_name": "example.org",
                "reality": {"enabled": false, "public_key": "unused-key", "short_id": "unused"}
              }
            }
        """.trimIndent()

        val parsed = parser.parse(sample)

        assertEquals(1, parsed.size)
        assertEquals("tls", parsed.single().security)
        assertEquals(null, parsed.single().publicKey)
        assertEquals(null, parsed.single().shortId)
    }

    @Test
    fun importsXrayVlessGrpcTlsAlpnAndFingerprintWithoutDroppingThem() {
        val sample = """
            {
              "protocol": "vless",
              "settings": {
                "vnext": [{
                  "address": "filnex.baby",
                  "port": 443,
                  "users": [{
                    "id": "11111111-1111-4111-8111-111111111111",
                    "encryption": "none",
                    "flow": ""
                  }]
                }]
              },
              "streamSettings": {
                "network": "grpc",
                "security": "tls",
                "grpcSettings": {
                  "authority": "filnex.baby",
                  "mode": false,
                  "serviceName": "lalibobi.tw.de1.Connect"
                },
                "tlsSettings": {
                  "alpn": ["h2"],
                  "enableSessionResumption": false,
                  "fingerprint": "firefox",
                  "serverName": "filnex.baby",
                  "allowInsecure": false
                }
              }
            }
        """.trimIndent()

        val parsed = parser.parse(sample, providerTag = "VLEES")

        assertEquals(1, parsed.size)
        val config = parsed.single()
        assertEquals("filnex.baby", config.address)
        assertEquals(443, config.port)
        assertEquals("grpc", config.transport)
        assertEquals("tls", config.security)
        assertEquals("lalibobi.tw.de1.Connect", config.path)
        assertEquals("filnex.baby", config.sni)
        assertEquals("firefox", config.fingerprint)
        assertEquals(listOf("h2"), config.alpn)
        assertEquals(false, config.insecure)
        assertEquals("VLEES", config.providerTag)
    }

    @Test
    fun rejectsMalformedJsonAndUnsupportedPayloads() {
        assertTrue(parser.parse("""{"protocol":""").isEmpty())
        assertTrue(parser.parse("""{"protocol":"unsupported","settings":{}}""").isEmpty())
        assertTrue(parser.parse("""{"type":"vless","server":"1.2.3.4","server_port":443}""").isEmpty())
    }

    @Test
    fun importsXrayHysteria2OutboundAndSkipsServiceOutbounds() {
        val sample = """
            {
              "inbounds": [{"tag": "socks-in", "port": 10808}],
              "outbounds": [
                {
                  "protocol": "hysteria",
                  "settings": {
                    "address": "static.court.lalibobi.online",
                    "port": 443,
                    "version": 2
                  },
                  "streamSettings": {
                    "network": "hysteria",
                    "security": "tls",
                    "hysteriaSettings": {"auth": "hy2-secret", "version": 2},
                    "tlsSettings": {
                      "alpn": ["h3"],
                      "fingerprint": "firefox",
                      "serverName": "static.court.lalibobi.online"
                    }
                  },
                  "tag": "proxy"
                },
                {"protocol": "freedom", "tag": "direct"},
                {"protocol": "blackhole", "tag": "block"}
              ],
              "routing": {"domainStrategy": "AsIs"}
            }
        """.trimIndent()

        val parsed = parser.parse(sample, providerTag = "hysteria-provider")

        assertEquals(1, parsed.size)
        val config = parsed.single()
        assertEquals("hysteria2", config.transport)
        assertEquals("static.court.lalibobi.online", config.address)
        assertEquals(443, config.port)
        assertEquals("hy2-secret", config.password)
        assertEquals("hy2-secret", config.uuid)
        assertEquals("static.court.lalibobi.online", config.sni)
        assertEquals("firefox", config.fingerprint)
        assertEquals(listOf("h3"), config.alpn)
        assertEquals("hysteria-provider", config.providerTag)
        assertTrue(config.requiresSingBox())
    }

    @Test
    fun doesNotTreatLegacyXrayHysteriaV1AsHysteria2() {
        val sample = """
            {
              "protocol": "hysteria",
              "settings": {"address": "legacy.example", "port": 443, "version": 1},
              "streamSettings": {
                "hysteriaSettings": {"auth": "legacy-secret", "version": 1}
              }
            }
        """.trimIndent()

        assertTrue(parser.parse(sample).isEmpty())
    }

}
