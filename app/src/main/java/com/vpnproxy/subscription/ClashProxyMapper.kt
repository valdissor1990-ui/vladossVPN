package com.vpnproxy.subscription

import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import org.json.JSONObject

/**
 * Maps one Clash / Clash.Meta (Mihomo) `proxies:` entry onto the app's internal
 * [ParsedConfig] model.
 *
 * Previously only `type: vless` was imported, so every VMess / Trojan / Shadowsocks /
 * Hysteria2 / TUIC / SOCKS entry of a Clash subscription was silently dropped even
 * though the sing-box outbound builder supports all of them.
 *
 * This object is intentionally free of Android dependencies so the mapping rules can be
 * unit tested on the JVM.
 */
internal object ClashProxyMapper {
    fun toParsedConfig(proxy: Map<String, Any>, providerTag: String): ParsedConfig? {
        val type = proxy.string("type")?.lowercase() ?: return null
        val server = proxy.string("server")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val port = (proxy["port"] as? Number)?.toInt() ?: defaultPort(type)
        val name = proxy.string("name")?.takeIf { it.isNotBlank() } ?: "$server:$port"

        val config = when (type) {
            "vless" -> vless(proxy, server, port, name, providerTag)
            "vmess" -> vmess(proxy, server, port, name, providerTag)
            "trojan" -> trojan(proxy, server, port, name, providerTag)
            "ss", "shadowsocks" -> shadowsocks(proxy, server, port, name, providerTag)
            "socks", "socks5" -> socks(proxy, server, port, name, providerTag)
            "hysteria2", "hy2" -> hysteria2(proxy, server, port, name, providerTag)
            "tuic" -> tuic(proxy, server, port, name, providerTag)
            else -> null
        }
        return config?.takeIf { it.isValid() }
    }

    /** Human-readable rejection reason for [SubscriptionParser] diagnostics. */
    fun classifyRejection(proxy: Map<String, Any>): String {
        val type = proxy.string("type")?.lowercase()
        if (type == null || type !in supportedTypes) return "unsupported_protocol"
        if (proxy.string("server").isNullOrBlank()) return "missing_server"
        val port = (proxy["port"] as? Number)?.toInt() ?: defaultPort(type)
        if (port !in 1..65535) return "invalid_port"
        when (type) {
            "vless", "vmess" -> if (proxy.string("uuid").isNullOrBlank()) return "missing_credentials"
            "trojan", "hysteria2", "hy2" ->
                if (proxy.string("password").isNullOrBlank() && proxy.string("auth").isNullOrBlank()) {
                    return "missing_credentials"
                }
            "ss", "shadowsocks" ->
                if (proxy.string("password").isNullOrBlank() || proxy.string("cipher").isNullOrBlank()) {
                    return "missing_credentials"
                }
            "tuic" -> if (proxy.string("uuid").isNullOrBlank() && proxy.string("token").isNullOrBlank()) {
                return "missing_credentials"
            }
            "socks", "socks5" -> Unit
        }
        val reality = proxy.map("reality-opts")
        if (reality != null && reality.string("public-key").isNullOrBlank()) return "missing_reality_public_key"
        if (type == "vless" && streamNetwork(proxy.string("network")) == null) return "unsupported_transport"
        return "invalid_or_malformed_entry"
    }

    private val supportedTypes = setOf(
        "vless", "vmess", "trojan", "ss", "shadowsocks", "socks", "socks5", "hysteria2", "hy2", "tuic"
    )

    private fun defaultPort(type: String): Int = when (type) {
        "ss", "shadowsocks" -> 8388
        "socks", "socks5" -> 1080
        else -> 443
    }

    private fun vless(
        proxy: Map<String, Any>,
        server: String,
        port: Int,
        name: String,
        providerTag: String
    ): ParsedConfig? {
        val uuid = proxy.string("uuid")?.takeIf { it.isNotBlank() } ?: return null
        val network = streamNetwork(proxy.string("network")) ?: return null
        val reality = proxy.map("reality-opts")
        val stream = streamOptions(proxy, network)
        val security = when {
            reality != null -> "reality"
            tlsEnabled(proxy) -> "tls"
            else -> "none"
        }
        return ParsedConfig(
            uuid = uuid,
            address = server,
            port = port,
            transport = network,
            security = security,
            sni = proxy.string("servername") ?: proxy.string("sni"),
            path = stream.path,
            host = stream.host,
            mode = stream.mode,
            extra = stream.extra,
            fingerprint = proxy.string("client-fingerprint"),
            publicKey = reality?.string("public-key"),
            shortId = reality?.string("short-id"),
            flow = proxy.string("flow"),
            name = name,
            providerTag = providerTag,
            insecure = proxy.boolean("skip-cert-verify") == true,
            alpn = proxy.stringList("alpn")
        )
    }

    private fun vmess(
        proxy: Map<String, Any>,
        server: String,
        port: Int,
        name: String,
        providerTag: String
    ): ParsedConfig? {
        val uuid = proxy.string("uuid")?.takeIf { it.isNotBlank() } ?: return null
        val network = streamNetwork(proxy.string("network")) ?: "tcp"
        val stream = streamOptions(proxy, network)
        return ParsedConfig(
            uuid = uuid,
            address = server,
            port = port,
            transport = "vmess",
            security = if (proxy.boolean("tls") == true) "tls" else "none",
            sni = proxy.string("servername") ?: proxy.string("sni"),
            path = stream.path,
            host = stream.host,
            // For VMess the builder reads the stream network from [ParsedConfig.mode].
            mode = network,
            fingerprint = proxy.string("client-fingerprint"),
            publicKey = null,
            shortId = null,
            flow = null,
            name = name,
            providerTag = providerTag,
            insecure = proxy.boolean("skip-cert-verify") == true,
            alpn = proxy.stringList("alpn")
        )
    }

    private fun trojan(
        proxy: Map<String, Any>,
        server: String,
        port: Int,
        name: String,
        providerTag: String
    ): ParsedConfig? {
        val password = proxy.string("password")?.takeIf { it.isNotBlank() } ?: return null
        val network = streamNetwork(proxy.string("network")) ?: "tcp"
        val stream = streamOptions(proxy, network)
        return ParsedConfig(
            uuid = password,
            address = server,
            port = port,
            transport = "trojan",
            security = "tls",
            sni = proxy.string("sni") ?: proxy.string("servername"),
            path = stream.path,
            host = stream.host,
            mode = network,
            fingerprint = proxy.string("client-fingerprint"),
            publicKey = null,
            shortId = null,
            flow = null,
            name = name,
            providerTag = providerTag,
            password = password,
            insecure = proxy.boolean("skip-cert-verify") == true,
            alpn = proxy.stringList("alpn")
        )
    }

    private fun shadowsocks(
        proxy: Map<String, Any>,
        server: String,
        port: Int,
        name: String,
        providerTag: String
    ): ParsedConfig? {
        val cipher = proxy.string("cipher")?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val password = proxy.string("password")?.takeIf { it.isNotBlank() } ?: return null
        return ParsedConfig(
            uuid = password,
            address = server,
            port = port,
            transport = "shadowsocks",
            security = cipher,
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

    private fun socks(
        proxy: Map<String, Any>,
        server: String,
        port: Int,
        name: String,
        providerTag: String
    ): ParsedConfig {
        return ParsedConfig(
            uuid = proxy.string("username").orEmpty(),
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
            password = proxy.string("password")
        )
    }

    private fun hysteria2(
        proxy: Map<String, Any>,
        server: String,
        port: Int,
        name: String,
        providerTag: String
    ): ParsedConfig? {
        val password = proxy.string("password")?.takeIf { it.isNotBlank() }
            ?: proxy.string("auth")?.takeIf { it.isNotBlank() }
            ?: return null
        return ParsedConfig(
            uuid = password,
            address = server,
            port = port,
            transport = "hysteria2",
            security = "tls",
            sni = proxy.string("sni") ?: proxy.string("servername"),
            path = null,
            host = null,
            mode = null,
            fingerprint = null,
            publicKey = null,
            shortId = null,
            flow = null,
            name = name,
            providerTag = providerTag,
            password = password,
            insecure = proxy.boolean("skip-cert-verify") == true,
            alpn = proxy.stringList("alpn"),
            obfsType = proxy.string("obfs"),
            obfsPassword = proxy.string("obfs-password"),
            pinSHA256 = proxy.string("fingerprint") ?: proxy.string("pinSHA256")
        )
    }

    private fun tuic(
        proxy: Map<String, Any>,
        server: String,
        port: Int,
        name: String,
        providerTag: String
    ): ParsedConfig? {
        val uuid = proxy.string("uuid")?.takeIf { it.isNotBlank() }
            ?: proxy.string("token")?.takeIf { it.isNotBlank() }
            ?: return null
        return ParsedConfig(
            uuid = uuid,
            address = server,
            port = port,
            transport = "tuic",
            security = "tls",
            sni = proxy.string("sni") ?: proxy.string("servername"),
            path = null,
            host = null,
            mode = null,
            fingerprint = null,
            publicKey = null,
            shortId = null,
            flow = null,
            name = name,
            providerTag = providerTag,
            password = proxy.string("password"),
            insecure = proxy.boolean("skip-cert-verify") == true,
            alpn = proxy.stringList("alpn"),
            congestionControl = proxy.string("congestion-controller")?.takeIf { it.isNotBlank() } ?: "bbr"
        )
    }

    private data class StreamOptions(
        val path: String? = null,
        val host: String? = null,
        val mode: String? = null,
        val extra: String? = null
    )

    /** `tls: true` explicitly, or the HTTP/2 transport which is TLS-only in Clash. */
    private fun tlsEnabled(proxy: Map<String, Any>): Boolean =
        proxy.boolean("tls") == true || proxy.string("network")?.lowercase() == "h2"

    /**
     * Normalize a Clash stream network to the sing-box transport name the outbound
     * builder accepts. Returns null for a network the bundled core cannot express, so
     * the profile is rejected at import time instead of failing at connect time.
     */
    private fun streamNetwork(raw: String?): String? {
        return when (raw?.trim()?.lowercase()) {
            null, "", "tcp", "raw", "none" -> "tcp"
            "ws", "websocket" -> "ws"
            "grpc", "gun" -> "grpc"
            "h2", "http" -> "http"
            "httpupgrade" -> "httpupgrade"
            "quic" -> "quic"
            "xhttp", "splithttp" -> "xhttp"
            else -> null
        }
    }

    private fun streamOptions(proxy: Map<String, Any>, network: String): StreamOptions = when (network) {
        "grpc" -> {
            val grpc = proxy.map("grpc-opts")
            StreamOptions(path = grpc?.string("grpc-service-name") ?: grpc?.string("service-name"))
        }
        "http" -> {
            val h2 = proxy.map("h2-opts")
            val http = proxy.map("http-opts")
            val headers = http?.map("headers")
            StreamOptions(
                path = h2?.string("path") ?: http?.stringList("path")?.firstOrNull(),
                host = h2?.stringList("host")?.firstOrNull()
                    ?: headers?.stringList("Host")?.firstOrNull()
                    ?: headers?.stringList("host")?.firstOrNull()
            )
        }
        "xhttp" -> {
            val xhttp = proxy.map("xhttp-opts")
            val headers = xhttp?.map("headers")
            StreamOptions(
                path = xhttp?.string("path"),
                host = xhttp?.string("host")
                    ?: headers?.string("Host")
                    ?: headers?.string("host"),
                mode = xhttp?.string("mode"),
                // Preserve the full Clash/Mihomo XHTTP options object; the builder
                // applies its own allow-list before emitting sing-box fields.
                extra = xhttp?.let { JSONObject(it).toString() }
            )
        }
        "ws" -> {
            val ws = proxy.map("ws-opts")
            val headers = ws?.map("headers")
            StreamOptions(
                path = ws?.string("path"),
                host = headers?.string("Host") ?: headers?.string("host")
            )
        }
        "httpupgrade" -> {
            val opts = proxy.map("httpupgrade-opts") ?: proxy.map("ws-opts")
            val headers = opts?.map("headers")
            StreamOptions(
                path = opts?.string("path"),
                host = opts?.string("host") ?: headers?.string("Host") ?: headers?.string("host")
            )
        }
        else -> StreamOptions()
    }

    private fun Map<String, Any>.string(key: String): String? =
        (this[key] as? String)?.takeIf { it.isNotBlank() }

    private fun Map<String, Any>.boolean(key: String): Boolean? = when (val value = this[key]) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        else -> null
    }

    private fun Map<String, Any>.map(key: String): Map<String, Any>? = this[key] as? Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.stringList(key: String): List<String> = when (val value = this[key]) {
        is List<*> -> value.mapNotNull { (it as? String)?.takeIf(String::isNotBlank) }
        is String -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }
}
