package com.vpnproxy.core.protocols.parser

import com.vpnproxy.core.protocols.model.GrpcConfig
import com.vpnproxy.core.protocols.model.HttpTransportConfig
import com.vpnproxy.core.protocols.model.HttpUpgradeConfig
import com.vpnproxy.core.protocols.model.ParseResult
import com.vpnproxy.core.protocols.model.ParsedConfig
import com.vpnproxy.core.protocols.model.RealityConfig
import com.vpnproxy.core.protocols.model.TlsConfig
import com.vpnproxy.core.protocols.model.TlsKind
import com.vpnproxy.core.protocols.model.TransportConfig
import com.vpnproxy.core.protocols.model.TransportKind
import com.vpnproxy.core.protocols.model.UtlsConfig
import com.vpnproxy.core.protocols.model.VmessConfig
import com.vpnproxy.core.protocols.model.WsConfig
import com.vpnproxy.core.protocols.model.XhttpConfig
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

object VmessUriParser {

    private val knownFields = setOf(
        "v", "ps", "add", "port", "id", "aid",
        "scy", "net", "type", "host", "path",
        "tls", "sni", "fp", "alpn", "allowinsecure",
        "pbk", "sid", "spx"
    )

    fun parse(uri: String, providerTag: String): ParseResult<ParsedConfig> {
        return try {
            val trimmed = uri.trim()
            if (!trimmed.startsWith("vmess://", ignoreCase = true)) {
                return ParseResult.Skip("not a vmess URI")
            }

            val payload = trimmed.substringAfter("://").substringBefore('#').trim()
            if (payload.isBlank()) return ParseResult.Skip("missing vmess payload")

            val jsonText = decodeBase64(payload)
                ?: return ParseResult.Skip("invalid vmess base64")

            val json = try {
                JSONObject(jsonText)
            } catch (_: Exception) {
                return ParseResult.Skip("invalid vmess JSON")
            }

            val server = json.optString("add", "").trim()
            if (server.isBlank()) return ParseResult.Skip("missing server")

            val port = parsePort(json.opt("port"))
                ?: return ParseResult.Skip("invalid port")

            val uuid = json.optString("id", "").trim()
            if (uuid.isBlank()) return ParseResult.Skip("missing uuid")

            val warnings = mutableListOf<String>()

            val alterId = parseInt(json.opt("aid")) ?: 0
            if (alterId < 0) {
                warnings += "negative VMess alterId normalized to 0"
            }
            val normalizedAlterId = alterId.coerceAtLeast(0)

            val security = json.optString("scy", "auto")
                .trim()
                .lowercase()
                .ifBlank { "auto" }

            val supportedSecurity = setOf(
                "auto",
                "none",
                "zero",
                "aes-128-gcm",
                "chacha20-poly1305"
            )

            if (security !in supportedSecurity) {
                return ParseResult.Unsupported("unsupported VMess security: $security")
            }

            val tlsValue = json.optString("tls", "")
                .trim()
                .lowercase()

            val publicKey = json.optString("pbk", "")
                .trim()
                .takeIf { it.isNotBlank() }

            val shortId = json.optString("sid", "")
                .trim()
                .takeIf { it.isNotBlank() }

            val spiderX = json.optString("spx", "")
                .trim()
                .takeIf { it.isNotBlank() }

            val tlsKind = when (tlsValue) {
                "", "none" -> TlsKind.NONE
                "tls" -> TlsKind.TLS
                "reality" -> {
                    if (publicKey == null) {
                        return ParseResult.Unsupported("vmess reality without pbk")
                    }
                    TlsKind.REALITY
                }
                else -> return ParseResult.Unsupported("unsupported VMess TLS mode: $tlsValue")
            }

            if (tlsKind != TlsKind.REALITY &&
                (publicKey != null || shortId != null || spiderX != null)
            ) {
                warnings += "reality keys present but tls=${tlsValue.ifBlank { "none" }}"
            }

            val serverName = json.optString("sni", "")
                .trim()
                .takeIf { it.isNotBlank() }

            val fingerprint = json.optString("fp", "")
                .trim()
                .takeIf { it.isNotBlank() }

            val alpn = json.optString("alpn", "")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val insecure = parseBoolean(json.opt("allowinsecure")) ?: false

            val reality = if (tlsKind == TlsKind.REALITY && publicKey != null) {
                RealityConfig(publicKey, shortId)
            } else {
                null
            }

            val tls = if (tlsKind != TlsKind.NONE || serverName != null ||
                fingerprint != null || alpn.isNotEmpty() || insecure || reality != null
            ) {
                TlsConfig(
                    serverName = serverName,
                    insecure = insecure,
                    alpn = alpn,
                    utls = fingerprint?.let { UtlsConfig(it) },
                    reality = reality
                )
            } else {
                null
            }

            val network = json.optString("net", "tcp")
                .trim()
                .lowercase()
                .ifBlank { "tcp" }

            val transportKind = when (network) {
                "tcp", "raw" -> TransportKind.TCP
                "ws" -> TransportKind.WS
                "grpc" -> TransportKind.GRPC
                "http", "h2" -> TransportKind.HTTP
                "httpupgrade" -> TransportKind.HTTP_UPGRADE
                "xhttp" -> TransportKind.XHTTP
                "quic" -> TransportKind.QUIC
                else -> return ParseResult.Unsupported("unsupported VMess transport: $network")
            }

            if (transportKind == TransportKind.QUIC && tlsKind == TlsKind.NONE) {
                return ParseResult.Unsupported("QUIC requires TLS")
            }

            val headerType = json.optString("type", "")
                .trim()
                .lowercase()

            val host = json.optString("host", "")
                .trim()
                .takeIf { it.isNotBlank() }

            val path = json.optString("path", "")
                .takeIf { it.isNotBlank() }

            val transport = when (transportKind) {
                TransportKind.TCP -> null

                TransportKind.WS -> TransportConfig(
                    type = transportKind,
                    ws = WsConfig(
                        path = path,
                        headers = host?.let { mapOf("Host" to it) } ?: emptyMap()
                    )
                )

                TransportKind.GRPC -> TransportConfig(
                    type = transportKind,
                    grpc = GrpcConfig(serviceName = path)
                )

                TransportKind.HTTP -> TransportConfig(
                    type = transportKind,
                    http = HttpTransportConfig(
                        host = host,
                        path = path,
                        method = headerType.takeIf { it.isNotBlank() }
                    )
                )

                TransportKind.HTTP_UPGRADE -> TransportConfig(
                    type = transportKind,
                    httpUpgrade = HttpUpgradeConfig(
                        path = path,
                        host = host
                    )
                )

                TransportKind.XHTTP -> {
                    val mode = headerType.takeIf { it.isNotBlank() }
                    if (mode != null &&
                        mode !in setOf("auto", "packet-up", "stream-up", "stream-one")
                    ) {
                        warnings += "unsupported XHTTP mode: $mode"
                    }
                    TransportConfig(
                        type = transportKind,
                        xhttp = XhttpConfig(
                            mode = mode,
                            path = path,
                            host = host
                        )
                    )
                }

                TransportKind.QUIC -> TransportConfig(type = transportKind)
            }

            val extra = linkedMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.lowercase() !in knownFields) {
                    extra[key] = json.optString(key, "")
                }
            }

            if (spiderX != null) {
                extra["spx"] = spiderX
                warnings += "dropped reality spider_x: retained in extra"
            }

            val remark = json.optString("ps", "")
                .takeIf { it.isNotBlank() }

            val name = remark ?: "$server:$port"

            val config = VmessConfig(
                server = server,
                port = port,
                name = name,
                providerTag = providerTag,
                remark = remark,
                extra = extra,
                uuid = uuid,
                security = security,
                alterId = normalizedAlterId,
                tlsKind = tlsKind,
                tls = tls,
                transport = transport,
                multiplex = null
            )

            ParseResult.Success(config, warnings)
        } catch (e: Exception) {
            ParseResult.Skip("invalid vmess URI: ${e.message ?: "parse error"}")
        }
    }

    private fun decodeBase64(payload: String): String? {
        val normalized = payload
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

    private fun parsePort(value: Any?): Int? {
        val port = parseInt(value) ?: return null
        return port.takeIf { it in 1..65535 }
    }

    private fun parseInt(value: Any?): Int? =
        when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }

    private fun parseBoolean(value: Any?): Boolean? =
        when (value) {
            is Boolean -> value
            is Number -> value.toInt().let { it != 0 }
            is String -> when (value.trim().lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> null
            }
            else -> null
        }
}
