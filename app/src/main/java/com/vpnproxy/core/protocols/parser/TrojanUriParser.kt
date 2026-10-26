package com.vpnproxy.core.protocols.parser

import com.vpnproxy.core.protocols.model.GrpcConfig
import com.vpnproxy.core.protocols.model.HttpTransportConfig
import com.vpnproxy.core.protocols.model.HttpUpgradeConfig
import com.vpnproxy.core.protocols.model.ParseResult
import com.vpnproxy.core.protocols.model.ParsedConfig
import com.vpnproxy.core.protocols.model.RealityConfig
import com.vpnproxy.core.protocols.model.TlsConfig
import com.vpnproxy.core.protocols.model.TlsKind
import com.vpnproxy.core.protocols.model.TrojanConfig
import com.vpnproxy.core.protocols.model.TransportConfig
import com.vpnproxy.core.protocols.model.TransportKind
import com.vpnproxy.core.protocols.model.UtlsConfig
import com.vpnproxy.core.protocols.model.WsConfig
import com.vpnproxy.core.protocols.model.XhttpConfig
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object TrojanUriParser {

    private val knownParameters = setOf(
        "security", "sni", "peer", "fp", "fingerprint",
        "pbk", "publickey", "sid", "shortid", "spx", "spiderx",
        "alpn", "allowinsecure", "insecure", "type", "network",
        "servicename", "authority", "host", "path", "mode", "ed", "eh"
    )

    fun parse(uri: String, providerTag: String): ParseResult<ParsedConfig> {
        return try {
            if (!uri.trim().startsWith("trojan://", ignoreCase = true)) {
                return ParseResult.Skip("not a trojan URI")
            }

            val body = uri.trim().substringAfter("://")
            val fragmentIndex = body.indexOf('#')
            val withoutRemark = if (fragmentIndex >= 0) body.substring(0, fragmentIndex) else body
            val remark = if (fragmentIndex >= 0) {
                decode(body.substring(fragmentIndex + 1))
            } else {
                null
            }

            val queryIndex = withoutRemark.indexOf('?')
            val authority = if (queryIndex >= 0) withoutRemark.substring(0, queryIndex) else withoutRemark
            val query = if (queryIndex >= 0) withoutRemark.substring(queryIndex + 1) else ""

            val atIndex = authority.lastIndexOf('@')
            if (atIndex <= 0 || atIndex == authority.lastIndex) {
                return ParseResult.Skip("invalid trojan authority")
            }

            val password = decode(authority.substring(0, atIndex))
            if (password.isBlank()) return ParseResult.Skip("empty password")

            val endpoint = parseHostPort(authority.substring(atIndex + 1))
                ?: return ParseResult.Skip("invalid server or port")

            val params = parseQuery(query)
            val warnings = mutableListOf<String>()

            val security = params.first("security")?.lowercase()
            if (security == "none") {
                return ParseResult.Unsupported("trojan requires TLS")
            }

            val publicKey = params.first("pbk", "publicKey")?.takeIf { it.isNotBlank() }
            val shortId = params.first("sid", "shortId")?.takeIf { it.isNotBlank() }
            val spiderX = params.first("spx", "spiderX")?.takeIf { it.isNotBlank() }

            val tlsKind = if (publicKey != null || shortId != null || spiderX != null ||
                security == "reality"
            ) {
                TlsKind.REALITY
            } else {
                TlsKind.TLS
            }

            if (tlsKind == TlsKind.REALITY && publicKey == null) {
                return ParseResult.Unsupported("trojan reality without pbk")
            }

            val serverName = params.first("sni", "peer")?.takeIf { it.isNotBlank() }
            if (serverName == null && !isDomainName(endpoint.first)) {
                warnings += "trojan has no sni and server is not a domain; builder must derive SNI"
            }

            val fingerprint = params.first("fp", "fingerprint")?.takeIf { it.isNotBlank() }
            val alpn = params.first("alpn")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()

            val insecure = params.first("allowInsecure", "insecure")
                ?.toBooleanCompat()
                ?: false

            val tls = TlsConfig(
                serverName = serverName,
                insecure = insecure,
                alpn = alpn,
                utls = fingerprint?.let { UtlsConfig(it) },
                reality = if (tlsKind == TlsKind.REALITY) {
                    RealityConfig(
                        publicKey = requireNotNull(publicKey),
                        shortId = shortId
                    )
                } else {
                    null
                }
            )

            if (spiderX != null) {
                warnings += "reality spider_x retained in extra"
            }

            val network = params.first("type", "network")
                ?.lowercase()
                ?.ifBlank { null }
                ?: "tcp"

            val transportKind = when (network) {
                "tcp", "raw" -> TransportKind.TCP
                "ws" -> TransportKind.WS
                "grpc" -> TransportKind.GRPC
                "http", "h2" -> TransportKind.HTTP
                "httpupgrade" -> TransportKind.HTTP_UPGRADE
                "xhttp" -> TransportKind.XHTTP
                "quic" -> TransportKind.QUIC
                else -> return ParseResult.Unsupported("unsupported Trojan transport: $network")
            }

            val host = params.first("host")?.takeIf { it.isNotBlank() }
            val path = params.first("path")?.takeIf { it.isNotBlank() }
            val serviceName = params.first("serviceName", "authority")?.takeIf { it.isNotBlank() }

            val transport = when (transportKind) {
                TransportKind.TCP -> null
                TransportKind.WS -> TransportConfig(
                    type = transportKind,
                    ws = WsConfig(
                        path = path,
                        headers = host?.let { mapOf("Host" to it) } ?: emptyMap(),
                        maxEarlyData = params.first("ed")?.toIntOrNull(),
                        earlyDataHeaderName = params.first("eh")
                    )
                )
                TransportKind.GRPC -> TransportConfig(
                    type = transportKind,
                    grpc = GrpcConfig(serviceName = serviceName)
                )
                TransportKind.HTTP -> TransportConfig(
                    type = transportKind,
                    http = HttpTransportConfig(host = host, path = path)
                )
                TransportKind.HTTP_UPGRADE -> TransportConfig(
                    type = transportKind,
                    httpUpgrade = HttpUpgradeConfig(path = path, host = host)
                )
                TransportKind.XHTTP -> {
                    val mode = params.first("mode")
                    if (mode != null && mode !in setOf("auto", "packet-up", "stream-up", "stream-one")) {
                        warnings += "unsupported XHTTP mode: $mode"
                    }
                    TransportConfig(
                        type = transportKind,
                        xhttp = XhttpConfig(mode = mode, path = path, host = host)
                    )
                }
                TransportKind.QUIC -> {
                    if (tlsKind == TlsKind.NONE) {
                        return ParseResult.Unsupported("QUIC requires TLS")
                    }
                    TransportConfig(type = transportKind)
                }
            }

            val extra = params.entries()
                .filterKeys { it !in knownParameters }
                .toMutableMap()

            if (spiderX != null) extra["spx"] = spiderX

            val name = remark?.takeIf { it.isNotBlank() } ?: "${endpoint.first}:${endpoint.second}"

            val config = TrojanConfig(
                server = endpoint.first,
                port = endpoint.second,
                name = name,
                providerTag = providerTag,
                remark = remark,
                extra = extra,
                password = password,
                tls = tls,
                tlsKind = tlsKind,
                transport = transport
            )

            ParseResult.Success(config, warnings)
        } catch (e: Exception) {
            ParseResult.Skip("invalid trojan URI: ${e.message ?: "parse error"}")
        }
    }

    private fun parseHostPort(value: String): Pair<String, Int>? {
        val host: String
        val portString: String

        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end <= 1 || end + 1 >= value.length || value[end + 1] != ':') return null
            host = value.substring(1, end)
            portString = value.substring(end + 2)
        } else {
            val colon = value.lastIndexOf(':')
            if (colon <= 0 || colon == value.lastIndex) return null
            host = value.substring(0, colon)
            portString = value.substring(colon + 1)
            if (host.contains(':')) return null
        }

        val port = portString.toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return host to port
    }

    private fun parseQuery(query: String): QueryParameters {
        val values = linkedMapOf<String, String>()
        query.split('&').forEach { part ->
            if (part.isBlank()) return@forEach
            val index = part.indexOf('=')
            val key = decode(if (index >= 0) part.substring(0, index) else part).lowercase()
            val value = decode(if (index >= 0) part.substring(index + 1) else "")
            if (key.isNotBlank() && key !in values) values[key] = value
        }
        return QueryParameters(values)
    }

    private fun decode(value: String): String =
        try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            value
        }

    private fun String.toBooleanCompat(): Boolean? = when (lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> null
    }

    private fun isDomainName(host: String): Boolean =
        !host.contains(':') &&
            !host.matches(Regex("""\\d{1,3}(\\.\\d{1,3}){3}"""))

    private class QueryParameters(private val values: Map<String, String>) {
        fun first(vararg aliases: String): String? =
            aliases.firstNotNullOfOrNull { values[it.lowercase()] }

        fun entries(): Map<String, String> = values
    }
}
