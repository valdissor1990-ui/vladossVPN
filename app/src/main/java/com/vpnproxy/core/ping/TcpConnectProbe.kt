package com.vpnproxy.core.ping

import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * How a direct TCP handshake to a profile endpoint ended.
 *
 * [REFUSED] is the interesting one: a reset that arrives in tens of milliseconds is what a
 * carrier-side block (or a closed port) looks like, while a silent timeout means the endpoint
 * simply does not answer. The distinction keeps a blocked server from being reported as a
 * broken one and vice versa.
 */
enum class TcpConnectOutcome {
    CONNECTED,
    REFUSED,
    TIMEOUT,
    UNREACHABLE,
    ERROR
}

data class TcpConnectResult(
    val outcome: TcpConnectOutcome,
    /** Handshake duration for [TcpConnectOutcome.CONNECTED], otherwise null. */
    val latencyMs: Long? = null,
    /** Duration of the last failed attempt; separates a fast reset from a slow timeout. */
    val failureMs: Long? = null
)

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

    /** A reset faster than this is treated as an active refusal, not a routed-but-silent host. */
    const val FAST_REFUSAL_MS = 500L

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
    ): Long? = probe(host, port, timeoutMs, attempts, connector).latencyMs

    /**
     * Same probe as [measure], but keeps *why* it failed. Every attempt is tried (like
     * [measure]); a single success wins with the fastest sample, otherwise the last failure
     * classification is reported.
     */
    fun probe(
        host: String,
        port: Int,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        attempts: Int = DEFAULT_ATTEMPTS,
        connector: Connector = Connector { h, p, t -> connectOnce(h, p, t) }
    ): TcpConnectResult {
        if (host.isBlank() || port !in 1..65535 || attempts <= 0) {
            return TcpConnectResult(TcpConnectOutcome.ERROR)
        }
        val samples = mutableListOf<Long>()
        var last = TcpConnectResult(TcpConnectOutcome.UNREACHABLE)
        repeat(attempts) {
            val started = System.nanoTime()
            try {
                val sample = connector.connect(host, port, timeoutMs)
                if (sample > 0L) samples += sample
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return bestResult(samples) ?: last
            } catch (failure: Exception) {
                val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
                last = TcpConnectResult(
                    outcome = classify(failure, elapsedMs),
                    failureMs = elapsedMs
                )
            }
        }
        return bestResult(samples) ?: last
    }

    private fun bestResult(samples: List<Long>): TcpConnectResult? =
        bestOf(samples)?.let { TcpConnectResult(TcpConnectOutcome.CONNECTED, latencyMs = it) }

    private fun classify(failure: Exception, elapsedMs: Long): TcpConnectOutcome = when {
        failure is SocketTimeoutException -> TcpConnectOutcome.TIMEOUT
        failure is UnknownHostException -> TcpConnectOutcome.UNREACHABLE
        failure is NoRouteToHostException -> TcpConnectOutcome.UNREACHABLE
        failure is ConnectException -> {
            val message = failure.message.orEmpty().lowercase()
            when {
                message.contains("refused") -> TcpConnectOutcome.REFUSED
                message.contains("unreachable") -> TcpConnectOutcome.UNREACHABLE
                elapsedMs <= FAST_REFUSAL_MS -> TcpConnectOutcome.REFUSED
                else -> TcpConnectOutcome.UNREACHABLE
            }
        }
        else -> TcpConnectOutcome.ERROR
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
