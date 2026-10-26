package com.vpnproxy.core.protocols.parser

import com.vpnproxy.core.protocols.model.ParseResult
import com.vpnproxy.core.protocols.model.ParsedConfig
import com.vpnproxy.core.protocols.model.SocksConfig
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object SocksUriParser {

    fun parse(uri: String, providerTag: String): ParseResult<ParsedConfig> {
        return try {
            val trimmed = uri.trim()
            val lower = trimmed.lowercase()
            if (!(lower.startsWith("socks://") ||
                    lower.startsWith("socks4://") ||
                    lower.startsWith("socks4a://") ||
                    lower.startsWith("socks5://"))
            ) {
                return ParseResult.Skip("not a socks URI")
            }

            val scheme = trimmed.substringBefore("://").lowercase()
            val version = when (scheme) {
                "socks4", "socks4a" -> "4"
                else -> "5"
            }

            val body = trimmed.substringAfter("://")
            val fragmentIndex = body.indexOf('#')
            val withoutRemark = if (fragmentIndex >= 0) body.substring(0, fragmentIndex) else body
            val remark = if (fragmentIndex >= 0) {
                decode(body.substring(fragmentIndex + 1))
            } else {
                null
            }

            val queryIndex = withoutRemark.indexOf('?')
            val authority = if (queryIndex >= 0) {
                withoutRemark.substring(0, queryIndex)
            } else {
                withoutRemark
            }

            val atIndex = authority.lastIndexOf('@')
            val (username, password, hostPort) = if (atIndex >= 0) {
                val userinfo = authority.substring(0, atIndex)
                val hp = authority.substring(atIndex + 1)
                val colon = userinfo.indexOf(':')
                if (colon >= 0) {
                    Triple(
                        decode(userinfo.substring(0, colon)).ifBlank { null },
                        decode(userinfo.substring(colon + 1)).ifBlank { null },
                        hp
                    )
                } else {
                    Triple(decode(userinfo).ifBlank { null }, null, hp)
                }
            } else {
                Triple(null, null, authority)
            }

            val endpoint = parseHostPort(hostPort)
                ?: return ParseResult.Skip("invalid server or port")

            val name = remark?.takeIf { it.isNotBlank() }
                ?: "${endpoint.first}:${endpoint.second}"

            ParseResult.Success(
                SocksConfig(
                    server = endpoint.first,
                    port = endpoint.second,
                    name = name,
                    providerTag = providerTag,
                    remark = remark,
                    version = version,
                    username = username,
                    password = password
                )
            )
        } catch (e: Exception) {
            ParseResult.Skip("socks parse error: ${e.message}")
        }
    }

    private fun parseHostPort(hostPort: String): Pair<String, Int>? {
        val value = hostPort.trim()
        if (value.isEmpty()) return null

        return if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end <= 1) return null
            val host = value.substring(1, end)
            val rest = value.substring(end + 1)
            if (!rest.startsWith(":")) return null
            val port = rest.substring(1).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            host to port
        } else {
            val colon = value.lastIndexOf(':')
            if (colon <= 0) return null
            val host = value.substring(0, colon)
            val port = value.substring(colon + 1).toIntOrNull() ?: return null
            if (host.isBlank() || port !in 1..65535) return null
            host to port
        }
    }

    private fun decode(value: String): String =
        try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            value
        }
}
