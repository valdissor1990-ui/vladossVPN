package com.vpnproxy.core.dualpath

import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig

/** Heuristic region label from subscription display name (flags / keywords). */
object ServerRegionClassifier {

    private val russianKeywords = listOf(
        "russia", "россия", "москва", "moscow", "beget", " vk", "vk —", "vk-", "aeza"
    )

    /** RU-bypass pool nodes that should not be used as true foreign AI exits. */
    private val ruBypassHints = listOf(
        "обход глушилок", "глушил", "whitelist", "white-list", "white_list"
    )

    private val foreignFlagHints = listOf(
        "🇸🇪", "🇩🇪", "🇺🇸", "🇫🇮", "🇵🇱", "🇳🇱", "🇬🇧", "🇫🇷", "🇯🇵", "🇸🇬",
        "🇨🇦", "🇦🇺", "🇪🇸", "🇮🇹", "🇨🇭", "🇦🇹", "🇳🇴", "🇩🇰", "🇧🇪", "🇵🇹",
        "🇦🇪", "🇹🇷", "🇭🇰", "🇹🇼", "🇰🇷", "🇧🇷", "🇮🇳", "🇲🇨"
    )

    fun isLikelyRussianExit(name: String): Boolean {
        if (name.contains("🇷🇺")) return true
        val lower = " ${name.lowercase()} "
        return russianKeywords.any { lower.contains(it) }
    }

    fun isForeignCandidate(config: ParsedConfig): Boolean {
        val name = config.name
        if (isLikelyRussianExit(name)) return false
        val lower = name.lowercase()
        if (ruBypassHints.any { lower.contains(it) }) return false
        return true
    }

    private fun foreignScore(name: String): Int {
        if (isLikelyRussianExit(name)) return -1
        val lower = name.lowercase()
        if (ruBypassHints.any { lower.contains(it) }) return 0
        var score = 1
        if (foreignFlagHints.any { name.contains(it) }) score += 2
        if ("hysteria" in lower || "hy2" in lower) score += 1
        return score
    }

    fun pickBestForeign(
        ranked: List<ParsedConfig>,
        preferredNot: ParsedConfig? = null
    ): ParsedConfig? {
        val preferredKey = preferredNot?.connectionKey()
        return ranked
            .asSequence()
            .filter { isForeignCandidate(it) }
            .filter { preferredKey == null || it.connectionKey() != preferredKey }
            .sortedByDescending { foreignScore(it.name) }
            .firstOrNull()
    }
}
