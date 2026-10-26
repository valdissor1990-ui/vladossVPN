package com.vpnproxy.core.protocols.parser

import com.vpnproxy.core.protocols.model.GrpcConfig
import com.vpnproxy.core.protocols.model.HttpTransportConfig
import com.vpnproxy.core.protocols.model.HttpUpgradeConfig
import com.vpnproxy.core.protocols.model.MultiplexConfig
import com.vpnproxy.core.protocols.model.ParseResult
import com.vpnproxy.core.protocols.model.ParsedConfig
import com.vpnproxy.core.protocols.model.RealityConfig
import com.vpnproxy.core.protocols.model.TlsConfig
import com.vpnproxy.core.protocols.model.TlsKind
import com.vpnproxy.core.protocols.model.TransportConfig
import com.vpnproxy.core.protocols.model.TransportKind
import com.vpnproxy.core.protocols.model.UtlsConfig
import com.vpnproxy.core.protocols.model.VlessConfig
import com.vpnproxy.core.protocols.model.WsConfig
import com.vpnproxy.core.protocols.model.XhttpConfig
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

object VlessUriParser {

    private val knownParameters = setOf(
        "sni", "peer",
        "fp", "fingerprint",
        "pbk", "publickey",
        "sid", "shortid",
        "spx", "spiderx",
        "type", "network",
        "servicename", "authority",
        "host", "path",
        "security", "flow",
        "mux", "alpn", "allowinsecure",
        "insecure", "eh", "ed", "mode"
    )

    fun parse(uri: String, providerTag: String): ParseResult<ParsedConfig> {
        return try {
            val trimmed = uri.trim()
            if (!trimmed.startsWith("vless://", ignoreCase = true)) {
                return ParseResult.Skip("not a vless URI")
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
            val query = if (queryIndex >= 0) {
                withoutRemark.substring(queryIndex + 1)
            } else {
                ""
            }

            val atIndex = authority.lastIndexOf('@')
            if (atIndex <= 0 || atIndex == authority.lastIndex) {
                return ParseResult.Skip("invalid vless authority")
            }

            val uuid = decode(authority.substring(0, atIndex))
            val hostPort = authority.substring(atIndex + 1)
            if (uuid.isBlank()) return ParseResult.Skip("missing uuid")

            try {
                UUID.fromString(uuid)
            } catch (_: IllegalArgumentException) {
                return ParseResult.Skip("invalid uuid")
            }

            val endpoint = parseHostPort(hostPort)
                ?: return ParseResult.Skip("invalid server or port")

            val params = parseQuery(query)
            val warnings = mutableListOf<String>()

            val security = params.first("security")
                ?.lowercase()
                ?: ""

            val publicKey = params.first("pbk", "publicKey")
                ?.takeIf { it.isNotBlank() }

            val shortId = params.first("sid", "shortId")
                ?.takeIf { it.isNotBlank() }

            val spiderX = params.first("spx", "spiderX")
                ?.takeIf { it.isNotBlank() }

            val tlsKind = when {
                security == "reality" -> TlsKind.REALITY
                security.isBlank() && publicKey != null -> TlsKind.REALITY
                security == "tls" -> TlsKind.TLS
                security == "none" -> TlsKind.NONE
                security.isBlank() -> TlsKind.NONE
                else -> return ParseResult.Unsupported("unsupported vless security: $security")
            }

            if (tlsKind != TlsKind.REALITY &&
                (publicKey != null || shortId != null || spiderX != null)
            ) {
                warnings += "reality keys present but security=${security.ifBlank { "none" }}"
            }

            val flow = params.first("flow")
                ?.takeIf { it.isNotBlank() }
                ?.also {
                    if (it != "xtls-rprx-vision") {
                        warnings += "unsupported VLESS flow: $it"
                    }
                }
                ?.takeIf { it == "xtls-rprx-vision" }

            val fingerprint = params.first("fp", "fingerprint")
                ?.takeIf { it.isNotBlank() }

            val serverName = params.first("sni", "peer")
                ?.takeIf { it.isNotBlank() }

            val insecure = params.first("allowInsecure", "insecure")
                ?.toBooleanCompat()
                ?: false

            val alpn = params.first("alpn")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()

            if (tlsKind == TlsKind.REALITY && publicKey.isNullOrBlank()) {
                warnings += "REALITY security specified without pbk/publicKey"
            }

            val tls = if (tlsKind != TlsKind.NONE || serverName != null ||
                fingerprint != null || alpn.isNotEmpty() || insecure
            ) {
                TlsConfig(
                    serverName = serverName,
                    insecure = insecure,
                    alpn = alpn,
                    utls = fingerprint?.let { UtlsConfig(it) },
                    reality = if (tlsKind == TlsKind.REALITY && publicKey != null) {
                        RealityConfig(publicKey, shortId)
                    } else {
                        null
                    }
                )
            } else {
                null
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
                else -> return ParseResult.Unsupported("unsupported VLESS transport: $network")
            }

            if (transportKind == TransportKind.QUIC && tlsKind == TlsKind.NONE) {
                return ParseResult.Unsupported("QUIC requires TLS")
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
                    http = HttpTransportConfig(
                        host = host,
                        path = path,
                        headers = emptyMap()
                    )
                )
                TransportKind.HTTP_UPGRADE -> TransportConfig(
                    type = transportKind,
                    httpUpgrade = HttpUpgradeConfig(
                        path = path,
                        host = host
                    )
                )
                TransportKind.XHTTP -> TransportConfig(
                    type = transportKind,
                    xhttp = XhttpConfig(
                        mode = params.first("mode"),
                        path = path,
                        host = host
                    )
                )
                TransportKind.QUIC -> TransportConfig(type = transportKind)
            }

            val muxEnabled = params.first("mux")
                ?.toBooleanCompat()
                ?: false

            val multiplex = if (params.first("mux") != null) {
                MultiplexConfig(enabled = muxEnabled)
            } else {
                null
            }

            val extra = params.entries()
                .filterKeys { it.lowercase() !in knownParameters }
                .toMutableMap()

            if (spiderX != null) {
                extra["spx"] = spiderX
                warnings += "dropped reality spider_x: retained in extra"
            }

            val name = remark?.takeIf { it.isNotBlank() }
                ?: "${endpoint.first}:${endpoint.second}"

            val config = VlessConfig(
                server = endpoint.first,
                port = endpoint.second,
                name = name,
                providerTag = providerTag,
                remark = remark,
                extra = extra,
                uuid = uuid,
                flow = flow,
                tlsKind = tlsKind,
                tls = tls,
                transport = transport,
                multiplex = multiplex
            )

            ParseResult.Success(config, warnings)
        } catch (e: Exception) {
            ParseResult.Skip("invalid vless URI: ${e.message ?: "parse error"}")
        }
    }

    private fun parseHostPort(value: String): Pair<String, Int>? {
        val host: String
        val portString: String

        if (value.startsWith("[")) {
            val closeBracket = value.indexOf(']')
            if (closeBracket <= 1 || closeBracket + 1 >= value.length ||
                value[closeBracket + 1] != ':'
            ) {
                return null
            }
            host = value.substring(1, closeBracket)
            portString = value.substring(closeBracket + 2)
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

        if (query.isBlank()) return QueryParameters(values)

        query.split('&').forEach { part ->
            if (part.isBlank()) return@forEach
            val separator = part.indexOf('=')
            val rawKey = if (separator >= 0) part.substring(0, separator) else part
            val rawValue = if (separator >= 0) part.substring(separator + 1) else ""

            val key = decode(rawKey).lowercase()
            val value = decode(rawValue)

            if (key.isNotBlank() && key !in values) {
                values[key] = value
            }
        }

        return QueryParameters(values)
    }

    private fun decode(value: String): String =
        try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            value
        }

    private fun String.toBooleanCompat(): Boolean? =
        when (lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }

    private class QueryParameters(
        private val values: Map<String, String>
    ) {
        fun first(vararg aliases: String): String? =
            aliases.firstNotNullOfOrNull { alias ->
                values[alias.lowercase()]
            }

        fun entries(): Map<String, String> = values
    }
}
