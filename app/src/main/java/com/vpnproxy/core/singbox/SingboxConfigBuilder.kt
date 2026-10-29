package com.vpnproxy.core.singbox

import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import com.vpnproxy.logging.LogCollectorHolder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builds Sing-box outbound configs shared by VPN mode and isolated health probes. */
object SingboxConfigBuilder {
    const val PROXY_PORT = 10809

    private fun normalizePath(path: String?): String {
        val value = path?.trim().orEmpty()
        if (value.isEmpty()) return "/"
        return if (value.startsWith("/")) value else "/$value"
    }

    fun build(config: ParsedConfig): String = buildJsonObject {
        put("log", buildJsonObject {
            put("level", "debug")
            put("timestamp", true)
        })
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

    /**
     * @param foreignConfig optional second outbound for AI / selected apps (non-RU exit)
     * @param foreignProcessNames Android package names routed to foreign outbound
     */
    fun buildVpn(
        config: ParsedConfig,
        options: DiagnosticOptions = DiagnosticOptions(),
        foreignConfig: ParsedConfig? = null,
        foreignProcessNames: List<String> = emptyList()
    ): String = buildJsonObject {
        put("log", buildJsonObject {
            put("level", "debug")
            put("timestamp", true)
        })
        put("dns", buildJsonObject {
            put("servers", buildJsonArray {
                when (options.dnsMode) {
                    "proxy_dot_8888" -> add(buildJsonObject {
                        put("type", "tls")
                        put("tag", "proxy-dot")
                        put("server", "8.8.8.8")
                        put("server_port", 853)
                        put("detour", "proxy")
                    })
                    "direct_udp_8888" -> add(buildJsonObject {
                        put("type", "udp"); put("tag", "direct-dns"); put("server", "8.8.8.8")
                    })
                    "direct_udp_1111" -> add(buildJsonObject {
                        put("type", "udp"); put("tag", "direct-dns"); put("server", "1.1.1.1"); put("server_port", 1111)
                    })
                    "direct_dot_1111" -> add(buildJsonObject {
                        put("type", "tls"); put("tag", "direct-dns"); put("server", "1.1.1.1"); put("server_port", 853)
                    })
                    "proxy_dot_1111" -> add(buildJsonObject {
                        put("type", "tls"); put("tag", "proxy-dot"); put("server", "1.1.1.1"); put("server_port", 853); put("detour", "proxy")
                    })
                    "system_no_hijack" -> add(buildJsonObject {
                        put("type", "local"); put("tag", "direct-dns")
                    })
                    else -> error("Unsupported diagnostic DNS mode: ${options.dnsMode}")
                }
            })
            put("final", if (options.dnsMode == "proxy_dot_8888" || options.dnsMode == "proxy_dot_1111") "proxy-dot" else "direct-dns")
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
                put("strict_route", options.strictRoute)
                put("stack", options.tunStack)
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
            if (foreignConfig != null && foreignProcessNames.isNotEmpty()) {
                add(buildOutbound(foreignConfig, "proxy-foreign"))
            }
            add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
            add(buildJsonObject { put("type", "block"); put("tag", "block") })
        })
        put("route", buildJsonObject {
            put("auto_detect_interface", options.autoDetectInterface)
            put("rules", buildJsonArray {
                add(buildJsonObject { put("action", "sniff") })
                if (options.dnsMode != "system_no_hijack") add(buildJsonObject {
                    put("protocol", "dns")
                    put("action", "hijack-dns")
                })
                if (foreignConfig != null && foreignProcessNames.isNotEmpty()) {
                    add(buildJsonObject {
                        put("process_name", buildJsonArray {
                            foreignProcessNames.forEach { pkg ->
                                add(kotlinx.serialization.json.JsonPrimitive(pkg))
                            }
                        })
                        put("outbound", "proxy-foreign")
                    })
                }
                add(buildJsonObject {
                    put("inbound", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("local-proxy-in")) })
                    put("outbound", "proxy")
                })
            })
            put("final", options.routeFinal)
        })
    }.toString()

    fun buildMulti(configs: List<ParsedConfig>, ports: List<Int>): String {
        require(configs.isNotEmpty()) { "At least one Sing-box config is required" }
        require(configs.size == ports.size) { "Every Sing-box config must have a distinct inbound port" }
        return buildJsonObject {
            put("log", buildJsonObject {
                put("level", "debug")
                put("timestamp", true)
            })
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

    private fun parseXhttpExtra(rawExtra: String?): JsonObject? {
        if (rawExtra.isNullOrBlank()) return null
        val element = try {
            Json.parseToJsonElement(rawExtra)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid XHTTP extra JSON: ${e.message}", e)
        }
        val obj = element as? JsonObject ?: throw IllegalArgumentException("XHTTP extra must be a JSON object")
        return obj
    }

    fun buildOutbound(config: ParsedConfig, tag: String = "proxy"): JsonObject {
        val transportKey = config.transport.lowercase()
        val isHy2 = transportKey == "hysteria2" || transportKey == "hy2"
        val isTuic = transportKey == "tuic"
        val isShadowsocks = transportKey == "shadowsocks" || transportKey == "ss"
        val isSocks = transportKey == "socks" || transportKey == "socks5"
        val isTrojan = transportKey == "trojan"
        val isVmess = transportKey == "vmess"
        val isVless = !isHy2 && !isTuic && !isShadowsocks && !isSocks && !isTrojan && !isVmess
        require(isVless || isHy2 || isTuic || isShadowsocks || isSocks || isTrojan || isVmess) {
            "Unsupported Sing-box outbound: ${config.transport}"
        }
        if (isVless) {
            require(transportKey in setOf("", "tcp", "raw", "none", "vless", "ws", "grpc", "http", "httpupgrade", "quic", "xhttp")) {
                "Unsupported VLESS transport for sing-box-lx 1.14.3: ${config.transport}"
            }
        }

        return buildJsonObject {
            put(
                "type",
                when {
                    isHy2 -> "hysteria2"
                    isTuic -> "tuic"
                    isShadowsocks -> "shadowsocks"
                    isSocks -> "socks"
                    isTrojan -> "trojan"
                    isVmess -> "vmess"
                    else -> "vless"
                }
            )
            put("tag", tag)
            put("server", config.address)
            put("server_port", config.port)

            when {
                isHy2 -> {
                    put("password", config.password ?: config.uuid)
                    config.obfsType?.takeIf { it.isNotBlank() }?.let { obfsType ->
                        put("obfs", buildJsonObject {
                            put("type", obfsType)
                            config.obfsPassword?.takeIf { it.isNotBlank() }?.let { put("password", it) }
                        })
                    }
                }
                isTuic -> {
                    put("uuid", config.uuid)
                    put("password", config.password.orEmpty())
                    put("congestion_control", config.congestionControl.ifBlank { "bbr" })
                    put("udp_relay_mode", "native")
                }
                isShadowsocks -> {
                    put("method", config.security.ifBlank { "aes-256-gcm" })
                    put("password", config.password ?: config.uuid)
                }
                isSocks -> {
                    put("version", "5")
                    config.uuid.takeIf { it.isNotBlank() }?.let { put("username", it) }
                    config.password?.takeIf { it.isNotBlank() }?.let { put("password", it) }
                }
                isTrojan -> put("password", config.password ?: config.uuid)
                isVmess -> {
                    put("uuid", config.uuid)
                    put("security", "auto")
                    put("alter_id", 0)
                }
                else -> {
                    put("uuid", config.uuid)
                    config.flow?.takeIf { it.isNotBlank() }?.let { put("flow", it) }
                }
            }

            val streamNetwork = when {
                isVmess || isTrojan -> config.mode?.lowercase()?.takeIf { it.isNotBlank() }
                isVless -> transportKey
                else -> null
            }
            if (isVless || ((isVmess || isTrojan) && streamNetwork != null && streamNetwork !in setOf("tcp", "raw", "none", ""))) {
                when (streamNetwork ?: transportKey) {
                    "ws" -> put("transport", buildJsonObject {
                        put("type", "ws")
                        put("path", normalizePath(config.path))
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
                        config.path?.takeIf { it.isNotBlank() }?.let { put("path", normalizePath(it)) }
                        config.host?.takeIf { it.isNotBlank() }?.let { host ->
                            put("host", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(host)) })
                        }
                    })
                    "httpupgrade" -> put("transport", buildJsonObject {
                        put("type", "httpupgrade")
                        put("path", normalizePath(config.path))
                        config.host?.takeIf { it.isNotBlank() }?.let { put("host", it) }
                    })
                    "quic" -> put("transport", buildJsonObject { put("type", "quic") })
                    "xhttp" -> put("transport", buildJsonObject {
                        val extraObject = config.extra?.takeIf { it.isNotBlank() }?.let { parseXhttpExtra(it) }
                        val allowedKeys = setOf(
                            "host", "path", "mode", "headers",
                            "x_padding_bytes", "x_padding_obfs_mode", "x_padding_key",
                            "x_padding_header", "x_padding_placement", "x_padding_method",
                            "no_grpc_header", "no_sse_header",
                            "uplink_http_method",
                            "session_placement", "session_key"
                        )
                        val includedKeys = mutableListOf<String>()
                        val omittedKeys = mutableListOf<String>()
                        extraObject?.forEach { (key, value) ->
                            if (key == "type") { omittedKeys += key; return@forEach }
                            if (value is JsonNull) { omittedKeys += key; return@forEach }
                            val singBoxKey = when (key) {
                                "sessionIDPlacement", "sessionIdPlacement", "sessionPlacement",
                                "session_id_placement" -> "session_placement"
                                "sessionIDKey", "sessionIdKey", "sessionKey",
                                "session_id_key" -> "session_key"
                                "noGRPCHeader", "noGrpcHeader" -> "no_grpc_header"
                                "noSSEHeader", "noSseHeader" -> "no_sse_header"
                                "uplinkHTTPMethod" -> "uplink_http_method"
                                "xPaddingBytes" -> "x_padding_bytes"
                                "xPaddingObfsMode" -> "x_padding_obfs_mode"
                                "xPaddingKey" -> "x_padding_key"
                                "xPaddingHeader" -> "x_padding_header"
                                "xPaddingPlacement" -> "x_padding_placement"
                                "xPaddingMethod" -> "x_padding_method"
                                else -> key.replace(Regex("([a-z0-9])([A-Z])")) { match ->
                                    "${match.groupValues[1]}_${match.groupValues[2]}"
                                }.lowercase()
                            }
                            if (value is JsonPrimitive && value.isString && value.content.isBlank()) { omittedKeys += key; return@forEach }
                            if (value is JsonPrimitive && value.booleanOrNull == false) { omittedKeys += key; return@forEach }
                            if (value is JsonPrimitive && value.intOrNull == 0) { omittedKeys += key; return@forEach }
                            if (singBoxKey in allowedKeys) {
                                if (singBoxKey == "path" && value is JsonPrimitive && value.isString) {
                                    put(singBoxKey, JsonPrimitive(normalizePath(value.content)))
                                } else {
                                    put(singBoxKey, value)
                                }
                                includedKeys += singBoxKey
                            } else omittedKeys += key
                        }
                        LogCollectorHolder.get()?.info("SingboxConfigBuilder", "XHTTP included keys=${includedKeys.distinct().sorted()}")
                        LogCollectorHolder.get()?.info("SingboxConfigBuilder", "XHTTP omitted keys=${omittedKeys.distinct().sorted()}")
                        put("type", "xhttp")
                        config.host?.takeIf { it.isNotBlank() }?.let { put("host", it) }
                        config.path?.takeIf { it.isNotBlank() }?.let { put("path", normalizePath(it)) }
                        put("mode", config.mode?.takeIf { it.isNotBlank() } ?: "auto")
                    })
                }
            }

            val needsTls = isHy2 || isTuic || isTrojan ||
                config.security.equals("tls", true) ||
                config.security.equals("reality", true)
            if (needsTls) {
                put("tls", buildJsonObject {
                    put("enabled", true)
                    val sni = config.sni?.takeIf { it.isNotBlank() }
                    val host = config.host?.takeIf { it.isNotBlank() && Regex("^[A-Za-z0-9.-]+$").matches(it) && it.contains(".") }
                    val serverName = sni ?: host ?: config.address
                    val source = when { sni != null -> "sni"; host != null -> "host"; else -> "address" }
                    LogCollectorHolder.get()?.info("SingboxConfigBuilder", "TLS server_name=$serverName source=$source")
                    put("server_name", serverName)
                    when {
                        isVless -> {
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
                        }
                        isVmess -> {
                            put("utls", buildJsonObject {
                                put("enabled", true)
                                put("fingerprint", config.fingerprint?.takeIf { it.isNotBlank() } ?: "chrome")
                            })
                            put("insecure", config.insecure)
                        }
                        else -> {
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
                    }
                })
            }
        }
    }
}
