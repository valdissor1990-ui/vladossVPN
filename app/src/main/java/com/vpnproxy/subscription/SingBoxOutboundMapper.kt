package com.vpnproxy.subscription

import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Maps a sing-box / Clash-meta style outbound JSON object onto [ParsedConfig].
 *
 * Only VLESS outbounds used to be imported from JSON payloads; every VMess, Trojan,
 * Shadowsocks, Hysteria2, TUIC and SOCKS outbound was rejected as an unsupported
 * protocol even though [com.vpnproxy.core.singbox.SingboxConfigBuilder] can build all
 * of them. Android-free so the mapping is unit testable.
 */
internal object SingBoxOutboundMapper {
    private val supportedTypes = setOf("vmess", "trojan", "shadowsocks", "hysteria2", "tuic", "socks")

    fun supports(type: String?): Boolean =
        type != null && type.lowercase() in supportedTypes

    fun fromOutbound(obj: JsonObject, providerTag: String): ParsedConfig? {
        val type = obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return null
        if (type !in supportedTypes) return null
        val server = obj["server"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val port = obj["server_port"]?.jsonPrimitive?.intOrNull
            ?: if (type == "socks") 1080 else 443
        val name = obj["tag"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "$server:$port"
        val tls = obj["tls"]?.jsonObject
        val transport = obj["transport"]?.jsonObject
        val network = transport?.get("type")?.jsonPrimitive?.contentOrNull
        val path = when {
            network == "grpc" -> transport?.get("service_name")?.jsonPrimitive?.contentOrNull
            else -> transport?.get("path")?.jsonPrimitive?.contentOrNull
        }
        val host = transport?.get("host")?.jsonPrimitive?.contentOrNull
            ?: transport?.get("headers")?.jsonObject?.let { headers ->
                headers["Host"]?.jsonPrimitive?.contentOrNull ?: headers["host"]?.jsonPrimitive?.contentOrNull
            }

        val config = when (type) {
            "vmess" -> ParsedConfig(
                uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull ?: return null,
                address = server,
                port = port,
                transport = "vmess",
                security = if (tlsEnabled(tls)) "tls" else "none",
                sni = tls?.get("server_name")?.jsonPrimitive?.contentOrNull,
                path = path,
                host = host,
                // For VMess/Trojan the builder reads the stream network from mode.
                mode = network,
                fingerprint = tls?.fingerprint(),
                publicKey = null,
                shortId = null,
                flow = null,
                name = name,
                providerTag = providerTag,
                insecure = tls?.get("insecure")?.jsonPrimitive?.booleanOrNull ?: false,
                alpn = tls?.stringList("alpn") ?: emptyList()
            )
            "trojan" -> {
                val password = obj["password"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return null
                ParsedConfig(
                    uuid = password,
                    address = server,
                    port = port,
                    transport = "trojan",
                    security = "tls",
                    sni = tls?.get("server_name")?.jsonPrimitive?.contentOrNull,
                    path = path,
                    host = host,
                    mode = network,
                    fingerprint = tls?.fingerprint(),
                    publicKey = null,
                    shortId = null,
                    flow = null,
                    name = name,
                    providerTag = providerTag,
                    password = password,
                    insecure = tls?.get("insecure")?.jsonPrimitive?.booleanOrNull ?: false,
                    alpn = tls?.stringList("alpn") ?: emptyList()
                )
            }
            "shadowsocks" -> {
                val password = obj["password"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return null
                ParsedConfig(
                    uuid = password,
                    address = server,
                    port = port,
                    transport = "shadowsocks",
                    security = obj["method"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "aes-256-gcm",
                    sni = null,
                    path = null,
                    host = null,
                    mode = null,
                    fingerprint = null,
                    publicKey = null,
                    shortId = null,
                    flow = null,
                    name = name,
                    providerTag = providerTag,
                    password = password
                )
            }
            "hysteria2" -> {
                val password = obj["password"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return null
                val obfs = obj["obfs"]?.jsonObject
                ParsedConfig(
                    uuid = password,
                    address = server,
                    port = port,
                    transport = "hysteria2",
                    security = "tls",
                    sni = tls?.get("server_name")?.jsonPrimitive?.contentOrNull,
                    path = null,
                    host = null,
                    mode = null,
                    fingerprint = tls?.fingerprint(),
                    publicKey = null,
                    shortId = null,
                    flow = null,
                    name = name,
                    providerTag = providerTag,
                    password = password,
                    insecure = tls?.get("insecure")?.jsonPrimitive?.booleanOrNull ?: false,
                    alpn = tls?.stringList("alpn") ?: emptyList(),
                    obfsType = obfs?.get("type")?.jsonPrimitive?.contentOrNull,
                    obfsPassword = obfs?.get("password")?.jsonPrimitive?.contentOrNull
                )
            }
            "tuic" -> ParsedConfig(
                uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull ?: return null,
                address = server,
                port = port,
                transport = "tuic",
                security = "tls",
                sni = tls?.get("server_name")?.jsonPrimitive?.contentOrNull,
                path = null,
                host = null,
                mode = null,
                fingerprint = tls?.fingerprint(),
                publicKey = null,
                shortId = null,
                flow = null,
                name = name,
                providerTag = providerTag,
                password = obj["password"]?.jsonPrimitive?.contentOrNull,
                insecure = tls?.get("insecure")?.jsonPrimitive?.booleanOrNull ?: false,
                alpn = tls?.stringList("alpn") ?: emptyList(),
                congestionControl = obj["congestion_control"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "bbr"
            )
            else -> ParsedConfig(
                uuid = obj["username"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                address = server,
                port = port,
                transport = "socks",
                security = "none",
                sni = null,
                path = null,
                host = null,
                mode = null,
                fingerprint = null,
                publicKey = null,
                shortId = null,
                flow = null,
                name = name,
                providerTag = providerTag,
                password = obj["password"]?.jsonPrimitive?.contentOrNull
            )
        }
        return config.takeIf { it.isValid() }
    }

    /** Reason key for [SubscriptionParser] diagnostics when an outbound cannot be mapped. */
    fun classifyRejection(obj: JsonObject): String {
        val type = obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase()
        if (!supports(type)) return "unsupported_protocol"
        if (obj["server"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) return "missing_server"
        val port = obj["server_port"]?.jsonPrimitive?.intOrNull
        if (port != null && port !in 1..65535) return "invalid_port"
        return when (type) {
            "vmess", "tuic" ->
                if (obj["uuid"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) "missing_credentials"
                else "invalid_or_malformed_entry"
            "trojan", "hysteria2", "shadowsocks" ->
                if (obj["password"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) "missing_credentials"
                else "invalid_or_malformed_entry"
            else -> "invalid_or_malformed_entry"
        }
    }

    private fun tlsEnabled(tls: JsonObject?): Boolean =
        tls?.get("enabled")?.jsonPrimitive?.booleanOrNull == true

    private fun JsonObject.fingerprint(): String? =
        get("utls")?.jsonObject?.get("fingerprint")?.jsonPrimitive?.contentOrNull

    private fun JsonObject.stringList(key: String): List<String>? =
        (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
