package com.vpnproxy.core.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs control requests for a list of services through the active VPN.
 *
 * The checker is deliberately side-effect free: it does not start a core, does not change
 * settings and does not write to the diagnostic journal.
 *
 * It is a process-wide singleton so "one sweep at a time" holds across screens: leaving and
 * re-entering the screen must not start a second sweep over the same proxy port.
 *
 * Tunnel state is passed as lambdas, not snapshots, so a tunnel that dies mid-sweep stops
 * the remaining targets instead of reporting a misleading verdict.
 */
@Singleton
class ServiceChecker @Inject constructor() {

    internal constructor(probe: ServiceProbe) : this() {
        this.testProbe = probe
    }

    companion object {
        private const val MAX_CONCURRENCY = 3
    }

    private var testProbe: ServiceProbe? = null

    private val defaultProbe by lazy { HttpServiceProbe() }

    private val probe: ServiceProbe
        get() = testProbe ?: defaultProbe

    private val runMutex = Mutex()

    /**
     * @return results, or null when a sweep is already running.
     */
    suspend fun checkAll(
        targets: List<ServiceTarget> = ServiceCatalog.defaultTargets,
        vpnActive: () -> Boolean,
        coreTransitioning: () -> Boolean = { false }
    ): List<ServiceCheckResult>? {
        if (!runMutex.tryLock()) return null
        try {
            if (coreTransitioning()) {
                return targets.map { ServiceCheckResult(it.id, ServiceCheckStatus.TRANSITION) }
            }
            if (!vpnActive()) {
                return targets.map { ServiceCheckResult(it.id, ServiceCheckStatus.VPN_OFF) }
            }
            return coroutineScope {
                val semaphore = Semaphore(MAX_CONCURRENCY)
                targets.map { target ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit { probeOne(target, vpnActive, coreTransitioning) }
                    }
                }.awaitAll()
            }
        } finally {
            runMutex.unlock()
        }
    }

    private suspend fun probeOne(
        target: ServiceTarget,
        vpnActive: () -> Boolean,
        coreTransitioning: () -> Boolean
    ): ServiceCheckResult {
        if (coreTransitioning()) {
            return ServiceCheckResult(target.id, ServiceCheckStatus.TRANSITION)
        }
        if (!vpnActive()) {
            return ServiceCheckResult(target.id, ServiceCheckStatus.VPN_OFF)
        }
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
                // 4xx/5xx: the request reached the server, so the tunnel works. A 403 from
                // Cloudflare is not a blocked VPN, and painting it red would be a lie.
                result.httpCode in 400..599 -> ServiceCheckStatus.ANSWERED
                // 3xx may be a captive portal or a geo bounce: no verdict either way.
                result.httpCode in 300..399 -> ServiceCheckStatus.UNKNOWN
                else -> ServiceCheckStatus.UNKNOWN
            },
            latencyMs = result.latencyMs.takeIf { it > 0 },
            httpCode = result.httpCode,
            reason = result.reason
        )
    }
}
