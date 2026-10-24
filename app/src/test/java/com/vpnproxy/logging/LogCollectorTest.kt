package com.vpnproxy.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogCollectorTest {
    @Test
    fun defaultFilterPublishesSameEntriesToBothFlows() {
        val collector = LogCollector()
        val first = LogEntry(level = LogLevel.INFO, tag = "VPN", message = "connected")
        val second = LogEntry(level = LogLevel.WARN, tag = "Network", message = "network lost")

        collector.add(first)
        collector.add(second)

        assertEquals(listOf(first, second), collector.logs.value)
        assertEquals(collector.logs.value, collector.filteredLogs.value)
    }

    @Test
    fun activeFilterOnlyPublishesMatchingEntries() {
        val collector = LogCollector()
        val first = LogEntry(level = LogLevel.INFO, tag = "VPN", message = "connected")
        val second = LogEntry(level = LogLevel.ERROR, tag = "VPN", message = "connection failed")
        val third = LogEntry(level = LogLevel.ERROR, tag = "Network", message = "network failed")

        collector.add(first)
        collector.add(second)
        collector.add(third)
        collector.setFilter(level = LogLevel.ERROR, tag = "VPN", query = "failed")

        assertEquals(listOf(second), collector.filteredLogs.value)
        assertEquals(3, collector.logs.value.size)
    }

    @Test
    fun clearEmptiesVisibleBuffersWithoutThrowing() {
        val collector = LogCollector()
        collector.add(LogEntry(level = LogLevel.INFO, tag = "VPN", message = "connected"))

        collector.clear()

        assertTrue(collector.logs.value.isEmpty())
        assertTrue(collector.filteredLogs.value.isEmpty())
        assertTrue(collector.getAllEntries().isEmpty())
    }
}
