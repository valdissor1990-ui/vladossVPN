package com.vpnproxy.subscription

import com.vpnproxy.logging.LogCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clash subscriptions are the most common provider format, so mixed-protocol payloads
 * must import fully instead of keeping only the VLESS entries.
 */
class ClashSubscriptionImportTest {
    private val parser = SubscriptionParser(LogCollector())

    private val mixedClash = """
        port: 7890
        socks-port: 7891
        proxies:
          - name: vless-ws
            type: vless
            server: vless.example.com
            port: 443
            uuid: 11111111-1111-4111-8111-111111111111
            network: ws
            tls: true
            servername: vless-sni.example.com
            ws-opts:
              path: /ws
              headers:
                Host: cdn.example.com
          - name: vmess-grpc
            type: vmess
            server: vmess.example.com
            port: 8443
            uuid: 22222222-2222-4222-8222-222222222222
            cipher: auto
            network: grpc
            tls: true
            grpc-opts:
              grpc-service-name: svc
          - name: trojan-node
            type: trojan
            server: trojan.example.com
            port: 443
            password: trojan-pass
            sni: trojan-sni.example.com
          - name: ss-node
            type: ss
            server: ss.example.com
            port: 8388
            cipher: aes-256-gcm
            password: ss-pass
          - name: socks-node
            type: socks5
            server: 10.0.0.5
            port: 1080
            username: user
            password: pass
          - name: hy2-node
            type: hysteria2
            server: hy.example.com
            port: 443
            password: hy-pass
            obfs: salamander
            obfs-password: obfs-pass
          - name: tuic-node
            type: tuic
            server: tuic.example.com
            port: 443
            uuid: tuic-uuid
            password: tuic-pass
          - name: http-node
            type: http
            server: http.example.com
            port: 8080
        proxy-groups:
          - name: auto
            type: url-test
            proxies:
              - vless-ws
    """.trimIndent()

    @Test
    fun importsEverySupportedClashProtocol() {
        val outcome = parser.parseWithReport(mixedClash, providerTag = "clash")

        assertEquals("clash_yaml", outcome.report.detectedFormat)
        assertEquals(7, outcome.configs.size)
        // VLESS keeps its stream network in `transport`, the other types keep their name.
        val transports = outcome.configs.associate { it.name to it.transport }
        assertEquals("ws", transports["vless-ws"])
        assertEquals("vmess", transports["vmess-grpc"])
        assertEquals("trojan", transports["trojan-node"])
        assertEquals("shadowsocks", transports["ss-node"])
        assertEquals("socks", transports["socks-node"])
        assertEquals("hysteria2", transports["hy2-node"])
        assertEquals("tuic", transports["tuic-node"])
        assertEquals(1, outcome.report.rejectionReasons["unsupported_protocol"])
    }

    @Test
    fun keepsProtocolSpecificCredentials() {
        val byName = parser.parse(mixedClash, providerTag = "clash").associateBy { it.name }

        assertEquals("trojan-pass", byName.getValue("trojan-node").password)
        assertEquals("aes-256-gcm", byName.getValue("ss-node").security)
        assertEquals("user", byName.getValue("socks-node").uuid)
        assertEquals("salamander", byName.getValue("hy2-node").obfsType)
        assertEquals("tuic-uuid", byName.getValue("tuic-node").uuid)
        assertEquals("/ws", byName.getValue("vless-ws").path)
        assertEquals("cdn.example.com", byName.getValue("vless-ws").host)
        assertTrue(byName.getValue("vmess-grpc").mode == "grpc")
    }
    @Test
    fun importsJsonClashProxyArrayAndReportsRejectedEntries() {
        val jsonClash = """
            {
              "proxies": [
                {
                  "name": "json-vless",
                  "type": "vless",
                  "server": "vless.example.com",
                  "port": 443,
                  "uuid": "11111111-1111-4111-8111-111111111111",
                  "network": "ws",
                  "tls": true
                },
                {
                  "name": "json-vmess",
                  "type": "vmess",
                  "server": "vmess.example.com",
                  "port": 8443,
                  "uuid": "22222222-2222-4222-8222-222222222222",
                  "cipher": "auto"
                },
                {
                  "name": "unsupported",
                  "type": "wireguard",
                  "server": "wg.example.com",
                  "port": 51820
                }
              ],
              "proxy-groups": []
            }
        """.trimIndent()

        val outcome = parser.parseWithReport(jsonClash, providerTag = "json-clash")

        assertEquals("json", outcome.report.detectedFormat)
        assertEquals(3, outcome.report.candidateCount)
        assertEquals(2, outcome.report.acceptedCount)
        assertEquals(1, outcome.report.rejectedCount)
        assertEquals(1, outcome.report.rejectionReasons["unsupported_protocol"])
        assertEquals(setOf("json-vless", "json-vmess"), outcome.configs.map { it.name }.toSet())
    }
}
