package com.vpnproxy.core.singbox

import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingRulesTest {
    private val config = ParsedConfig(
        uuid = "11111111-1111-1111-1111-111111111111",
        address = "proxy.example.org",
        port = 443,
        transport = "vless",
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
        password = "secret",
        insecure = false,
        alpn = listOf("h2"),
        obfsType = null,
        obfsPassword = null,
        congestionControl = "bbr"
    )

    private fun build(routing: RoutingRules): JsonObject =
        Json.parseToJsonElement(
            SingboxConfigBuilder.buildVpn(config, DiagnosticOptions(), routing = routing)
        ).jsonObject

    private fun rules(root: JsonObject) = root["route"]!!.jsonObject["rules"]!!.jsonArray

    private fun JsonObject.outbound(): String? = this["outbound"]?.jsonPrimitive?.content

    @Test
    fun defaultPresetBypassesLanOnly() {
        val routing = RoutingRules()
        assertEquals(RoutingPreset.BYPASS_LAN, routing.effectivePreset)
        val root = build(routing)

        assertFalse("all_vpn is not the default", routing.preset == RoutingPreset.ALL_VPN)
        assertFalse(root["route"]!!.jsonObject.containsKey("rule_set"))
        val lanRule = rules(root).map { it.jsonObject }.single { it["ip_is_private"]?.jsonPrimitive?.boolean == true }
        assertEquals("direct", lanRule.outbound())
        assertEquals("proxy", root["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
    }

    @Test
    fun allVpnSendsLanThroughTheTunnel() {
        val root = build(RoutingRules(preset = RoutingPreset.ALL_VPN))

        assertTrue(rules(root).none { it.jsonObject.containsKey("ip_is_private") })
        assertFalse(root["route"]!!.jsonObject.containsKey("rule_set"))
    }

    @Test
    fun bypassRuEmitsLocalBinaryRuleSets() {
        val root = build(
            RoutingRules(
                preset = RoutingPreset.BYPASS_RU,
                geoipRuPath = "/data/user/0/com.vpnproxy/files/rulesets/geoip-ru.srs",
                geositeRuPath = "/data/user/0/com.vpnproxy/files/rulesets/geosite-category-ru.srs"
            )
        )

        val ruleSets = root["route"]!!.jsonObject["rule_set"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("geoip-ru", "geosite-ru"), ruleSets.map { it["tag"]!!.jsonPrimitive.content }.toSet())
        ruleSets.forEach {
            assertEquals("local", it["type"]!!.jsonPrimitive.content)
            assertEquals("binary", it["format"]!!.jsonPrimitive.content)
            assertTrue(it["path"]!!.jsonPrimitive.content.endsWith(".srs"))
        }

        val ruRule = rules(root).map { it.jsonObject }
            .single { it.containsKey("rule_set") }
        assertEquals(
            setOf("geosite-ru", "geoip-ru"),
            ruRule["rule_set"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        )
        assertEquals("direct", ruRule.outbound())
    }

    @Test
    fun bypassRuWithoutLocalFilesDegradesToLanBypass() {
        val routing = RoutingRules(preset = RoutingPreset.BYPASS_RU)
        assertTrue(routing.ruRuleSetsDegraded)
        assertEquals(RoutingPreset.BYPASS_LAN, routing.effectivePreset)

        val root = build(routing)
        assertFalse("missing rule-sets must not be referenced", root["route"]!!.jsonObject.containsKey("rule_set"))
        assertTrue(rules(root).any { it.jsonObject["ip_is_private"]?.jsonPrimitive?.boolean == true })
    }

    @Test
    fun partialRuleSetPathsStillDegrade() {
        val routing = RoutingRules(
            preset = RoutingPreset.BYPASS_RU,
            geoipRuPath = "/tmp/geoip-ru.srs"
        )
        assertTrue(routing.ruRuleSetsDegraded)
        assertFalse(build(routing)["route"]!!.jsonObject.containsKey("rule_set"))
    }

    @Test
    fun lanRuleRunsBeforeTheRuRuleSet() {
        val root = build(
            RoutingRules(
                preset = RoutingPreset.BYPASS_RU,
                geoipRuPath = "/tmp/geoip-ru.srs",
                geositeRuPath = "/tmp/geosite-ru.srs"
            )
        )
        val rules = rules(root).map { it.jsonObject }
        assertTrue(
            rules.indexOfFirst { it.containsKey("ip_is_private") } < rules.indexOfFirst { it.containsKey("rule_set") }
        )
    }

    @Test
    fun presetIdsRoundTrip() {
        RoutingPreset.entries.forEach { preset ->
            assertEquals(preset, RoutingPreset.fromId(preset.id))
        }
        assertEquals(RoutingPreset.DEFAULT, RoutingPreset.fromId(null))
        assertEquals(RoutingPreset.DEFAULT, RoutingPreset.fromId("nonsense"))
    }

    @Test
    fun ruleSetPayloadRequiresSrsMagic() {
        val valid = ByteArray(2048).also {
            it[0] = 'S'.code.toByte()
            it[1] = 'R'.code.toByte()
            it[2] = 'S'.code.toByte()
            it[3] = 1
        }
        assertTrue(RuleSetStore.isValidRuleSetPayload(valid))
        assertFalse("HTML error page must be rejected", RuleSetStore.isValidRuleSetPayload("<html>404</html>".toByteArray()))
        assertFalse("too small", RuleSetStore.isValidRuleSetPayload(valid.copyOf(16)))
        assertFalse(RuleSetStore.isValidRuleSetPayload(null))
    }
}