package com.vpnproxy.core.protocols.parser

import com.vpnproxy.core.protocols.model.ParseResult
import com.vpnproxy.core.protocols.model.ParsedConfig
import com.vpnproxy.core.protocols.model.ShadowsocksConfig
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

object SsUriParser {

    private val currentMethods = setOf(
        "2022-blake3-aes-128-gcm",
        "2022-blake3-aes-256-gcm",
        "2022-blake3-chacha20-poly1305",
        "none",
        "aes-128-gcm",
        "aes-192-gcm",
        "aes-256-gcm",
        "chacha20-ietf-poly1305",
        "xchacha20-ietf-poly1305"
    )

    private val legacyMethods = setOf(
        "aes-128-ctr",
        "aes-192-ctr",
        "aes-256-ctr",
        "aes-128-cfb",
        "aes-192-cfb",
        "aes-256-cfb",
        "rc4-md5",
        "chacha20-ietf",
        "xchacha20"
    )

    fun parse(uri: String, providerTag: String): ParseResult<ParsedConfig> {
        return try {
            val trimmed = uri.trim()
            if (!trimmed.startsWith("ss://", ignoreCase = true)) {
                return ParseResult.Skip("not an ss URI")
            }

            val body = trimmed.substringAfter("://")
            val fragmentIndex = body.indexOf('#')
            val beforeFragment =
                if (fragmentIndex >= 0) body.substring(0, fragmentIndex) else body
            val remark =
                if (fragmentIndex >= 0) decode(body.substring(fragmentIndex + 1)) else null

            val queryIndex = beforeFragment.indexOf('?')
            val authority =
                if (queryIndex >= 0) beforeFragment.substring(0, queryIndex)
                else beforeFragment
            val query =
                if (queryIndex >= 0) beforeFragment.substring(queryIndex + 1)
                else ""

            val params = parseQuery(query)
            val warnings = mutableListOf<String>()

            val parsed = parseSsAuthority(authority)
                ?: return ParseResult.Skip("invalid ss authority")

            val method = parsed.method.trim().lowercase()
            if (method.isBlank()) return ParseResult.Skip("empty method")

            val password = parsed.password
            if (password.isEmpty()) return ParseResult.Skip("empty password")

            when {
                method in currentMethods -> Unit
                method in legacyMethods -> warnings += "deprecated method: $method"
                else -> return ParseResult.Unsupported("unknown cipher: $method")
            }

            val pluginParameter = params.first("plugin")
            val explicitPluginOpts = params.first("plugin_opts", "plugin-opts")

            val plugin: String?
            val pluginOpts: String?

            if (pluginParameter != null) {
                val segments = pluginParameter.split(';')
                plugin = segments.firstOrNull()?.takeIf { it.isNotBlank() }
                val embeddedOpts = segments.drop(1).joinToString(";")
                    .takeIf { it.isNotBlank() }

                pluginOpts = explicitPluginOpts ?: embeddedOpts

                if (plugin != null) {
                    warnings += "ss plugin parsed, support decided in builder: $plugin"
                }
            } else {
                plugin = null
                pluginOpts = explicitPluginOpts
                if (pluginOpts != null) {
                    warnings += "ss plugin_opts present without plugin name"
                }
            }

            val extra = params.entries()
                .filterKeys {
                    it !in setOf("plugin", "plugin_opts", "plugin-opts")
                }
                .toMutableMap()

            val uot = params.first("udp_over_tcp", "uot")
            if (uot != null) {
                extra["udp_over_tcp"] = uot
                warnings += "udp_over_tcp retained in extra; multiplex conflict validation deferred to PR2"
            }

            val name = remark?.takeIf { it.isNotBlank() }
                ?: "${parsed.server}:${parsed.port}"

            val config = ShadowsocksConfig(
                server = parsed.server,
                port = parsed.port,
                name = name,
                providerTag = providerTag,
                remark = remark,
                extra = extra,
                method = method,
                password = password,
                plugin = plugin,
                pluginOpts = pluginOpts
            )

            ParseResult.Success(config, warnings)
        } catch (e: Exception) {
            ParseResult.Skip("invalid ss URI: ${e.message ?: "parse error"}")
        }
    }

    private fun parseSsAuthority(authority: String): ParsedAuthority? {
        if (authority.isBlank()) return null

        val atIndex = authority.lastIndexOf('@')

        if (atIndex >= 0) {
            val userInfo = authority.substring(0, atIndex)
            val hostPort = authority.substring(atIndex + 1)

            val endpoint = parseHostPort(hostPort) ?: return null

            val decodedUserInfo = decodeBase64IfPossible(userInfo)
                ?: decode(userInfo)

            val separator = decodedUserInfo.indexOf(':')
            if (separator <= 0) return null

            val method = decodedUserInfo.substring(0, separator)
            val password = decodedUserInfo.substring(separator + 1)

            if (method.isBlank()) return null
            if (password.isEmpty()) return null

            return ParsedAuthority(
                method = method,
                password = password,
                server = endpoint.first,
                port = endpoint.second
            )
        }

        val decoded = decodeBase64IfPossible(authority) ?: return null
        val decodedAt = decoded.lastIndexOf('@')
        if (decodedAt <= 0 || decodedAt == decoded.lastIndex) return null

        val userInfo = decoded.substring(0, decodedAt)
        val hostPort = decoded.substring(decodedAt + 1)
        val separator = userInfo.indexOf(':')
        if (separator <= 0) return null

        val method = userInfo.substring(0, separator)
        val password = userInfo.substring(separator + 1)
        val endpoint = parseHostPort(hostPort) ?: return null

        if (method.isBlank() || password.isEmpty()) return null

        return ParsedAuthority(
            method = method,
            password = password,
            server = endpoint.first,
            port = endpoint.second
        )
    }

    private fun parseHostPort(value: String): Pair<String, Int>? {
        val host: String
        val portString: String

        if (value.startsWith("[")) {
            val closeBracket = value.indexOf(']')
            if (closeBracket <= 1 ||
                closeBracket + 1 >= value.length ||
                value[closeBracket + 1] != ':'
            ) {
                return null
            }

            host = value.substring(1, closeBracket)
            portString = value.substring(closeBracket + 2).substringBefore('/')
        } else {
            val colon = value.lastIndexOf(':')
            if (colon <= 0 || colon == value.lastIndex) return null

            host = value.substring(0, colon)
            portString = value.substring(colon + 1).substringBefore('/')

            if (host.contains(':')) return null
        }

        val port = portString.toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null

        return host to port
    }

    private fun parseQuery(query: String): QueryParameters {
        val values = linkedMapOf<String, String>()
        if (query.isBlank()) return QueryParameters(values)

        query.split('&').forEach { part ->
            if (part.isBlank()) return@forEach

            val separator = part.indexOf('=')
            val rawKey =
                if (separator >= 0) part.substring(0, separator) else part
            val rawValue =
                if (separator >= 0) part.substring(separator + 1) else ""

            val key = decode(rawKey).lowercase()
            val value = decode(rawValue)

            if (key.isNotBlank() && key !in values) {
                values[key] = value
            }
        }

        return QueryParameters(values)
    }

    private fun decodeBase64IfPossible(value: String): String? {
        val normalized = value
            .replace('-', '+')
            .replace('_', '/')
            .filterNot { it.isWhitespace() }

        if (normalized.isBlank()) return null

        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)

        return try {
            String(
                Base64.getDecoder().decode(padded),
                StandardCharsets.UTF_8
            ).takeIf { it.isNotBlank() }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun decode(value: String): String =
        try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            value
        }

    private data class ParsedAuthority(
        val method: String,
        val password: String,
        val server: String,
        val port: Int
    )

    private class QueryParameters(private val values: Map<String, String>) {
        fun first(vararg aliases: String): String? =
            aliases.firstNotNullOfOrNull { values[it.lowercase()] }

        fun entries(): Map<String, String> = values
    }
}
