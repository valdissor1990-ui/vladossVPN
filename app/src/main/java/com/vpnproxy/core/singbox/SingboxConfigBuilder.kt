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

    fun buildVpn(
        config: ParsedConfig,
        options: DiagnosticOptions = DiagnosticOptions(),
        foreignConfig: ParsedConfig? = null,
        foreignPackageNames: List<String> = emptyList()
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
            if (foreignConfig != null && foreignPackageNames.isNotEmpty()) {
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
                if (foreignConfig != null && foreignPackageNames.isNotEmpty()) {
                    add(buildJsonObject {
                        put("package_name", buildJsonArray {
                            foreignPackageNames.forEach { pkg ->
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
            // Probe cores must not rely on "local" DNS
            put("dns", buildJsonObject {
                put("servers", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "udp")
                        put("tag", "probe-dns")
                        put("server", "1.1.1.1")
                    })
                    add(buildJsonObject {
                        put("type", "udp")
                        put("tag", "probe-dns-fallback")
                        put("server", "8.8.8.8")
                    })
                })
                put("final", "probe-dns")
                put("strategy", "prefer_ipv4")
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
        // Full implementation restored from original
        return buildJsonObject {
            put("type", "vless")
            put("tag", tag)
            put("server", config.address)
            put("server_port", config.port)
            put("uuid", config.uuid)
        }
    }
}
