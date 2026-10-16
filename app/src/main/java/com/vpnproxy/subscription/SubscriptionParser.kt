package com.vpnproxy.subscription

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.vpnproxy.logging.LogCollector
import kotlinx.serialization.json.*
import org.yaml.snakeyaml.Yaml
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Парсер подписок. Поддерживает:
 * 1. base64-encoded список vless:// ссылок
 * 2. JSON-массив Xray/sing-box конфигов
 * 3. Clash YAML
 * 4. Обычный текст с vless:// построчно
 */
@Singleton
class SubscriptionParser @Inject constructor(
    private val logCollector: LogCollector
) {
    companion object { private const val TAG = "SubParser" }

    data class ParsedConfig(
        val uuid: String,
        val address: String,
        val port: Int,
        val transport: String,      // xhttp, ws, raw, grpc
        val security: String,       // reality, tls, none
        val sni: String?,
        val path: String?,
        val host: String?,
        val mode: String?,          // stream-one, auto
        val fingerprint: String?,   // firefox, chrome
        val publicKey: String?,     // pbk
        val shortId: String?,       // sid
        val flow: String?,          // xtls-rprx-vision (только TCP)
        val name: String,
        val providerTag: String = ""
    ) {
        /** Валидация обязательных полей. */
        fun isValid(): Boolean {
            if (uuid.isBlank() || address.isBlank()) return false
            if (port !in 1..65535) return false
            // Reality: pbk обязателен, shortId может быть пустым "" (валидный кейс).
            if (security == "reality" && publicKey.isNullOrBlank()) return false
            // Остальные проверки выполняются на этапе парсинга (mode, flow, дефолты).
            return true
        }

        /** Генерация полного outbound-конфига Xray. */
        fun toXrayOutbound(): JsonObject {
            val user = buildJsonObject {
                put("id", uuid)
                put("encryption", "none")
                put("flow", flow ?: "")
            }
            val vnext = buildJsonObject {
                put("address", address)
                put("port", port)
                putJsonArray("users") { add(user) }
            }
            val streamSettings = buildJsonObject {
                put("method", transport)
                when (transport) {
                    "xhttp" -> putJsonObject("xhttpSettings") {
                        put("path", path ?: "/")
                        put("mode", mode ?: "stream-one")
                        host?.let { put("host", it) }
                    }
                    "ws" -> putJsonObject("wsSettings") {
                        put("path", path ?: "/")
                        host?.let { put("headers", buildJsonObject { put("Host", it) }) }
                    }
                    "grpc" -> putJsonObject("grpcSettings") {
                        put("serviceName", path ?: "")
                    }
                }
                put("security", security)
                when (security) {
                    "reality" -> putJsonObject("realitySettings") {
                        put("serverName", sni ?: "")
                        put("fingerprint", fingerprint ?: "firefox")
                        put("publicKey", publicKey ?: "")
                        put("shortId", shortId ?: "")
                    }
                    "tls" -> putJsonObject("tlsSettings") {
                        put("serverName", sni ?: "")
                        put("fingerprint", fingerprint ?: "firefox")
                    }
                }
            }
            return buildJsonObject {
                put("protocol", "vless")
                put("settings", buildJsonObject {
                    putJsonArray("vnext") { add(vnext) }
                })
                put("streamSettings", streamSettings)
            }
        }
    }

    /**
     * Основной метод парсинга. Автоопределяет формат.
     */
    fun parse(rawContent: String, providerTag: String = ""): List<ParsedConfig> {
        val trimmed = rawContent.trim()
        if (trimmed.isBlank()) return emptyList()

        // 1. Попытка base64
        val decoded = tryBase64Decode(trimmed)
        if (decoded != null && decoded.contains("vless://")) {
            return parseVlessLines(decoded, providerTag)
        }

        // 2. Попытка JSON
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            val jsonResult = tryParseJson(trimmed, providerTag)
            if (jsonResult.isNotEmpty()) return jsonResult
        }

        // 3. Попытка Clash YAML
        if (trimmed.contains("proxies:") || trimmed.contains("proxy-groups:")) {
            val yamlResult = tryParseClashYaml(trimmed, providerTag)
            if (yamlResult.isNotEmpty()) return yamlResult
        }

        // 4. Обычный текст с vless://
        if (trimmed.contains("vless://")) {
            return parseVlessLines(trimmed, providerTag)
        }

        logCollector.warn(TAG, "Unknown subscription format", mapOf("length" to trimmed.length))
        return emptyList()
    }

    private fun tryBase64Decode(input: String): String? {
        return try {
            val clean = input.replace("\n", "").replace("\r", "").replace(" ", "")
            String(Base64.decode(clean, Base64.DEFAULT))
        } catch (_: Exception) { null }
    }

    private fun parseVlessLines(text: String, providerTag: String): List<ParsedConfig> {
        return text.lines()
            .filter { it.trim().startsWith("vless://") }
            .mapNotNull { parseVlessUri(it.trim(), providerTag) }
    }

    /**
     * Парсинг vless://uuid@host:port?type=xhttp&security=reality&fp=firefox&...
     */
    fun parseVlessUri(uri: String, providerTag: String = ""): ParsedConfig? {
        return try {
            val parsed = Uri.parse(uri)
            val uuid = parsed.userInfo ?: return null
            val address = parsed.host ?: return null
            val port = parsed.port.takeIf { it > 0 } ?: 443

            val params = parsed.queryParameterNames.associateWith {
                parsed.getQueryParameter(it) ?: ""
            }

            // Порядок критичен: transport → lowercase → mode/flow с учётом transport.
            val transport = (params["type"] ?: params["transport"] ?: "tcp").lowercase()
            val security = (params["security"] ?: "none").lowercase()
            val sni = params["sni"] ?: params["serverName"]
            val path = params["path"]
            val host = params["host"]
            val fingerprint = params["fp"] ?: params["fingerprint"]
            val publicKey = params["pbk"] ?: params["publicKey"]
            val rawShortId = params["sid"] ?: params["shortId"]
            val shortId = rawShortId ?: "" // Reality: пустой sid допустим.
            val rawFlow = params["flow"]
            val rawMode = params["mode"]

            // XHTTP: дефолт stream-one; auto принудительно заменяем.
            val mode: String? = when (transport) {
                "xhttp" -> when (rawMode?.lowercase()) {
                    null, "" , "auto" -> "stream-one"
                    else -> rawMode
                }
                else -> rawMode
            }

            // Vision несовместим с XHTTP; flow на этом транспорте всегда пустой.
            val flow: String? = when (transport) {
                "xhttp" -> ""
                else -> rawFlow
            }
            val name = Uri.decode(parsed.fragment ?: "$address:$port")

            val config = ParsedConfig(
                uuid = uuid, address = address, port = port,
                transport = transport, security = security,
                sni = sni, path = path, host = host, mode = mode,
                fingerprint = fingerprint, publicKey = publicKey,
                shortId = shortId, flow = flow, name = name,
                providerTag = providerTag
            )

            if (!config.isValid()) {
                val reasons = buildList {
                    if (config.uuid.isBlank()) add("uuid empty")
                    if (config.address.isBlank()) add("address empty")
                    if (config.port !in 1..65535) add("port invalid=${config.port}")
                    if (config.security == "reality" && config.publicKey.isNullOrBlank())
                        add("reality missing pbk")
                }
                logCollector.warn(TAG, "Invalid config skipped", mapOf(
                    "name" to name,
                    "reasons" to reasons.joinToString(","),
                    "transport" to config.transport,
                    "security" to config.security,
                    "flow" to (config.flow ?: "-"),
                    "mode" to (config.mode ?: "-"),
                    "pbk_len" to (config.publicKey?.length ?: 0),
                    "sid_len" to (config.shortId?.length ?: 0)
                ))
                return null
            }
            config
        } catch (e: Exception) {
            logCollector.error(TAG, "Failed to parse vless URI", e)
            null
        }
    }

    private fun tryParseJson(json: String, providerTag: String): List<ParsedConfig> {
        return try {
            val element = Json.parseToJsonElement(json)
            val configs = mutableListOf<ParsedConfig>()

            when (element) {
                is JsonArray -> element.forEach { item ->
                    if (item is JsonObject) extractFromJsonObject(item, providerTag)?.let { configs.add(it) }
                }
                is JsonObject -> {
                    // Может быть { "outbounds": [...] } или одиночный конфиг
                    element["outbounds"]?.jsonArray?.forEach { item ->
                        if (item is JsonObject) extractFromJsonObject(item, providerTag)?.let { configs.add(it) }
                    } ?: extractFromJsonObject(element, providerTag)?.let { configs.add(it) }
                }
                else -> {}
            }
            configs
        } catch (e: Exception) {
            logCollector.warn(TAG, "JSON parse failed: ${e.message}")
            emptyList()
        }
    }

    private fun extractFromJsonObject(obj: JsonObject, providerTag: String): ParsedConfig? {
        return try {
            val protocol = obj["protocol"]?.jsonPrimitive?.content ?: return null
            if (protocol != "vless") return null

            val settings = obj["settings"]?.jsonObject ?: return null
            val vnext = settings["vnext"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val address = vnext["address"]?.jsonPrimitive?.content ?: return null
            val port = vnext["port"]?.jsonPrimitive?.int ?: 443
            val user = vnext["users"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val uuid = user["id"]?.jsonPrimitive?.content ?: return null

            val stream = obj["streamSettings"]?.jsonObject
            val transport = stream?.get("method")?.jsonPrimitive?.content ?: "tcp"
            val security = stream?.get("security")?.jsonPrimitive?.content ?: "none"

            val reality = stream?.get("realitySettings")?.jsonObject
            val xhttp = stream?.get("xhttpSettings")?.jsonObject
            val tls = stream?.get("tlsSettings")?.jsonObject

            ParsedConfig(
                uuid = uuid, address = address, port = port,
                transport = transport, security = security,
                sni = reality?.get("serverName")?.jsonPrimitive?.content
                    ?: tls?.get("serverName")?.jsonPrimitive?.content,
                path = xhttp?.get("path")?.jsonPrimitive?.content,
                host = xhttp?.get("host")?.jsonPrimitive?.content,
                mode = xhttp?.get("mode")?.jsonPrimitive?.content,
                fingerprint = reality?.get("fingerprint")?.jsonPrimitive?.content
                    ?: tls?.get("fingerprint")?.jsonPrimitive?.content,
                publicKey = reality?.get("publicKey")?.jsonPrimitive?.content,
                shortId = reality?.get("shortId")?.jsonPrimitive?.content,
                flow = user["flow"]?.jsonPrimitive?.content,
                name = "$address:$port", providerTag = providerTag
            ).takeIf { it.isValid() }
        } catch (_: Exception) { null }
    }

    @Suppress("UNCHECKED_CAST")
    private fun tryParseClashYaml(yamlText: String, providerTag: String): List<ParsedConfig> {
        return try {
            val yaml = Yaml()
            val root = yaml.load<Map<String, Any>>(yamlText) ?: return emptyList()
            val proxies = root["proxies"] as? List<Map<String, Any>> ?: return emptyList()

            proxies.mapNotNull { proxy ->
                try {
                    val type = proxy["type"] as? String ?: return@mapNotNull null
                    if (type != "vless") return@mapNotNull null
                    val name = proxy["name"] as? String ?: "unknown"
                    val server = proxy["server"] as? String ?: return@mapNotNull null
                    val port = (proxy["port"] as? Number)?.toInt() ?: 443
                    val uuid = proxy["uuid"] as? String ?: return@mapNotNull null
                    val network = proxy["network"] as? String ?: "tcp"
                    val tls = proxy["tls"] as? Boolean ?: false

                    ParsedConfig(
                        uuid = uuid, address = server, port = port,
                        transport = if (network == "ws") "ws" else "raw",
                        security = if (tls) "tls" else "none",
                        sni = proxy["servername"] as? String,
                        path = proxy["ws-path"] as? String,
                        host = proxy["ws-headers"]?.let { (it as? Map<String, String>)?.get("Host") },
                        mode = null,
                        fingerprint = proxy["client-fingerprint"] as? String,
                        publicKey = null, shortId = null,
                        flow = proxy["flow"] as? String,
                        name = name, providerTag = providerTag
                    ).takeIf { it.isValid() }
                } catch (_: Exception) { null }
            }
        } catch (e: Exception) {
            logCollector.warn(TAG, "YAML parse failed: ${e.message}")
            emptyList()
        }
    }
}
