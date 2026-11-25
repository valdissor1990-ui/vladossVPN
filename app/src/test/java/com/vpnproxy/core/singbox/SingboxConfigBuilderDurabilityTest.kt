package com.vpnproxy.core.singbox

import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingboxConfigBuilderDurabilityTest {
    private fun config(name: String) = ParsedConfig(
        uuid = "11111111-1111-1111-1111-111111111111",
        address = "$name.example.org",
        port = 443,
        transport = "ws",
        security = "tls",
        sni = "$name.example.org",
        path = "/ws",
        host = null,
        mode = null,
        fingerprint = "chrome",
        publicKey = null,
        shortId = null,
        flow = null,
        name = name,
        password = "secret",
        insecure = false,
        alpn = listOf("h2")
    )

    private fun outbounds(json: String) =
        Json.parseToJsonElement(json).jsonObject["outbounds"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun urlTestGroupKeepsProxyTagAndWrapsStandbys() {
        val json = SingboxConfigBuilder.buildVpn(
            config("primary"),
            standbys = listOf(config("standby-a"), config("standby-b")),
            durability = DurabilityOptions(nativeUrlTestEnabled = true)
        )
        val outbounds = outbounds(json)

        val group = outbounds.single { it["tag"]!!.jsonPrimitive.content == "proxy" }
        assertEquals("urltest", group["type"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("proxy-primary", "proxy-standby-0", "proxy-standby-1"),
            group["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(DurabilityOptions.URL_TEST_PROBE_URL, group["url"]!!.jsonPrimitive.content)
        assertEquals(DurabilityOptions.URL_TEST_INTERVAL, group["interval"]!!.jsonPrimitive.content)
        assertEquals(DurabilityOptions.URL_TEST_TOLERANCE_MS, group["tolerance"]!!.jsonPrimitive.int)

        // route.final still resolves to the group tag, so DNS detour and inbound rules keep working.
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals("proxy", root["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
        assertTrue(outbounds.any { it["tag"]!!.jsonPrimitive.content == SingboxConfigBuilder.PROXY_PRIMARY_TAG })
    }

    @Test
    fun nativeUrlTestDisabledKeepsConcreteProxyOutbound() {
        val outbounds = outbounds(
            SingboxConfigBuilder.buildVpn(
                config("primary"),
                standbys = listOf(config("standby-a")),
                durability = DurabilityOptions(nativeUrlTestEnabled = false)
            )
        )
        // No group: the primary stays a concrete outbound tagged "proxy".
        assertFalse(outbounds.any { it["type"]!!.jsonPrimitive.content == "urltest" })
        val proxy = outbounds.single { it["tag"]!!.jsonPrimitive.content == "proxy" }
        assertEquals("vless", proxy["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun urlTestStandbyCountIsClampedToMaximum() {
        val many = (0 until 10).map { config("standby-$it") }
        val group = outbounds(
            SingboxConfigBuilder.buildVpn(
                config("primary"),
                standbys = many,
                durability = DurabilityOptions(nativeUrlTestEnabled = true, urlTestStandbyCount = 99)
            )
        ).single { it["tag"]!!.jsonPrimitive.content == "proxy" }

        val members = group["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(1 + DurabilityOptions.MAX_STANDBY_COUNT, members.size)
        assertFalse(members.contains("proxy-standby-${DurabilityOptions.MAX_STANDBY_COUNT}"))
    }

    @Test
    fun tlsFragmentFlagsAreEmittedWhenEnabled() {
        val proxy = outbounds(
            SingboxConfigBuilder.buildVpn(
                config("primary"),
                durability = DurabilityOptions(tlsFragmentEnabled = true, tlsRecordFragmentEnabled = true)
            )
        ).single { it["tag"]!!.jsonPrimitive.content == "proxy" }

        val tls = proxy["tls"]!!.jsonObject
        assertTrue(tls["fragment"]!!.jsonPrimitive.boolean)
        assertTrue(tls["record_fragment"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun tlsFragmentFlagsAreAbsentByDefault() {
        val tls = outbounds(SingboxConfigBuilder.buildVpn(config("primary")))
            .single { it["tag"]!!.jsonPrimitive.content == "proxy" }["tls"]!!.jsonObject
        assertFalse(tls.containsKey("fragment"))
        assertFalse(tls.containsKey("record_fragment"))
    }

    @Test
    fun defaultDurabilityOptionsProduceTheGoldenConfigByteForByte() {
        // Guards the promise that untouched installs (all flags off) get exactly the old config.
        val withoutOptions = SingboxConfigBuilder.buildVpn(config("primary"))
        val withDefaults = SingboxConfigBuilder.buildVpn(
            config("primary"),
            durability = DurabilityOptions()
        )

        assertEquals(withoutOptions, withDefaults)
    }

    @Test
    fun utlsFingerprintOverrideWins() {
        val tls = outbounds(
            SingboxConfigBuilder.buildVpn(
                config("primary"),
                durability = DurabilityOptions(utlsFingerprint = "firefox")
            )
        ).single { it["tag"]!!.jsonPrimitive.content == "proxy" }["tls"]!!.jsonObject

        assertEquals("firefox", tls["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
    }
}