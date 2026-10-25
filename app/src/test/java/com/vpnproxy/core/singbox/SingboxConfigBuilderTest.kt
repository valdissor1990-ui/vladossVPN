package com.vpnproxy.core.singbox

import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingboxConfigBuilderTest {
    private fun config(transport: String, password: String) = ParsedConfig(
        uuid = if (transport == "tuic") "11111111-1111-1111-1111-111111111111" else password,
        address = "proxy.example.org",
        port = 443,
        transport = transport,
        security = "tls",
        sni = "sni.example.org",
        path = null,
        host = null,
        mode = null,
        fingerprint = null,
        publicKey = null,
        shortId = null,
        flow = null,
        name = "test",
        password = password,
        insecure = false,
        alpn = listOf("h3"),
        obfsType = if (transport == "hysteria2") "salamander" else null,
        obfsPassword = if (transport == "hysteria2") "obfs-secret" else null,
        congestionControl = "cubic"
    )

    @Test
    fun hysteria2BuildsProxyModeOutbound() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(
            SingboxConfigBuilder.build(config("hysteria2", "hy2-secret"))
        ).jsonObject
        val inbound = root["inbounds"]!!.jsonArray.single().jsonObject
        val outbound = root["outbounds"]!!.jsonArray.first().jsonObject
        assertEquals("mixed", inbound["type"]!!.jsonPrimitive.content)
        assertEquals(10809, inbound["listen_port"]!!.jsonPrimitive.int)
        assertEquals("hysteria2", outbound["type"]!!.jsonPrimitive.content)
        assertEquals("hy2-secret", outbound["password"]!!.jsonPrimitive.content)
        assertEquals("sni.example.org", outbound["tls"]!!.jsonObject["server_name"]!!.jsonPrimitive.content)
        assertEquals("salamander", outbound["obfs"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("obfs-secret", outbound["obfs"]!!.jsonObject["password"]!!.jsonPrimitive.content)
        assertEquals("h3", outbound["tls"]!!.jsonObject["alpn"]!!.jsonArray.first().jsonPrimitive.content)
        assertFalse(outbound["tls"]!!.jsonObject["insecure"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun tuicBuildsUuidPasswordAndCongestionControl() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(
            SingboxConfigBuilder.build(config("tuic", "tuic-secret"))
        ).jsonObject
        val outbound = root["outbounds"]!!.jsonArray.first().jsonObject
        assertEquals("tuic", outbound["type"]!!.jsonPrimitive.content)
        assertEquals("11111111-1111-1111-1111-111111111111", outbound["uuid"]!!.jsonPrimitive.content)
        assertEquals("tuic-secret", outbound["password"]!!.jsonPrimitive.content)
        assertEquals("cubic", outbound["congestion_control"]!!.jsonPrimitive.content)
        assertEquals("native", outbound["udp_relay_mode"]!!.jsonPrimitive.content)
        assertTrue(outbound["tls"]!!.jsonObject["alpn"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun multiServerConfigPinsEachInboundToItsOwnOutbound() {
        val hy2 = config("hysteria2", "hy2-secret")
        val tuic = config("tuic", "tuic-secret")
        val root = kotlinx.serialization.json.Json.parseToJsonElement(
            SingboxConfigBuilder.buildMulti(listOf(hy2, tuic), listOf(12001, 12002))
        ).jsonObject
        val inbounds = root["inbounds"]!!.jsonArray
        val outbounds = root["outbounds"]!!.jsonArray
        val rules = root["route"]!!.jsonObject["rules"]!!.jsonArray
        assertEquals(2, inbounds.size)
        assertEquals(3, outbounds.size)
        assertEquals(12001, inbounds[0].jsonObject["listen_port"]!!.jsonPrimitive.int)
        assertEquals(12002, inbounds[1].jsonObject["listen_port"]!!.jsonPrimitive.int)
        assertEquals("proxy-0", rules[0].jsonObject["outbound"]!!.jsonPrimitive.content)
        assertEquals("proxy-1", rules[1].jsonObject["outbound"]!!.jsonPrimitive.content)
    }

    @Test
    fun vlessRealityMapsUriFieldsToSingBoxOutbound() {
        val config = ParsedConfig(
            uuid = "11111111-1111-1111-1111-111111111111",
            address = "reality.example.org",
            port = 443,
            transport = "tcp",
            security = "reality",
            sni = "www.example.org",
            path = null, host = null, mode = null,
            fingerprint = "chrome",
            publicKey = "public-key",
            shortId = "a1b2",
            flow = "xtls-rprx-vision",
            name = "reality"
        )
        val outbound = kotlinx.serialization.json.Json.parseToJsonElement(
            SingboxConfigBuilder.build(config)
        ).jsonObject["outbounds"]!!.jsonArray.first().jsonObject
        assertEquals("vless", outbound["type"]!!.jsonPrimitive.content)
        assertEquals(config.uuid, outbound["uuid"]!!.jsonPrimitive.content)
        assertEquals("xtls-rprx-vision", outbound["flow"]!!.jsonPrimitive.content)
        val tls = outbound["tls"]!!.jsonObject
        assertEquals("www.example.org", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals("chrome", tls["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
        assertEquals("public-key", tls["reality"]!!.jsonObject["public_key"]!!.jsonPrimitive.content)
        assertEquals("a1b2", tls["reality"]!!.jsonObject["short_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun xhttpRealityMapsTransportAndExtraFields() {
        val config = ParsedConfig(
            uuid = "11111111-1111-1111-1111-111111111111",
            address = "xhttp.example.org",
            port = 443,
            transport = "xhttp",
            security = "reality",
            sni = "www.example.org",
            path = "/xhttp", host = "www.example.org", mode = "stream-one",
            fingerprint = "chrome", publicKey = "public-key", shortId = "a1b2",
            flow = "", name = "xhttp",
            extra = """{"xPaddingBytes":"100-200","noGRPCHeader":true,"uplinkHTTPMethod":"POST","headers":{"X-Test":"yes"}}"""
        )
        val outbound = kotlinx.serialization.json.Json.parseToJsonElement(SingboxConfigBuilder.build(config))
            .jsonObject["outbounds"]!!.jsonArray.first().jsonObject
        val transport = outbound["transport"]!!.jsonObject
        assertEquals("xhttp", transport["type"]!!.jsonPrimitive.content)
        assertEquals("www.example.org", transport["host"]!!.jsonPrimitive.content)
        assertEquals("/xhttp", transport["path"]!!.jsonPrimitive.content)
        assertEquals("stream-one", transport["mode"]!!.jsonPrimitive.content)
        assertEquals("100-200", transport["x_padding_bytes"]!!.jsonPrimitive.content)
        assertTrue(transport["no_grpc_header"]!!.jsonPrimitive.boolean)
        assertEquals("POST", transport["uplink_http_method"]!!.jsonPrimitive.content)
        assertEquals("yes", transport["headers"]!!.jsonObject["X-Test"]!!.jsonPrimitive.content)
        assertFalse(transport.containsKey("extra"))
        assertFalse(transport.containsKey("xPaddingBytes"))
        val tls = outbound["tls"]!!.jsonObject
        assertEquals("www.example.org", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals("public-key", tls["reality"]!!.jsonObject["public_key"]!!.jsonPrimitive.content)
        assertEquals("a1b2", tls["reality"]!!.jsonObject["short_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun xhttpSessionIdAliasesMapToSingBoxCanonicalNames() {
        val config = ParsedConfig(
            uuid = "11111111-1111-1111-1111-111111111111",
            address = "xhttp.example.org",
            port = 443,
            transport = "xhttp",
            security = "reality",
            sni = "www.example.org",
            path = "/xhttp",
            host = "www.example.org",
            mode = "auto",
            fingerprint = "chrome",
            publicKey = "public-key",
            shortId = "a1b2",
            flow = "",
            name = "xhttp",
            extra = """{"sessionIDPlacement":"cookie","sessionIDKey":"stream_auth"}"""
        )
        val transport = kotlinx.serialization.json.Json.parseToJsonElement(
            SingboxConfigBuilder.build(config)
        ).jsonObject["outbounds"]!!.jsonArray.first().jsonObject["transport"]!!.jsonObject

        assertEquals("cookie", transport["session_placement"]!!.jsonPrimitive.content)
        assertEquals("stream_auth", transport["session_key"]!!.jsonPrimitive.content)
        assertFalse(transport.containsKey("session_idplacement"))
        assertFalse(transport.containsKey("session_i_d_placement"))
        assertFalse(transport.containsKey("session_idkey"))
        assertFalse(transport.containsKey("session_i_d_key"))
    }

    @Test
    fun xhttpDropsUnsupportedExtraFields() {
        val config = ParsedConfig(
            uuid = "11111111-1111-1111-1111-111111111111",
            address = "xhttp.example.org",
            port = 443,
            transport = "xhttp",
            security = "reality",
            sni = "www.example.org",
            path = "/xhttp",
            host = "www.example.org",
            mode = "auto",
            fingerprint = "chrome",
            publicKey = "public-key",
            shortId = "a1b2",
            flow = "",
            name = "xhttp",
            extra = """{"sessionIDTable":{"a":1},"sessionIDLength":16,"seqPlacement":"header","xmux":true,"xPaddingBytes":"100-200"}"""
        )
        val transport = kotlinx.serialization.json.Json.parseToJsonElement(
            SingboxConfigBuilder.build(config)
        ).jsonObject["outbounds"]!!.jsonArray.first().jsonObject["transport"]!!.jsonObject

        // Supported field survives
        assertEquals("100-200", transport["x_padding_bytes"]!!.jsonPrimitive.content)
        // Unsupported fields must not appear under any known alias
        assertFalse(transport.containsKey("session_idtable"))
        assertFalse(transport.containsKey("session_i_d_table"))
        assertFalse(transport.containsKey("sessionIDTable"))
        assertFalse(transport.containsKey("session_id_length"))
        assertFalse(transport.containsKey("sessionIDLength"))
        assertFalse(transport.containsKey("seq_placement"))
        assertFalse(transport.containsKey("seqPlacement"))
        assertFalse(transport.containsKey("xmux"))
    }

    @Test
    fun tlsServerNameFallsBackToTransportHostWhenSniIsMissing() {
        val config = config("ws", "secret").copy(sni = null, host = "fitindoor.net")
        val outbound = kotlinx.serialization.json.Json.parseToJsonElement(
            SingboxConfigBuilder.build(config)
        ).jsonObject["outbounds"]!!.jsonArray.first().jsonObject

        assertEquals(
            "fitindoor.net",
            outbound["tls"]!!.jsonObject["server_name"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun connectionKeyKeepsDifferentHostVariantsDistinctOnSameEndpoint() {
        val first = config("ws", "secret").copy(sni = null, host = "fitindoor.net")
        val second = first.copy(host = "cdn.example.org")
        assertFalse(first.connectionKey() == second.connectionKey())
    }


    @Test
    fun transportPathsAreNormalizedWhenLeadingSlashIsMissing() {
        val ws = config("ws", "secret").copy(path = "ws")
        val wsTransport = kotlinx.serialization.json.Json.parseToJsonElement(
            SingboxConfigBuilder.build(ws)
        ).jsonObject["outbounds"]!!.jsonArray.first().jsonObject["transport"]!!.jsonObject
        assertEquals("/ws", wsTransport["path"]!!.jsonPrimitive.content)

        val xhttp = config("xhttp", "secret").copy(path = "xhttp")
        val xhttpTransport = kotlinx.serialization.json.Json.parseToJsonElement(
            SingboxConfigBuilder.build(xhttp)
        ).jsonObject["outbounds"]!!.jsonArray.first().jsonObject["transport"]!!.jsonObject
        assertEquals("/xhttp", xhttpTransport["path"]!!.jsonPrimitive.content)
    }

}
