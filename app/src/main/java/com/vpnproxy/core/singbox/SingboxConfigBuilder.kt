package com.vpnproxy.core.singbox

import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

    fun buildOutbound(config: ParsedConfig, tag: String = "proxy"): JsonObject {
        val isHy2 = config.transport.equals("hysteria2", true) || config.transport.equals("hy2", true)
        val isTuic = config.transport.equals("tuic", true)
        val isVless = config.transport.equals("vless", true) || (!isHy2 && !isTuic)
        require(isVless || isHy2 || isTuic) { "Unsupported Sing-box outbound: ${config.transport}" }

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

            if (isVless && config.transport.equals("ws", true)) {
                put("transport", buildJsonObject {
                    put("type", "ws")
                    put("path", config.path?.takeIf { it.isNotBlank() } ?: "/")
                    config.host?.takeIf { it.isNotBlank() }?.let { host ->
                        put("headers", buildJsonObject { put("Host", host) })
                    }
                })
            } else if (isVless && config.transport.equals("grpc", true)) {
                put("transport", buildJsonObject {
                    put("type", "grpc")
                    put("service_name", config.path.orEmpty())
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
