package com.vpnproxy.core.ping

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PingService @Inject constructor(
    private val executor: PingExecutor
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 3000
    }

    private fun monotonicNowMs(): Long =
        runCatching { SystemClock.elapsedRealtime() }
            .getOrElse { System.nanoTime() / 1_000_000L }

    suspend fun pingAll(
        targets: List<PingTarget>,
        concurrency: Int = 10,
        timeoutMs: Long = 2000L,
        region: PingRegion = targets.firstOrNull()?.regions?.singleOrNull() ?: PingRegion.GLOBAL
    ): MultiPingResult {
        require(concurrency > 0) { "concurrency must be greater than zero" }
        require(timeoutMs > 0) { "timeoutMs must be greater than zero" }
        val semaphore = Semaphore(concurrency)
        val results = coroutineScope {
            targets.map { target ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        withTimeoutOrNull(timeoutMs) { executor.ping(target, timeoutMs) }
                            ?: TargetResult(target.id, null, false, "timeout", monotonicNowMs())
                    }
                }
            }.awaitAll()
        }
        return MultiPingResult(results, region, monotonicNowMs())
    }
}
