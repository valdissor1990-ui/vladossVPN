package com.vpnproxy.smartconnect

import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepProbeScoringTest {
    private fun config(name: String, host: String) = ParsedConfig(
        uuid = "00000000-0000-0000-0000-000000000001", address = host, port = 443,
        transport = "tcp", security = "tls", sni = host, path = null, host = null,
        mode = null, fingerprint = "chrome", publicKey = null, shortId = null,
        flow = null, name = name
    )

    @Test fun profileLatencyUsesMedianAndRequiresAtLeastTwoSuccessfulSamples() {
        val config = config("profile", "profile.example")
        val healthy = DeepProbeEngine.summarizeProfileLatency(config, listOf(90L, 42L, 60L))
        assertTrue(healthy.success)
        assertEquals(3, healthy.successfulSamples)
        assertEquals(60L, healthy.latencyMs)

        val twoResponses = DeepProbeEngine.summarizeProfileLatency(config, listOf(40L, 60L))
        assertTrue(twoResponses.success)
        assertEquals(50L, twoResponses.latencyMs)

        val oneResponse = DeepProbeEngine.summarizeProfileLatency(config, listOf(42L))
        assertTrue(!oneResponse.success)
        assertEquals(Long.MAX_VALUE, oneResponse.latencyMs)

        val noResponses = DeepProbeEngine.summarizeProfileLatency(config, emptyList())
        assertTrue(!noResponses.success)
        assertEquals(Long.MAX_VALUE, noResponses.latencyMs)
    }
    @Test fun normalizesScoresAndSortsBestFirst() {
        val slow = DeepProbeEngine.ProbeMetrics(config("slow", "slow.example"), 180, 1.5, 60, 0.6, false)
        val fast = DeepProbeEngine.ProbeMetrics(config("fast", "fast.example"), 20, 50.0, 2, 0.0, true)
        val scored = DeepProbeEngine.scoreMetrics(listOf(slow, fast))
        assertEquals("fast", scored.first().config.name)
        assertEquals(1.0, scored.first().normalizedScore, 0.000001)
        assertEquals(0.0, scored.last().normalizedScore, 0.000001)
        scored.forEach { assertTrue(it.normalizedScore in 0.0..1.0) }
    }

    @Test fun equalMetricsDoNotDivideByZero() {
        val first = DeepProbeEngine.ProbeMetrics(config("one", "one.example"), 20, 10.0, 2, 0.0, true)
        val second = first.copy(config = config("two", "two.example"))
        val scored = DeepProbeEngine.scoreMetrics(listOf(first, second))
        assertEquals(1.0, scored[0].normalizedScore, 0.000001)
        assertEquals(1.0, scored[1].normalizedScore, 0.000001)
    }
    @Test fun allFailedMetricsNeverRankAsHealthy() {
        val failedA = DeepProbeEngine.ProbeMetrics(config("failed-a", "a.example"), Long.MAX_VALUE, 0.0, Long.MAX_VALUE, 1.0, false)
        val failedB = DeepProbeEngine.ProbeMetrics(config("failed-b", "b.example"), Long.MAX_VALUE, 0.0, Long.MAX_VALUE, 1.0, false)
        val scored = DeepProbeEngine.scoreMetrics(listOf(failedA, failedB))
        assertTrue(scored.all { it.normalizedScore == 0.0 })
        assertEquals(0.0, scored.first().speedScore, 0.000001)
    }

}
