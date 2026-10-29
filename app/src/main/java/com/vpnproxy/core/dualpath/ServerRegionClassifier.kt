package com.vpnproxy.core.dualpath

import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig

/** Heuristic region label from subscription display name (flags / keywords). */
object ServerRegionClassifier {

    private val russianKeywords = listOf(
        "russia", "россия", "москва", "moscow", "beget", " vk", "vk —", "vk —", "vk-"
    )

    fun isLikelyRussianExit(name: String): Boolean {
        if (name.contains("🇷🇺")) return true
        val lower = " ${name.lowercase()} "
        return russianKeywords.any { lower.contains(it) }
    }

    fun isForeignCandidate(config: ParsedConfig): Boolean =
        !isLikelyRussianExit(config.name.orEmpty())

    fun pickBestForeign(
        ranked: List<ParsedConfig>,
        preferredNot: ParsedConfig? = null
    ): ParsedConfig? {
        val foreign = ranked.filter { isForeignCandidate(it) }
        if (foreign.isEmpty()) return null
        val preferredKey = preferredNot?.connectionKey()
        return foreign.firstOrNull { preferredKey == null || it.connectionKey() != preferredKey }
            ?: foreign.firstOrNull()
    }
}
