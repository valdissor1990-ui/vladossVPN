package com.vpnproxy.core.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ServerDiagnosticsStoreTest {
    @Test
    fun writesStructuredEventsWithoutAcceptingRawServerNames() {
        val directory = Files.createTempDirectory("server-diagnostics-test").toFile()
        try {
            val store = ServerDiagnosticsStore(directory, true)
            store.record(
                ServerDiagnosticEvent(
                    eventType = "speed_test_completed",
                    runId = "run_123",
                    serverId = "a1b2c3d4e5f6",
                    protocol = "HYSTERIA2",
                    stage = "complete",
                    dnsMs = 24,
                    connectMs = 180,
                    downloadMbps = 42.6,
                    uploadMbps = 1.8,
                    durationMs = 15_000,
                    success = true
                )
            )

            val journal = store.currentJournal()
            assertNotNull(journal)
            val line = journal!!.readText()
            assertTrue(line.startsWith("{"))
            assertTrue(line.contains("\"event\":\"speed_test_completed\""))
            assertTrue(line.contains("\"protocol\":\"hysteria2\""))
            assertTrue(line.contains("\"uploadMbps\":1.8"))
            assertFalse(line.contains("password"))
            assertFalse(line.contains("example.com"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsRawHostnamesAsServerIdsAndNormalizesUnknownProtocol() {
        val directory = Files.createTempDirectory("server-diagnostics-test").toFile()
        try {
            val store = ServerDiagnosticsStore(directory, true)
            var rejected = false
            try {
                ServerDiagnosticEvent(
                    eventType = "probe_completed",
                    runId = "run_123",
                    serverId = "server.example.com"
                )
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)

            store.record(
                ServerDiagnosticEvent(
                    eventType = "probe_completed",
                    runId = "run_123",
                    protocol = "custom-unknown"
                )
            )
            assertTrue(store.currentJournal()!!.readText().contains("\"protocol\":\"unknown\""))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun clearRemovesJournal() {
        val directory = Files.createTempDirectory("server-diagnostics-test").toFile()
        try {
            val store = ServerDiagnosticsStore(directory, true)
            store.record(ServerDiagnosticEvent(eventType = "probe_completed", runId = "run_123"))
            store.clear()
            assertFalse(File(directory, "server-diagnostics.jsonl").exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
