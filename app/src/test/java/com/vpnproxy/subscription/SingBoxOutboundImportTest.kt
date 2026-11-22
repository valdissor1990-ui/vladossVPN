package com.vpnproxy.subscription

import com.vpnproxy.logging.LogCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** sing-box JSON payloads may mix every outbound type, not just VLESS. */
class SingBoxOutboundImportTest {
    private val parser = SubscriptionParser(LogCollector())

    private val mixedJson = """
        {
          "outbounds": [
            {
              "type": "vless",
              "tag": "vless-node",
              "server": "vless.example.com",
              "server_port": 443,
              "uuid": "11111111-1111-4111-8111-111111111111",
              "tls": {"enabled": true, "server_name": "vless-sni.example.com"}
            },
            {
              "type": "vmess",
              "tag": "vmess-node",
              "server": "vmess.example.com",
              "server_port": 8443,
              "uuid": "22222222-2222-4222-8222-222222222222",
              "tls": {"enabled": true, "server_name": "vmess-sni.example.com"},
              "transport": {"type": "ws", "path": "/vm", "headers": {"Host": "vm.example.com"}}
            },
            {
              "type": "trojan",
              "tag": "trojan-node",
              "server": "trojan.example.com",
              "server_port": 443,
              "password": "trojan-pass",
              "tls": {"enabled": true}
            },
            {
              "type": "shadowsocks",
              "tag": "ss-node",
              "server": "ss.example.com",
              "server_port": 8388,
              "method": "aes-256-gcm",
              "password": "ss-pass"
            },
            {
              "type": "hysteria2",
              "tag": "hy2-node",
              "server": "hy.example.com",
              "server_port": 443,
              "password": "hy-pass",
              "obfs": {"type": "salamander", "password": "obfs-pass"},
              "tls": {"enabled": true}
            },
            {
              "type": "tuic",
              "tag": "tuic-node",
              "server": "tuic.example.com",
              "server_port": 443,
              "uuid": "tuic-uuid",
              "password": "tuic-pass",
              "congestion_control": "cubic",
              "tls": {"enabled": true}
            },
            {
              "type": "socks",
              "tag": "socks-node",
              "server": "10.0.0.5",
              "server_port": 1080,
              "username": "user",
              "password": "pass"
            },
            {
              "type": "wireguard",
              "tag": "unsupported-node",
              "server": "wg.example.com",
              "server_port": 51820
            }
          ]
        }
    """.trimIndent()

    @Test
    fun importsEverySupportedSingBoxOutboundType() {
        val outcome = parser.parseWithReport(mixedJson, providerTag = "json")

        assertEquals("json", outcome.report.detectedFormat)
        assertEquals(7, outcome.configs.size)
        val transports = outcome.configs.associate { it.name to it.transport }
        assertEquals("tcp", transports["vless-node"])
        assertEquals("vmess", transports["vmess-node"])
        assertEquals("trojan", transports["trojan-node"])
        assertEquals("shadowsocks", transports["ss-node"])
        assertEquals("hysteria2", transports["hy2-node"])
        assertEquals("tuic", transports["tuic-node"])
        assertEquals("socks", transports["socks-node"])
        assertEquals(1, outcome.report.rejectionReasons["unsupported_protocol"])
    }

    @Test
    fun mapsTransportSpecificFields() {
        val byName = parser.parse(mixedJson, providerTag = "json").associateBy { it.name }

        assertEquals("/vm", byName.getValue("vmess-node").path)
        assertEquals("vm.example.com", byName.getValue("vmess-node").host)
        assertEquals("ws", byName.getValue("vmess-node").mode)
        assertEquals("trojan-pass", byName.getValue("trojan-node").password)
        assertEquals("aes-256-gcm", byName.getValue("ss-node").security)
        assertEquals("salamander", byName.getValue("hy2-node").obfsType)
        assertEquals("cubic", byName.getValue("tuic-node").congestionControl)
        assertEquals("user", byName.getValue("socks-node").uuid)
        assertTrue(byName.getValue("vless-node").security == "tls")
    }
}
