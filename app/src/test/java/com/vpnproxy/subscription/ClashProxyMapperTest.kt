package com.vpnproxy.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClashProxyMapperTest {
    private fun map(vararg pairs: Pair<String, Any?>): Map<String, Any> =
        pairs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()

    @Test
    fun vlessRealityIsMappedWithRealityCredentials() {
        val proxy = map(
            "name" to "reality-node",
            "type" to "vless",
            "server" to "example.com",
            "port" to 443,
            "uuid" to "11111111-1111-1111-1111-111111111111",
            "tls" to true,
            "network" to "tcp",
            "servername" to "sni.example.com",
            "flow" to "xtls-rprx-vision",
            "client-fingerprint" to "chrome",
            "reality-opts" to mapOf("public-key" to "PUBKEY", "short-id" to "abcd")
        )

        val config = ClashProxyMapper.toParsedConfig(proxy, "prov")!!

        // VLESS stores the stream network in `transport`; "tcp" is still a VLESS outbound.
        assertEquals("tcp", config.transport)
        assertEquals("reality", config.security)
        assertEquals("sni.example.com", config.sni)
        assertEquals("PUBKEY", config.publicKey)
        assertEquals("abcd", config.shortId)
        assertEquals("xtls-rprx-vision", config.flow)
        assertEquals("chrome", config.fingerprint)
    }

    @Test
    fun vlessWebSocketUsesWsOptions() {
        val proxy = map(
            "type" to "vless",
            "server" to "example.com",
            "port" to 443,
            "uuid" to "u",
            "network" to "ws",
            "tls" to true,
            "ws-opts" to mapOf(
                "path" to "/ws",
                "headers" to mapOf("Host" to "cdn.example.com")
            )
        )

        val config = ClashProxyMapper.toParsedConfig(proxy, "")!!

        assertEquals("ws", config.transport)
        assertEquals("/ws", config.path)
        assertEquals("cdn.example.com", config.host)
    }

    @Test
    fun h2NetworkBecomesHttpTransportWithTls() {
        val proxy = map(
            "type" to "vless",
            "server" to "example.com",
            "port" to 443,
            "uuid" to "u",
            "network" to "h2",
            "h2-opts" to mapOf("path" to "/h2", "host" to listOf("h2.example.com"))
        )

        val config = ClashProxyMapper.toParsedConfig(proxy, "")!!

        // sing-box calls the HTTP/2 transport "http"; leaving "h2" here would make
        // the outbound builder throw at connect time.
        assertEquals("http", config.transport)
        assertEquals("tls", config.security)
        assertEquals("/h2", config.path)
        assertEquals("h2.example.com", config.host)
    }

    @Test
    fun vmessKeepsNetworkInModeField() {
        val proxy = map(
            "type" to "vmess",
            "server" to "vmess.example.com",
            "port" to 8443,
            "uuid" to "vmess-uuid",
            "cipher" to "auto",
            "network" to "grpc",
            "tls" to true,
            "grpc-opts" to mapOf("grpc-service-name" to "svc")
        )

        val config = ClashProxyMapper.toParsedConfig(proxy, "")!!

        assertEquals("vmess", config.transport)
        assertEquals("grpc", config.mode)
        assertEquals("svc", config.path)
        assertEquals("tls", config.security)
    }

    @Test
    fun trojanCarriesPasswordAndStreamNetwork() {
        val proxy = map(
            "type" to "trojan",
            "server" to "trojan.example.com",
            "port" to 443,
            "password" to "secret",
            "network" to "ws",
            "sni" to "trojan-sni.example.com",
            "skip-cert-verify" to true,
            "ws-opts" to mapOf("path" to "/tj")
        )

        val config = ClashProxyMapper.toParsedConfig(proxy, "")!!

        assertEquals("trojan", config.transport)
        assertEquals("secret", config.password)
        assertEquals("secret", config.uuid)
        assertEquals("ws", config.mode)
        assertEquals("/tj", config.path)
        assertTrue(config.insecure)
    }

    @Test
    fun shadowsocksUsesCipherAsMethod() {
        val proxy = map(
            "type" to "ss",
            "server" to "ss.example.com",
            "port" to 8388,
            "cipher" to "aes-256-gcm",
            "password" to "ss-pass"
        )

        val config = ClashProxyMapper.toParsedConfig(proxy, "")!!

        assertEquals("shadowsocks", config.transport)
        assertEquals("aes-256-gcm", config.security)
        assertEquals("ss-pass", config.password)
    }

    @Test
    fun socks5MapsUsernameAndPassword() {
        val proxy = map(
            "type" to "socks5",
            "server" to "127.0.0.1",
            "port" to 1080,
            "username" to "user",
            "password" to "pass"
        )

        val config = ClashProxyMapper.toParsedConfig(proxy, "")!!

        assertEquals("socks", config.transport)
        assertEquals("user", config.uuid)
        assertEquals("pass", config.password)
    }

    @Test
    fun hysteria2MapsObfsAndInsecure() {
        val proxy = map(
            "type" to "hysteria2",
            "server" to "hy.example.com",
            "port" to 443,
            "password" to "hy-pass",
            "sni" to "hy-sni.example.com",
            "obfs" to "salamander",
            "obfs-password" to "obfs-pass",
            "skip-cert-verify" to true,
            "alpn" to listOf("h3")
        )

        val config = ClashProxyMapper.toParsedConfig(proxy, "")!!

        assertEquals("hysteria2", config.transport)
        assertEquals("hy-pass", config.password)
        assertEquals("salamander", config.obfsType)
        assertEquals("obfs-pass", config.obfsPassword)
        assertEquals(listOf("h3"), config.alpn)
        assertTrue(config.insecure)
    }

    @Test
    fun tuicMapsCongestionControl() {
        val proxy = map(
            "type" to "tuic",
            "server" to "tuic.example.com",
            "port" to 443,
            "uuid" to "tuic-uuid",
            "password" to "tuic-pass",
            "congestion-controller" to "cubic"
        )

        val config = ClashProxyMapper.toParsedConfig(proxy, "")!!

        assertEquals("tuic", config.transport)
        assertEquals("tuic-uuid", config.uuid)
        assertEquals("cubic", config.congestionControl)
    }

    @Test
    fun unknownProtocolIsRejectedWithReason() {
        val proxy = map("type" to "http", "server" to "example.com", "port" to 8080)

        assertNull(ClashProxyMapper.toParsedConfig(proxy, ""))
        assertEquals("unsupported_protocol", ClashProxyMapper.classifyRejection(proxy))
    }

    @Test
    fun missingCredentialsAreClassified() {
        val vmess = map("type" to "vmess", "server" to "example.com", "port" to 443)
        val trojan = map("type" to "trojan", "server" to "example.com", "port" to 443)

        assertEquals("missing_credentials", ClashProxyMapper.classifyRejection(vmess))
        assertEquals("missing_credentials", ClashProxyMapper.classifyRejection(trojan))
    }

    @Test
    fun unsupportedVlessTransportIsRejectedAtImport() {
        val proxy = map(
            "type" to "vless",
            "server" to "example.com",
            "port" to 443,
            "uuid" to "u",
            "network" to "kcp"
        )

        assertNull(ClashProxyMapper.toParsedConfig(proxy, ""))
        assertEquals("unsupported_transport", ClashProxyMapper.classifyRejection(proxy))
    }
}
