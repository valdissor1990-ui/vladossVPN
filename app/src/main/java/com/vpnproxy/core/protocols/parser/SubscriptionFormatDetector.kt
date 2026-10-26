package com.vpnproxy.core.protocols.parser

import java.util.Base64

enum class SubscriptionFormat {
    BASE64_BLOB,
    PLAIN_LIST,
    UNKNOWN
}

object SubscriptionFormatDetector {

    fun detect(raw: String): SubscriptionFormat {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return SubscriptionFormat.UNKNOWN

        val decoded = tryBase64(trimmed)
        if (decoded != null && decoded.lines().any { it.trim().isKnownScheme() }) {
            return SubscriptionFormat.BASE64_BLOB
        }

        if (trimmed.lines().any { it.trim().isKnownScheme() }) {
            return SubscriptionFormat.PLAIN_LIST
        }

        return SubscriptionFormat.UNKNOWN
    }

    internal fun tryBase64(value: String): String? {
        val input = value.trim()
        if (input.isEmpty()) return null

        val normalized = input
            .replace('-', '+')
            .replace('_', '/')
            .filterNot { it.isWhitespace() }

        if (normalized.isEmpty()) return null

        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)

        return try {
            val decoded = Base64.getDecoder().decode(padded)
            decoded.toString(Charsets.UTF_8)
                .takeIf { text ->
                    text.isNotBlank() &&
                        text.none { it == '\u0000' } &&
                        text.count { it == '\uFFFD' } < 2
                }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun String.isKnownScheme(): Boolean {
        val value = trim().lowercase()
        return value.startsWith("vless://") ||
            value.startsWith("vmess://") ||
            value.startsWith("ss://") ||
            value.startsWith("trojan://") ||
            value.startsWith("hysteria2://") ||
            value.startsWith("hy2://") ||
            value.startsWith("tuic://") ||
            value.startsWith("wg://") ||
            value.startsWith("anytls://") ||
            value.startsWith("naive+https://") ||
            value.startsWith("ssh://") ||
            value.startsWith("socks://") ||
            value.startsWith("http://") ||
            value.startsWith("https://")
    }
}
