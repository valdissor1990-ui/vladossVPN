package com.vpnproxy.core.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Runs control requests for a list of services through the active VPN.
 *
 * The checker is deliberately side-effect free: it does not start a core, does
 * not change settings and does not write to the diagnostic journal. The caller
 * passes the tunnel state so the class stays testable on the JVM.
 *
 * Only one run happens at a time: a second call returns null instead of
 * queueing, so the UI cannot fire overlapping sweeps over the same proxy port.
 */
class ServiceChecker(
    private val probe: ServiceProbe = HttpServiceProbe()
) {
    companion object {
        private const val MAX_CONCURRENCY = 3
    }

    private val runMutex = Mutex()

    /**
     * @return results, or null when a sweep is already running.
     */
    suspend fun checkAll(
        targets: List<ServiceTarget> = ServiceCatalog.defaultTargets,
        vpnActive: Boolean,
        coreTransitioning: Boolean = false
    ): List<ServiceCheckResult>? {
        if (!runMutex.tryLock()) return null
        try {
            if (coreTransitioning) {
                return targets.map { ServiceCheckResult(it.id, ServiceCheckStatus.TRANSITION) }
            }
            if (!vpnActive) {
                return targets.map { ServiceCheckResult(it.id, ServiceCheckStatus.VPN_OFF) }
            }
            return coroutineScope {
                val semaphore = Semaphore(MAX_CONCURRENCY)
                targets.map { target ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit { probeOne(target) }
                    }
                }.awaitAll()
            }
        } finally {
            runMutex.unlock()
        }
    }

    private suspend fun probeOne(target: ServiceTarget): ServiceCheckResult {
        val result = try {
            probe.probe(target)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ServiceProbeResult(reachable = false, latencyMs = 0L, reason = e.javaClass.simpleName)
        }
        return ServiceCheckResult(
            serviceId = target.id,
            status = when {
                result.reachable -> ServiceCheckStatus.REACHABLE
                // A redirect does not prove the target answered: it may be a captive portal
                // or a geo bounce. Treat it as "could not verify", not as "blocked".
                result.httpCode in 300..399 -> ServiceCheckStatus.UNKNOWN
                result.httpCode != null -> ServiceCheckStatus.UNREACHABLE
                else -> ServiceCheckStatus.UNKNOWN
            },
            latencyMs = result.latencyMs.takeIf { it > 0 },
            httpCode = result.httpCode,
            reason = result.reason
        )
    }
}
