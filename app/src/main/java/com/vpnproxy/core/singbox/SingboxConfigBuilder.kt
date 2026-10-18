package com.vpnproxy.core.singbox

import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builds minimal sing-box proxy-mode configurations. This process never owns a TUN. */
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

    fun buildOutbound(config: ParsedConfig): JsonObject {
        require(config.requiresSingBox()) { "Sing-box outbound requires Hysteria2 or TUIC" }
        return buildJsonObject {
            val isHy2 = config.transport.equals("hysteria2", true) ||
                config.transport.equals("hy2", true)
            put("type", if (isHy2) "hysteria2" else "tuic")
            put("tag", "proxy")
            put("server", config.address)
            put("server_port", config.port)
            if (isHy2) {
                put("password", config.password ?: config.uuid)
            } else {
                put("uuid", config.uuid)
                put("password", config.password.orEmpty())
                put("congestion_control", config.congestionControl.ifBlank { "bbr" })
                put("udp_relay_mode", "native")
            }
            put("tls", buildJsonObject {
                put("enabled", true)
                put("server_name", config.sni?.takeIf { it.isNotBlank() } ?: config.address)
                put("insecure", config.insecure)
                if (config.alpn.isNotEmpty()) {
                    put("alpn", JsonArray(config.alpn.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                }
                config.pinSHA256?.takeIf { it.isNotBlank() }?.let { pin ->
                    put("certificate_public_key_sha256", buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive(pin))
                    })
                }
            })
            if (isHy2 && !config.obfsType.isNullOrBlank()) {
                put("obfs", buildJsonObject {
                    put("type", config.obfsType!!)
                    config.obfsPassword?.let { put("password", it) }
                })
            }
        }
    }
}
