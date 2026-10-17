package com.vpnproxy.core.ping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PingTargetsTest {
    @Test fun forRegion_RU_containsYandexAndRutracker() {
        val ids = PingTargets.forRegion(PingRegion.RU).map { it.id }.toSet()
        assertTrue("yandex" in ids)
        assertTrue("rutracker" in ids)
    }

    @Test fun forRegion_EU_replacesRUWithXAndReddit() {
        val ids = PingTargets.forRegion(PingRegion.EU).map { it.id }.toSet()
        assertTrue("x_com" in ids)
        assertTrue("reddit" in ids)
        assertTrue("yandex" !in ids && "rutracker" !in ids)
    }

    @Test fun weights_sumToOnePerRegion() {
        PingRegion.entries.forEach { region ->
            assertEquals(region.name, 1.0, PingTargets.forRegion(region).sumOf { it.weight }, 1e-9)
        }
    }
}
