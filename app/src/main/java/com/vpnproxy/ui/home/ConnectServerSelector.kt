package com.vpnproxy.ui.home

import com.vpnproxy.core.connection.LastServerStore
import com.vpnproxy.core.scanner.ServerScanner
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.smartconnect.AutoSwitcher
import com.vpnproxy.smartconnect.ScoringEngine
import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import kotlinx.coroutines.CancellationException

/**
 * P1 connect path: order by last successful server, probe with [ServerScanner.scanForConnection],
 * then pick a live responder (prefer last server if it answered).
 */
internal object ConnectServerSelector {
    private const val TAG = "ConnectSelect"

    suspend fun select(
        configs: List<ParsedConfig>,
        isAutomaticMode: Boolean,
        manuallySelected: ScoringEngine.ScoredServer?,
        lastServerStore: LastServerStore,
        serverScanner: ServerScanner,
        autoSwitcher: AutoSwitcher,
        logCollector: LogCollector,
        traceMsg: (String) -> String,
    ): ScoringEngine.ScoredServer? {
        if (configs.isEmpty()) return null
        val allowedProfiles = configs.map { it.providerTag to it.connectionKey() }.toSet()
        val last = lastServerStore.load()
        val orderedConfigs = if (last == null) configs else {
            val preferred = configs.filter {
                it.address == last.address && it.port == last.port &&
                    (last.uuid.isBlank() || it.uuid == last.uuid)
            }
            preferred + configs.filterNot { c -> preferred.any { it.connectionKey() == c.connectionKey() } }
        }
        logCollector.info(TAG, traceMsg("Connect path: ping-first scanForConnection (${orderedConfigs.size} profiles)"))
        val probed = try {
            serverScanner.scanForConnection(orderedConfigs, targetAvailableCount = 4)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            logCollector.warn(TAG, traceMsg("scanForConnection failed: ${e.message}"))
            serverScanner.availableServers()
        }
        val scanned = probed
            .filter { (it.config.providerTag to it.config.connectionKey()) in allowedProfiles }
            .filterNot { autoSwitcher.isBlacklisted(it.config) }
        val live = scanned.filter { it.successRate > 0f && it.latencyMs != Long.MAX_VALUE }
        logCollector.info(TAG, traceMsg("Ping-first results: live=${live.size} scanned=${scanned.size}"))

        return if (isAutomaticMode) {
            val lastLive = if (last != null) {
                live.firstOrNull {
                    it.config.address == last.address && it.config.port == last.port &&
                        (last.uuid.isBlank() || it.config.uuid == last.uuid)
                }
            } else null
            val best = lastLive ?: serverScanner.bestAvailableServer(live.ifEmpty { scanned })
            if (best != null) {
                autoSwitcher.selectScanned(best, scanned)
            } else {
                logCollector.warn(TAG, traceMsg("No server passed reachability scan; refusing automatic selection"))
                null
            }
        } else {
            manuallySelected?.takeIf { s -> configs.any { it.connectionKey() == s.config.connectionKey() } }
                ?: run {
                    val best = serverScanner.bestAvailableServer(live.ifEmpty { scanned })
                    if (best != null) autoSwitcher.selectScanned(best, scanned)
                    else null
                }
        }
    }
}
