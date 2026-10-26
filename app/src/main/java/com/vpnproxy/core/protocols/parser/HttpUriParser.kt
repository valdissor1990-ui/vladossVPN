package com.vpnproxy.core.protocols.parser

import com.vpnproxy.core.protocols.model.HttpConfig
import com.vpnproxy.core.protocols.model.ParseResult
import com.vpnproxy.core.protocols.model.ParsedConfig
import com.vpnproxy.core.protocols.model.TlsConfig
import com.vpnproxy.core.protocols.model.TlsKind
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object HttpUriParser {

    fun parse(uri: String, providerTag: String): ParseResult<ParsedConfig> {
        val input = uri.trim()
        val schemeEnd = input.indexOf("://")

        if (schemeEnd <= 0) {
            return ParseResult.Skip("missing URI scheme")
        }

        val scheme = input.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") {
            return ParseResult.Skip("unsupported HTTP proxy scheme: $scheme")
        }

        val warnings = mutableListOf<String>()
        val extra = linkedMapOf<String, String>()
        var tlsKind = if (scheme == "https") TlsKind.TLS else TlsKind.NONE

        val remainder = input.substring(schemeEnd + 3)

        val fragmentIndex = remainder.indexOf('#')
        val beforeFragment =
            if (fragmentIndex >= 0) remainder.substring(0, fragmentIndex)
            else remainder
        val rawRemark =
            if (fragmentIndex >= 0) remainder.substring(fragmentIndex + 1)
            else null

        val queryIndex = beforeFragment.indexOf('?')
        val beforeQuery =
            if (queryIndex >= 0) beforeFragment.substring(0, queryIndex)
            else beforeFragment
        val rawQuery =
            if (queryIndex >= 0) beforeFragment.substring(queryIndex + 1)
            else null

        val authorityEnd = findPathStart(beforeQuery)
        val authority =
            if (authorityEnd >= 0) beforeQuery.substring(0, authorityEnd)
            else beforeQuery
        val path =
            if (authorityEnd >= 0) beforeQuery.substring(authorityEnd)
            else null

        if (!path.isNullOrEmpty()) {
            extra["path"] = path
            warnings += "HTTP proxy path is not supported by HttpConfig; retained in extra"
        }

        val atIndex = authority.lastIndexOf('@')
        val rawUserInfo = if (atIndex >= 0) authority.substring(0, atIndex) else null
        val rawHostPort = if (atIndex >= 0) authority.substring(atIndex + 1) else authority

        var username: String? = null
        var password: String? = null

        if (rawUserInfo != null) {
            val colon = rawUserInfo.indexOf(':')
            if (colon >= 0) {
                username = decode(rawUserInfo.substring(0, colon)).ifBlank { null }
                password = decode(rawUserInfo.substring(colon + 1)).ifBlank { null }
            } else {
                username = decode(rawUserInfo).ifBlank { null }
            }
        }

        val endpoint = parseHostPort(rawHostPort)
            ?: return ParseResult.Skip("invalid server or port")

        val query = parseQuery(rawQuery)

        query["sni"]?.takeIf { it.isNotBlank() }?.let { extra["sni"] = it }
        query["peer"]?.takeIf { it.isNotBlank() }?.let { extra.putIfAbsent("sni", it) }

        val insecure = listOf("insecure", "allowInsecure", "allowinsecure")
            .mapNotNull { query[it] }
            .firstOrNull()
            ?.let { it == "1" || it.equals("true", true) }
            ?: false

        val serverName = query["sni"] ?: query["peer"]
        val tls = if (tlsKind != TlsKind.NONE || serverName != null || insecure) {
            TlsConfig(
                serverName = serverName?.takeIf { it.isNotBlank() },
                insecure = insecure
            )
        } else {
            null
        }

        // Preserve unknown query keys
        val known = setOf("sni", "peer", "insecure", "allowInsecure", "allowinsecure")
        query.forEach { (k, v) ->
            if (k.lowercase() !in known.map { it.lowercase() }) {
                extra.putIfAbsent(k, v)
            }
        }

        val remark = rawRemark?.let { decode(it) }?.takeIf { it.isNotBlank() }
        val name = remark ?: "${endpoint.first}:${endpoint.second}"

        val config = HttpConfig(
            server = endpoint.first,
            port = endpoint.second,
            name = name,
            providerTag = providerTag,
            remark = remark,
            extra = extra,
            username = username,
            password = password,
            tlsKind = tlsKind,
            tls = tls
        )

        return ParseResult.Success(config, warnings)
    }

    private fun findPathStart(value: String): Int {
        var insideBrackets = false

        for (index in value.indices) {
            when (value[index]) {
                '[' -> insideBrackets = true
                ']' -> insideBrackets = false
                '/' -> if (!insideBrackets) return index
            }
        }

        return -1
    }

    private fun parseHostPort(authority: String): Pair<String, Int>? {
        val value = authority.trim()
        if (value.isEmpty()) return null

        val host: String
        val portText: String

        if (value.startsWith("[")) {
            val closingBracket = value.indexOf(']')
            if (closingBracket <= 1) return null
            if (closingBracket + 1 >= value.length ||
                value[closingBracket + 1] != ':'
            ) {
                return null
            }

            host = value.substring(1, closingBracket)
            portText = value.substring(closingBracket + 2)
        } else {
            val colon = value.lastIndexOf(':')
            if (colon <= 0 || colon == value.lastIndex) return null

            if (value.substring(0, colon).contains(':')) return null

            host = value.substring(0, colon)
            portText = value.substring(colon + 1)
        }

        if (host.isBlank()) return null
        val port = portText.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return host to port
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()

        val result = linkedMapOf<String, String>()

        rawQuery.split('&').forEach { item ->
            if (item.isEmpty()) return@forEach

            val separator = item.indexOf('=')
            val rawKey = if (separator >= 0) item.substring(0, separator) else item
            val rawValue = if (separator >= 0) item.substring(separator + 1) else ""

            val key = decode(rawKey)
            val value = decode(rawValue)

            if (key.isNotEmpty()) {
                result.putIfAbsent(key, value)
            }
        }

        return result
    }

    private fun decode(value: String): String =
        try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            value
        }
}
