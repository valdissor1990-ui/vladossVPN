package com.vpnproxy.ui.home

import com.vpnproxy.core.connection.LastServerStore
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.smartconnect.AutoSwitcher
import com.vpnproxy.smartconnect.ScoringEngine
import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig

/**
 * Initial connection must not depend on an external preflight probe endpoint.
 * Select a known-good or first usable profile immediately; the VPN core and traffic
 * validator determine whether it actually works. Background ranking runs after connection.
 */
internal object ConnectServerSelector {
    private const val TAG = "ConnectSelect"

    fun select(
        configs: List<ParsedConfig>,
        isAutomaticMode: Boolean,
        manuallySelected: ScoringEngine.ScoredServer?,
        lastServerStore: LastServerStore,
        autoSwitcher: AutoSwitcher,
        logCollector: LogCollector,
        traceMsg: (String) -> String,
    ): ScoringEngine.ScoredServer? {
        if (configs.isEmpty()) return null

        val usable = configs.filter { it.isValid() && !autoSwitcher.isBlacklisted(it) }
        if (usable.isEmpty()) {
            logCollector.warn(TAG, traceMsg("No non-blacklisted valid profiles available"))
            return null
        }

        val manualConfig = manuallySelected?.config?.takeIf { selected ->
            usable.any { it.connectionKey() == selected.connectionKey() }
        }
        val saved = lastServerStore.load()
        val lastConfig = saved?.let { last ->
            usable.firstOrNull {
                it.address == last.address && it.port == last.port &&
                    (last.uuid.isBlank() || it.uuid == last.uuid)
            }
        }
        val selectedConfig = manualConfig ?: if (isAutomaticMode) usable.first() else lastConfig ?: usable.first()
        val source = when {
            manualConfig != null -> "manual"
            isAutomaticMode -> "lowest-latency-verified"
            lastConfig != null -> "last-success"
            else -> "first-verified"
        }
        logCollector.info(
            TAG,
            traceMsg("Immediate connect selection: ${selectedConfig.name}; source=$source; profiles=${usable.size}; automatic=$isAutomaticMode")
        )
        return autoSwitcher.selectImmediate(selectedConfig, usable)
    }
}
