package com.vpnproxy.core.singbox

import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.boolean
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
        assertTrue(outbound["tls"]!!.jsonObject["alpn"]!!.jsonArray.isNotEmpty())
    }
}
