package com.vpnproxy.core.ping

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

fun interface PingExecutor {
    suspend fun ping(target: PingTarget, timeoutMs: Long): TargetResult
}

@Singleton
class RealPingExecutor @Inject constructor() : PingExecutor {
    override suspend fun ping(target: PingTarget, timeoutMs: Long): TargetResult {
        val timestamp = now()
        val result = withTimeoutOrNull(timeoutMs + CLEANUP_GRACE_MS) {
            val addressResult = resolveIpv4(target.host, DNS_TIMEOUT_MS)
            val address = addressResult.address
            if (address == null) {
                TargetResult(target.id, null, false, addressResult.error ?: "dns_fail", timestamp)
            } else {
                val socket = Socket()
                val cancellationHandle = currentCoroutineContext().job.invokeOnCompletion { cause ->
                    if (cause is CancellationException) runCatching { socket.close() }
                }
                try {
                    val rtt = runInterruptible(Dispatchers.IO) {
                        val started = now()
                        socket.connect(
                            InetSocketAddress(address, target.port),
                            timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                        )
                        now() - started
                    }
                    TargetResult(target.id, rtt, true, timestamp = timestamp)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SocketTimeoutException) {
                    TargetResult(target.id, null, false, "socket_timeout", timestamp)
                } catch (e: Exception) {
                    TargetResult(target.id, null, false, e.message ?: "connect_fail", timestamp)
                } finally {
                    cancellationHandle.dispose()
                    runCatching { socket.close() }
                }
            }
        }
        return result ?: TargetResult(target.id, null, false, "outer_timeout", timestamp)
    }

    private suspend fun resolveIpv4(host: String, timeoutMs: Long): DnsResult =
        withContext(Dispatchers.IO) {
            val future = DNS_EXECUTOR.submit(Callable {
                runCatching {
                    InetAddress.getAllByName(host).firstOrNull { it is Inet4Address } as? Inet4Address
                }.getOrNull()
            })
            try {
                val address = runInterruptible(Dispatchers.IO) {
                    future.get(timeoutMs, TimeUnit.MILLISECONDS)
                }
                if (address == null) DnsResult(null, "dns_fail") else DnsResult(address, null)
            } catch (e: TimeoutException) {
                future.cancel(true)
                DnsResult(null, "dns_timeout")
            } catch (e: CancellationException) {
                future.cancel(true)
                throw e
            } catch (e: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                DnsResult(null, "dns_interrupted")
            } catch (e: Exception) {
                future.cancel(true)
                DnsResult(null, "dns_fail")
            } finally {
                if (!future.isDone) future.cancel(true)
            }
        }

    private data class DnsResult(val address: Inet4Address?, val error: String?)

    private fun now(): Long =
        runCatching { SystemClock.elapsedRealtime() }
            .getOrElse { System.nanoTime() / 1_000_000L }

    companion object {
        private const val DNS_TIMEOUT_MS = 1000L
        private const val CLEANUP_GRACE_MS = 200L

        // Bounded pool: timed-out OS DNS lookups cannot create unbounded worker threads.
        private val DNS_EXECUTOR = Executors.newFixedThreadPool(4, ThreadFactory { runnable ->
            Thread(runnable, "ping-dns").apply { isDaemon = true }
        })
    }
}
