package com.vpnproxy.core.singbox

import android.content.Context

/**
 * Persisted routing choice. Unlike [DiagnosticSettings] this is a user-facing setting, so it is
 * read regardless of the diagnostic flag.
 */
object RoutingSettings {
    private const val PREFS = "routing_settings"
    private const val KEY_PRESET = "routing_preset"

    val presets: List<RoutingPreset> = RoutingPreset.entries.toList()

    fun load(context: Context): RoutingRules {
        val preset = preset(context)
        if (preset != RoutingPreset.BYPASS_RU) return RoutingRules(preset)
        val geoip = RuleSetStore.geoipFile(context)
        val geosite = RuleSetStore.geositeFile(context)
        return RoutingRules(
            preset = preset,
            geoipRuPath = geoip.takeIf { it.isFile }?.absolutePath,
            geositeRuPath = geosite.takeIf { it.isFile }?.absolutePath
        )
    }

    fun preset(context: Context): RoutingPreset =
        RoutingPreset.fromId(preferences(context).getString(KEY_PRESET, null))

    fun setPreset(context: Context, preset: RoutingPreset) {
        preferences(context).edit().putString(KEY_PRESET, preset.id).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
