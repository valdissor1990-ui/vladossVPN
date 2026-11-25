package com.vpnproxy.core.services

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Proxy
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Talks to a local stub server instead of the tunnel, so the HEAD→GET fallback,
 * redirect handling and body draining are covered without a device.
 *
 * `com.sun.net.httpserver` is hidden in Android unit tests, so the stub speaks raw
 * HTTP over a plain [ServerSocket].
 */
class HttpServiceProbeTest {

    private companion object {
        /** Slow HEAD; the successful GET must stay well below it. */
        const val HEAD_DELAY_MS = 500L
        /** Generous ceiling for a local GET, still below HEAD_DELAY_MS. */
        const val LATENCY_CEILING_MS = 400L
    }

    private lateinit var server: ServerSocket
    private var port: Int = 0

    @Before
    fun setUp() {
        server = ServerSocket(0)
        port = server.localPort
        thread(isDaemon = true, name = "stub-http") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { serve(socket) }
                runCatching { socket.close() }
            }
        }
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    private fun serve(socket: java.net.Socket) {
        val reader = socket.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        // Consume headers up to the blank line so the client is not left mid-request.
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty()
        val response = when {
            path == "/head-rejected" && method == "HEAD" -> {
                // Slow rejection: a latency that sums HEAD+GET would exceed the threshold
                // asserted in latencyComesFromTheSuccessfulAttemptOnly.
                Thread.sleep(HEAD_DELAY_MS)
                status(405, "Method Not Allowed")
            }
            path == "/head-rejected" && method == "GET" -> ok("OK")
            path == "/redirect" -> status(302, "Found", extraHeaders = "Location: http://example.invalid/\r\n")
            path == "/forbidden" -> status(403, "Forbidden")
            else -> status(404, "Not Found")
        }
        socket.getOutputStream().apply {
            write(response.toByteArray())
            flush()
        }
    }

    private fun ok(body: String): String =
        "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"

    private fun status(code: Int, text: String, extraHeaders: String = ""): String =
        "HTTP/1.1 $code $text\r\n${extraHeaders}Content-Length: 0\r\nConnection: close\r\n\r\n"

    private fun probe() = HttpServiceProbe(proxyOverride = Proxy.NO_PROXY)

    private fun target(path: String) = ServiceTarget(
        id = "stub",
        title = "Stub",
        category = ServiceCategory.DEV,
        url = "http://127.0.0.1:$port$path"
    )

    @Test
    fun headRejectedFallsBackToGet() = runBlocking {
        val result = probe().probe(target("/head-rejected"))

        assertTrue("GET must rescue a rejected HEAD", result.reachable)
        assertEquals(200, result.httpCode)
        assertNull(result.reason)
    }

    @Test
    fun redirectIsNotReachable() = runBlocking {
        val result = probe().probe(target("/redirect"))

        assertTrue(!result.reachable)
        assertEquals(302, result.httpCode)
    }

    @Test
    fun httpErrorIsReportedWithCode() = runBlocking {
        val result = probe().probe(target("/forbidden"))

        assertTrue(!result.reachable)
        assertEquals(403, result.httpCode)
    }

    @Test
    fun latencyComesFromTheSuccessfulAttemptOnly() = runBlocking {
        val result = probe().probe(target("/head-rejected"))

        // HEAD is slowed down by HEAD_DELAY_MS; summing both attempts would exceed the ceiling.
        assertTrue("latency=${result.latencyMs}", result.latencyMs < LATENCY_CEILING_MS)
    }
}
