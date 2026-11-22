package com.vpnproxy.core.ping

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Direct TCP handshake timing to a profile endpoint.
 *
 * This is a *hint*, never a verdict: a successful handshake only proves the endpoint answered the
 * SYN directly from this network, not that the proxy protocol works. It is measured when the full
 * proxied probe fails, so a server that is merely blocked or misconfigured on the app's side does
 * not look identical to a server that does not exist at all.
 */
object TcpConnectProbe {
    const val DEFAULT_TIMEOUT_MS = 2_500
    const val DEFAULT_ATTEMPTS = 2

    fun interface Connector {
        /** Returns the handshake duration in milliseconds or throws. */
        fun connect(host: String, port: Int, timeoutMs: Int): Long
    }

    /** Fastest successful attempt wins; empty means every attempt failed. */
    internal fun bestOf(samples: List<Long>): Long? = samples.minOrNull()

    fun measure(
        host: String,
        port: Int,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        attempts: Int = DEFAULT_ATTEMPTS,
        connector: Connector = Connector { h, p, t -> connectOnce(h, p, t) }
    ): Long? {
        if (host.isBlank() || port !in 1..65535 || attempts <= 0) return null
        val samples = mutableListOf<Long>()
        repeat(attempts) {
            try {
                val sample = connector.connect(host, port, timeoutMs)
                if (sample > 0L) samples += sample
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return bestOf(samples)
            } catch (_: Exception) {
                // Unreachable, refused, timed out or DNS failure: this attempt simply has no sample.
            }
        }
        return bestOf(samples)
    }

    private fun connectOnce(host: String, port: Int, timeoutMs: Int): Long {
        val socket = Socket()
        try {
            val started = System.nanoTime()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            return ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
        } finally {
            runCatching { socket.close() }
        }
    }
}
