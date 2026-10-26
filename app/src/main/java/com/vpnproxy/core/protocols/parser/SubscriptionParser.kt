package com.vpnproxy.core.protocols.parser

import com.vpnproxy.core.protocols.model.ParseResult
import com.vpnproxy.core.protocols.model.ParsedConfig

class SubscriptionParser {

    fun parse(
        raw: String,
        providerTag: String
    ): ParseResult<List<ParsedConfig>> {
        val format = SubscriptionFormatDetector.detect(raw)

        val content = when (format) {
            SubscriptionFormat.BASE64_BLOB ->
                SubscriptionFormatDetector.tryBase64(raw)
                    ?: return ParseResult.Skip("invalid base64")

            SubscriptionFormat.PLAIN_LIST -> raw

            SubscriptionFormat.UNKNOWN ->
                return ParseResult.Skip("unknown format")
        }

        val results = content
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { parseUri(it, providerTag) }

        val configs = results
            .filterIsInstance<ParseResult.Success<ParsedConfig>>()
            .map { it.value }

        val parserWarnings = results
            .filterIsInstance<ParseResult.Success<ParsedConfig>>()
            .flatMap { it.warnings }

        val unsupported = results.filterIsInstance<ParseResult.Unsupported>()
        val skipped = results.filterIsInstance<ParseResult.Skip>()

        val aggregatedWarnings = mutableListOf<String>()

        if (unsupported.isNotEmpty()) {
            val grouped = unsupported
                .groupingBy { it.reason }
                .eachCount()
                .entries
                .sortedBy { it.key }
                .joinToString(", ") { (reason, count) ->
                    "$reason: $count"
                }

            aggregatedWarnings.add(
                "Unsupported ${unsupported.size} links: $grouped"
            )
        }

        if (skipped.isNotEmpty()) {
            val grouped = skipped
                .groupingBy { it.reason }
                .eachCount()
                .entries
                .sortedBy { it.key }
                .joinToString(", ") { (reason, count) ->
                    "$reason: $count"
                }

            aggregatedWarnings.add(
                "Skipped ${skipped.size} links: $grouped"
            )
        }

        return if (configs.isEmpty()) {
            ParseResult.Skip(
                buildString {
                    append("no valid configs found")
                    if (aggregatedWarnings.isNotEmpty()) {
                        append("; ")
                        append(aggregatedWarnings.joinToString("; "))
                    }
                }
            )
        } else {
            ParseResult.Success(
                value = configs,
                warnings = parserWarnings + aggregatedWarnings
            )
        }
    }

    private fun parseUri(
        uri: String,
        providerTag: String
    ): ParseResult<ParsedConfig> {
        val lower = uri.lowercase()

        return when {
            lower.startsWith("vless://") ->
                VlessUriParser.parse(uri, providerTag)

            lower.startsWith("vmess://") ->
                VmessUriParser.parse(uri, providerTag)

            lower.startsWith("ss://") ->
                SsUriParser.parse(uri, providerTag)

            lower.startsWith("trojan://") ->
                TrojanUriParser.parse(uri, providerTag)

            lower.startsWith("socks://") ||
                lower.startsWith("socks4://") ||
                lower.startsWith("socks4a://") ||
                lower.startsWith("socks5://") ->
                SocksUriParser.parse(uri, providerTag)

            lower.startsWith("http://") ||
                lower.startsWith("https://") ->
                HttpUriParser.parse(uri, providerTag)

            else ->
                ParseResult.Skip("unsupported or unimplemented scheme")
        }
    }
}
