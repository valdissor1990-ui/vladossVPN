package com.vpnproxy.core.ping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyCalculatorTest {

    private fun tr(id: String, rtt: Long?, ok: Boolean = true) =
        TargetResult(id, rtt, ok, if (ok) null else "fail", 0L)

    @Test
    fun empty_list_returns_invalid_score() {
        val b = LatencyCalculator.compute(emptyList())
        assertEquals(9999.0, b.compositeScore, 0.0)
        assertNull(b.medianMs)
        assertEquals(0, b.successCount)
        assertFalse(b.isValid)
    }

    @Test
    fun fewer_than_three_successes_invalid() {
        val b = LatencyCalculator.compute(
            listOf(tr("a", 10), tr("b", 20), tr("c", null, false))
        )
        assertEquals(9999.0, b.compositeScore, 0.0)
        assertEquals(2, b.successCount)
        assertFalse(b.isValid)
    }

    @Test
    fun identical_rtts_zero_jitter() {
        val b = LatencyCalculator.compute(
            listOf(tr("a", 50), tr("b", 50), tr("c", 50), tr("d", 50))
        )
        assertEquals(50.0, b.medianMs!!, 0.01)
        assertEquals(50.0, b.p95Ms!!, 0.01)
        assertEquals(0.0, b.jitterMs!!, 0.01)
        // 0.4*50 + 0.3*50 + 0.3*0 = 35
        assertEquals(35.0, b.compositeScore, 0.01)
        assertTrue(b.isValid)
    }

    @Test
    fun varied_rtts_compute_stats() {
        val b = LatencyCalculator.compute(
            listOf(
                tr("a", 10),
                tr("b", 20),
                tr("c", 30),
                tr("d", 40),
                tr("e", 100)
            )
        )
        assertEquals(30.0, b.medianMs!!, 0.01)
        assertTrue(b.p95Ms!! >= 40.0)
        assertTrue(b.jitterMs!! > 0.0)
        assertTrue(b.compositeScore < 9999.0)
        assertEquals(5, b.successCount)
    }

    @Test
    fun displayScore_na_when_invalid() {
        val b = LatencyCalculator.compute(listOf(tr("a", 5)))
        assertEquals("N/A", b.displayScore())
    }
}
