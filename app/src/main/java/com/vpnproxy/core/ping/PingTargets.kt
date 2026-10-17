package com.vpnproxy.core.ping

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

enum class PingProtocol { TCP_CONNECT }
enum class PingRegion { RU, EU, US, ASIA, GLOBAL }

data class PingTarget(
    val id: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val protocol: PingProtocol,
    val weight: Double,
    val regions: Set<PingRegion>
)

object PingTargets {
    private val allRegions = setOf(PingRegion.RU, PingRegion.EU, PingRegion.US, PingRegion.ASIA, PingRegion.GLOBAL)
    private val common = listOf(
        PingTarget("google_dns", "Google DNS", "8.8.8.8", 53, PingProtocol.TCP_CONNECT, .15, allRegions),
        PingTarget("cloudflare_dns", "Cloudflare DNS", "1.1.1.1", 53, PingProtocol.TCP_CONNECT, .15, allRegions),
        PingTarget("telegram", "Telegram", "149.154.167.51", 443, PingProtocol.TCP_CONNECT, .12, allRegions),
        PingTarget("youtube", "YouTube", "youtube.com", 443, PingProtocol.TCP_CONNECT, .12, allRegions),
        PingTarget("github", "GitHub", "github.com", 443, PingProtocol.TCP_CONNECT, .10, allRegions),
        PingTarget("cloudflare_doh", "Cloudflare DoH", "cloudflare-dns.com", 443, PingProtocol.TCP_CONNECT, .08, allRegions),
        PingTarget("quad9", "Quad9", "9.9.9.9", 53, PingProtocol.TCP_CONNECT, .08, allRegions),
        PingTarget("steam", "Steam", "store.steampowered.com", 443, PingProtocol.TCP_CONNECT, .05, allRegions)
    )
    private val ruOnly = listOf(
        PingTarget("yandex", "Yandex", "yandex.ru", 443, PingProtocol.TCP_CONNECT, .08, setOf(PingRegion.RU)),
        PingTarget("rutracker", "RuTracker", "rutracker.org", 443, PingProtocol.TCP_CONNECT, .07, setOf(PingRegion.RU))
    )
    private val international = listOf(
        PingTarget("x_com", "X", "x.com", 443, PingProtocol.TCP_CONNECT, .08, setOf(PingRegion.EU, PingRegion.US, PingRegion.ASIA)),
        PingTarget("reddit", "Reddit", "reddit.com", 443, PingProtocol.TCP_CONNECT, .07, setOf(PingRegion.EU, PingRegion.US, PingRegion.ASIA))
    )

    val ALL: List<PingTarget> = common + ruOnly

    fun forRegion(region: PingRegion): List<PingTarget> {
        val selected = when (region) {
            PingRegion.RU -> common + ruOnly
            PingRegion.EU, PingRegion.US, PingRegion.ASIA -> common + international
            PingRegion.GLOBAL -> common
        }
        val total = selected.sumOf { it.weight }
        if (selected.isEmpty() || total <= 0.0) return emptyList()
        return selected.map { it.copy(regions = setOf(region), weight = it.weight / total) }
    }

    fun detectRegion(context: Context? = null): PingRegion {
        val networkCountry = runCatching {
            if (context == null) "" else {
                val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                manager?.networkCountryIso.orEmpty().uppercase(Locale.ROOT)
            }
        }.getOrDefault("")
        val country = networkCountry.ifBlank { Locale.getDefault().country }.uppercase(Locale.ROOT)
        return when (country) {
            "RU", "KZ", "BY", "UA" -> PingRegion.RU
            "US", "CA" -> PingRegion.US
            "JP", "KR", "SG", "CN", "HK", "TW" -> PingRegion.ASIA
            "DE", "FR", "GB", "NL", "PL", "BE", "AT", "CH", "ES", "IT", "PT", "SE", "NO", "FI", "DK", "IE", "CZ", "SK", "HU", "RO", "BG", "GR" -> PingRegion.EU
            else -> PingRegion.GLOBAL
        }
    }
}
