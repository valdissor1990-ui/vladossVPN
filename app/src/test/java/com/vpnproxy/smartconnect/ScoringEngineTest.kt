package com.vpnproxy.smartconnect

import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringEngineTest {
    private fun config(name: String, transport: String = "tcp") = ParsedConfig(
        uuid = "00000000-0000-0000-0000-000000000001",
        address = "same.example",
        port = 443,
        transport = transport,
        security = "tls",
        sni = "same.example",
        path = null,
        host = null,
        mode = null,
        fingerprint = "chrome",
        publicKey = null,
        shortId = null,
        flow = null,
        name = name
    )

    @Test
    fun successRateUsesObservedProbeHistoryInsteadOfConstantOne() {
        val engine = ScoringEngine()
        val profile = config("profile")

        engine.computeScore(listOf(HealthChecker.LatencyResult(profile, Long.MAX_VALUE, false)))
        val ranked = engine.computeScore(listOf(HealthChecker.LatencyResult(profile, 100L, true)))

        assertEquals(1, ranked.size)
        assertEquals(0.5f, ranked.single().successRate, 0.0001f)
    }

    @Test
    fun speedMeasurementsAreMatchedByFullProfileIdentity() {
        val engine = ScoringEngine()
        val tcp = config("tcp", "tcp")
        val ws = config("ws", "ws")

        val ranked = engine.computeScore(
            listOf(
                HealthChecker.LatencyResult(tcp, 80L, true),
                HealthChecker.LatencyResult(ws, 80L, true)
            ),
            listOf(
                HealthChecker.SpeedResult(tcp, 5f, true),
                HealthChecker.SpeedResult(ws, 20f, true)
            )
        )
        val byName = ranked.associateBy { it.config.name }

        assertEquals(5f, byName.getValue("tcp").speedMbps, 0.0001f)
        assertEquals(20f, byName.getValue("ws").speedMbps, 0.0001f)
        assertTrue(byName.getValue("ws").score > byName.getValue("tcp").score)
    }

    @Test
    fun failedSpeedProbeDoesNotCountAsMeasuredThroughput() {
        val engine = ScoringEngine()
        val profile = config("profile")

        val ranked = engine.computeScore(
            listOf(HealthChecker.LatencyResult(profile, 50L, true)),
            listOf(HealthChecker.SpeedResult(profile, 100f, false))
        )

        assertEquals(0f, ranked.single().speedMbps, 0.0001f)
    }
}
