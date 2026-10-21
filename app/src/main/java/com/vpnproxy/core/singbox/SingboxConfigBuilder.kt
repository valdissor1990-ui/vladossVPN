package com.vpnproxy.core.singbox

import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builds Sing-box outbound configs shared by VPN mode and isolated health probes. */
object SingboxConfigBuilder {
    const val PROXY_PORT = 10809

    fun build(config: ParsedConfig): String = buildJsonObject {
        put("log", buildJsonObject { put("level", "warn") })
        put("inbounds", buildJsonArray {
            add(buildJsonObject {
                put("type", "mixed")
                put("tag", "mixed-in")
                put("listen", "127.0.0.1")
                put("listen_port", PROXY_PORT)
            })
        })
        put("outbounds", buildJsonArray {
            add(buildOutbound(config))
            add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
        })
    }.toString()

    /** Builds a multi-server proxy config for DeepProbe; each inbound is pinned to one outbound. */
    fun buildVpn(config: ParsedConfig): String = buildJsonObject {
        put("log", buildJsonObject { put("level", "warn") })
        put("dns", buildJsonObject {
            put("servers", buildJsonArray {
                add(buildJsonObject {
                    put("type", "tls")
                    put("tag", "remote-dns")
                    put("server", "1.1.1.1")
                    put("server_port", 853)
                    put("detour", "proxy")
                })
            })
            put("final", "remote-dns")
            put("strategy", "prefer_ipv4")
        })
        put("inbounds", buildJsonArray {
            add(buildJsonObject {
                put("type", "tun")
                put("tag", "tun-in")
                put("interface_name", "vpnproxy0")
                put("address", buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("172.19.0.1/30"))
                    add(kotlinx.serialization.json.JsonPrimitive("fdfe:dcba:9876::1/126"))
                })
                put("mtu", 1400)
                put("auto_route", true)
                put("strict_route", true)
                put("stack", "system")
            })
            add(buildJsonObject {
                put("type", "mixed")
                put("tag", "local-proxy-in")
                put("listen", "127.0.0.1")
                put("listen_port", PROXY_PORT)
            })
        })
        put("outbounds", buildJsonArray {
            add(buildOutbound(config, "proxy"))
            add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
            add(buildJsonObject { put("type", "block"); put("tag", "block") })
        })
        put("route", buildJsonObject {
            put("auto_detect_interface", true)
            put("rules", buildJsonArray {
                add(buildJsonObject { put("action", "sniff") })
                add(buildJsonObject {
                    put("protocol", "dns")
                    put("action", "hijack-dns")
                })
                add(buildJsonObject {
                    put("inbound", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("local-proxy-in")) })
                    put("outbound", "proxy")
                })
            })
            put("final", "proxy")
        })
    }.toString()

    fun buildMulti(configs: List<ParsedConfig>, ports: List<Int>): String {
        require(configs.isNotEmpty()) { "At least one Sing-box config is required" }
        require(configs.size == ports.size) { "Every Sing-box config must have a distinct inbound port" }
        return buildJsonObject {
            put("log", buildJsonObject { put("level", "warn") })
            put("inbounds", buildJsonArray {
                configs.forEachIndexed { index, _ ->
                    add(buildJsonObject {
                        put("type", "mixed")
                        put("tag", "mixed-in-$index")
                        put("listen", "127.0.0.1")
                        put("listen_port", ports[index])
                    })
                }
            })
            put("outbounds", buildJsonArray {
                configs.forEachIndexed { index, config -> add(buildOutbound(config, "proxy-$index")) }
                add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
            })
            put("route", buildJsonObject {
                put("rules", buildJsonArray {
                    configs.forEachIndexed { index, _ ->
                        add(buildJsonObject {
                            put("inbound", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("mixed-in-$index")) })
                            put("outbound", "proxy-$index")
                        })
                    }
                })
            })
        }.toString()
    }

    private val xhttpOptionAliases = mapOf(
        "headers" to "headers",
        "xPaddingBytes" to "x_padding_bytes", "x_padding_bytes" to "x_padding_bytes",
        "noGRPCHeader" to "no_grpc_header", "no_grpc_header" to "no_grpc_header",
        "sessionIDPlacement" to "session_placement", "sessionPlacement" to "session_placement",
        "session_placement" to "session_placement",
        "sessionIDKey" to "session_key", "sessionKey" to "session_key", "session_key" to "session_key",
        "seqPlacement" to "seq_placement", "seq_placement" to "seq_placement",
        "seqKey" to "seq_key", "seq_key" to "seq_key",
        "uplinkDataPlacement" to "uplink_data_placement", "uplink_data_placement" to "uplink_data_placement",
        "uplinkDataKey" to "uplink_data_key", "uplink_data_key" to "uplink_data_key",
        "uplinkChunkSize" to "uplink_chunk_size", "uplink_chunk_size" to "uplink_chunk_size",
        "uplinkHTTPMethod" to "uplink_http_method", "uplink_http_method" to "uplink_http_method",
        "xPaddingObfsMode" to "x_padding_obfs_mode", "x_padding_obfs_mode" to "x_padding_obfs_mode",
        "xPaddingPlacement" to "x_padding_placement", "x_padding_placement" to "x_padding_placement",
        "xPaddingKey" to "x_padding_key", "x_padding_key" to "x_padding_key",
        "xPaddingHeader" to "x_padding_header", "x_padding_header" to "x_padding_header",
        "xPaddingMethod" to "x_padding_method", "x_padding_method" to "x_padding_method",
        "scMaxEachPostBytes" to "sc_max_each_post_bytes", "sc_max_each_post_bytes" to "sc_max_each_post_bytes",
        "scMinPostsIntervalMs" to "sc_min_posts_interval_ms", "sc_min_posts_interval_ms" to "sc_min_posts_interval_ms"
    )

    private val xhttpStringOptions = setOf(
        "x_padding_bytes", "session_placement", "session_key", "seq_placement", "seq_key",
        "uplink_data_placement", "uplink_data_key", "uplink_chunk_size", "uplink_http_method",
        "x_padding_placement", "x_padding_key", "x_padding_header", "x_padding_method",
        "sc_max_each_post_bytes", "sc_min_posts_interval_ms"
    )

    /**
     * Subscription links commonly encode Xray-style camelCase names and server-only fields.
     * sing-box-lx expects snake_case client options; unknown/server-only fields must never be
     * copied into transport because libbox decodes this object strictly.
     */
    private fun parseXhttpExtra(rawExtra: String?): JsonObject? {
        if (rawExtra.isNullOrBlank()) return null
        val element = try {
            Json.parseToJsonElement(rawExtra)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid XHTTP extra JSON: ${e.message}", e)
        }
        val obj = element as? JsonObject ?: throw IllegalArgumentException("XHTTP extra must be a JSON object")
        return kotlinx.serialization.json.buildJsonObject {
            obj.forEach { (sourceKey, value) ->
                val targetKey = xhttpOptionAliases[sourceKey] ?: return@forEach
                when (targetKey) {
                    "headers" -> if (value is JsonObject) put(targetKey, value)
                    "no_grpc_header", "x_padding_obfs_mode" -> {
                        val booleanValue = (value as? JsonPrimitive)?.booleanOrNull ?: return@forEach
                        put(targetKey, booleanValue)
                    }
                    else -> {
                        if (targetKey !in xhttpStringOptions) return@forEach
                        val rawValue = (value as? JsonPrimitive)?.contentOrNull ?: return@forEach
                        val normalized = normalizeXhttpStringOption(targetKey, rawValue) ?: return@forEach
                        put(targetKey, normalized)
                    }
                }
            }
        }
    }

    private fun normalizeXhttpStringOption(key: String, raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        return when (key) {
            "session_placement", "seq_placement" ->
                value.lowercase().takeIf { it in setOf("path", "query", "header", "cookie") }
            "uplink_data_placement" ->
                value.lowercase().takeIf { it in setOf("body", "auto", "header", "cookie") }
            "x_padding_placement" ->
                value.takeIf { it in setOf("cookie", "header", "query", "queryInHeader") }
            "x_padding_method" ->
                value.lowercase().takeIf { it in setOf("repeat-x", "tokenish") }
            "uplink_http_method" ->
                value.uppercase().takeIf { it in setOf("GET", "POST") }
            "x_padding_bytes", "uplink_chunk_size", "sc_max_each_post_bytes", "sc_min_posts_interval_ms" -> {
                if (!value.matches(Regex("\\d+(?:-\\d+)?"))) return null
                if (key != "x_padding_bytes" && value == "0") return null
                value
            }
            else -> value
        }
    }

    fun buildOutbound(config: ParsedConfig, tag: String = "proxy"): JsonObject {
        val isHy2 = config.transport.equals("hysteria2", true) || config.transport.equals("hy2", true)
        val isTuic = config.transport.equals("tuic", true)
        val isVless = config.transport.equals("vless", true) || (!isHy2 && !isTuic)
        require(isVless || isHy2 || isTuic) { "Unsupported Sing-box outbound: ${config.transport}" }
        if (isVless) {
            require(config.transport.lowercase() in setOf("", "tcp", "raw", "none", "vless", "ws", "grpc", "http", "httpupgrade", "quic", "xhttp")) {
                "Unsupported VLESS transport for sing-box-lx 1.14.3: ${config.transport}"
            }
        }

        return buildJsonObject {
            put("type", when { isHy2 -> "hysteria2"; isTuic -> "tuic"; else -> "vless" })
            put("tag", tag)
            put("server", config.address)
            put("server_port", config.port)

            when {
                isHy2 -> put("password", config.password ?: config.uuid)
                isTuic -> {
                    put("uuid", config.uuid)
                    put("password", config.password.orEmpty())
                    put("congestion_control", config.congestionControl.ifBlank { "bbr" })
                    put("udp_relay_mode", "native")
                }
                else -> {
                    put("uuid", config.uuid)
                    config.flow?.takeIf { it.isNotBlank() }?.let { put("flow", it) }
                }
            }

            if (isVless) when (config.transport.lowercase()) {
                "ws" -> put("transport", buildJsonObject {
                    put("type", "ws")
                    put("path", config.path?.takeIf { it.isNotBlank() } ?: "/")
                    config.host?.takeIf { it.isNotBlank() }?.let { host ->
                        put("headers", buildJsonObject { put("Host", host) })
                    }
                })
                "grpc" -> put("transport", buildJsonObject {
                    put("type", "grpc")
                    put("service_name", config.path.orEmpty())
                })
                "http" -> put("transport", buildJsonObject {
                    put("type", "http")
                    config.path?.takeIf { it.isNotBlank() }?.let { put("path", it) }
                    config.host?.takeIf { it.isNotBlank() }?.let { host ->
                        put("host", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(host)) })
                    }
                })
                "httpupgrade" -> put("transport", buildJsonObject {
                    put("type", "httpupgrade")
                    put("path", config.path?.takeIf { it.isNotBlank() } ?: "/")
                    config.host?.takeIf { it.isNotBlank() }?.let { put("host", it) }
                })
                "quic" -> put("transport", buildJsonObject { put("type", "quic") })
                "xhttp" -> put("transport", buildJsonObject {
                    val extraObject = config.extra?.takeIf { it.isNotBlank() }?.let { parseXhttpExtra(it) }
                    extraObject?.forEach { (key, value) ->
                        if (key !in setOf("headers", "host", "path", "mode", "type")) put(key, value)
                    }

                    // Top-level URI parameters are authoritative; extra.host/path/mode are fallbacks.
                    val extraHost = extraObject?.get("host") as? JsonPrimitive
                    val extraPath = extraObject?.get("path") as? JsonPrimitive
                    val extraMode = (extraObject?.get("mode") as? JsonPrimitive)?.contentOrNull
                    val selectedMode = config.mode?.takeIf { it in setOf("auto", "packet-up", "stream-up", "stream-one") }
                        ?: extraMode?.takeIf { it in setOf("auto", "packet-up", "stream-up", "stream-one") }
                        ?: "auto"
                    val effectiveMode = if (selectedMode == "auto") {
                        if (config.security.equals("reality", true)) "stream-one" else "packet-up"
                    } else selectedMode

                    put("type", "xhttp")
                    (config.host?.takeIf { it.isNotBlank() } ?: extraHost?.contentOrNull?.takeIf { it.isNotBlank() })
                        ?.let { put("host", it) }
                    (config.path?.takeIf { it.isNotBlank() } ?: extraPath?.contentOrNull?.takeIf { it.isNotBlank() })
                        ?.let { put("path", it) }
                    put("mode", selectedMode)

                    // Some Xray links contain options only meaningful for packet-up. Drop
                    // incompatible combinations instead of letting strict libbox config decode fail.
                    val packetUp = effectiveMode == "packet-up"
                    if (!packetUp) {
                        // These values are removed below if present; defaults remain core-owned.
                    }
                    extraObject?.forEach { (key, value) ->
                        val normalizedKey = key
                        if (normalizedKey == "uplink_data_placement" &&
                            (value as? JsonPrimitive)?.contentOrNull in setOf("header", "cookie") && !packetUp
                        ) return@forEach
                        if (normalizedKey == "uplink_http_method" &&
                            (value as? JsonPrimitive)?.contentOrNull == "GET" && !packetUp
                        ) return@forEach
                        if (normalizedKey in setOf("headers", "x_padding_bytes", "no_grpc_header", "session_placement",
                                "session_key", "seq_placement", "seq_key", "uplink_data_placement", "uplink_data_key",
                                "uplink_chunk_size", "uplink_http_method", "x_padding_obfs_mode", "x_padding_placement",
                                "x_padding_key", "x_padding_header", "x_padding_method", "sc_max_each_post_bytes",
                                "sc_min_posts_interval_ms")
                        ) put(normalizedKey, value)
                    }
                })
            }

            if (isHy2 || isTuic || config.security.equals("tls", true) || config.security.equals("reality", true)) {
                put("tls", buildJsonObject {
                    put("enabled", true)
                    put("server_name", config.sni?.takeIf { it.isNotBlank() } ?: config.address)
                    if (isVless) {
                        put("utls", buildJsonObject {
                            put("enabled", true)
                            put("fingerprint", config.fingerprint?.takeIf { it.isNotBlank() } ?: "chrome")
                        })
                        if (config.security.equals("reality", true)) {
                            require(!config.publicKey.isNullOrBlank()) { "VLESS Reality public key (pbk) is missing" }
                            put("reality", buildJsonObject {
                                put("enabled", true)
                                put("public_key", config.publicKey)
                                put("short_id", config.shortId.orEmpty())
                            })
                        }
                    } else {
                        put("insecure", config.insecure)
                        if (config.alpn.isNotEmpty()) {
                            put("alpn", JsonArray(config.alpn.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                        }
                        config.pinSHA256?.takeIf { it.isNotBlank() }?.let { pin ->
                            put("certificate_public_key_sha256", buildJsonArray {
                                add(kotlinx.serialization.json.JsonPrimitive(pin))
                            })
                        }
                    }
                })
            }

            if (isHy2 && !config.obfsType.isNullOrBlank()) {
                put("obfs", buildJsonObject {
                    put("type", config.obfsType!!)
                    config.obfsPassword?.let { put("password", it) }
                })
            }
        }
    }
}
