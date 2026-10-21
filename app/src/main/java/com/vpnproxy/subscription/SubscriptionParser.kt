package com.vpnproxy.subscription

import android.util.Base64
import com.vpnproxy.logging.LogCollector
import kotlinx.serialization.json.*
import org.yaml.snakeyaml.Yaml
import java.net.URI
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Парсер подписок. Поддерживает:
 * 1. base64-encoded список vless:// / hysteria2:// / tuic:// ссылок
 * 2. JSON-массив legacy / Sing-box конфигов
 * 3. Clash YAML
 * 4. Обычный текст с vless:// / hysteria2:// / tuic:// построчно
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
        val transport: String,
        val security: String,
        val sni: String?,
        val path: String?,
        val host: String?,
        val mode: String?,
        val fingerprint: String?,
        val publicKey: String?,
        val shortId: String?,
        val flow: String?,
        val name: String,
        val providerTag: String = "",
        val password: String? = null,
        val insecure: Boolean = false,
        val alpn: List<String> = emptyList(),
        val obfsType: String? = null,
        val obfsPassword: String? = null,
        val pinSHA256: String? = null,
        val congestionControl: String = "bbr",
        val extra: String? = null
    ) {
        /**
         * Stable identity for a connection profile. Endpoint + UUID alone is too coarse:
         * subscriptions can publish the same endpoint with different transports/security.
         * Name and providerTag are intentionally excluded so identical profiles from
         * different providers are counted once globally but remain attributable per provider.
         */
        fun connectionKey(): String = listOf(
            address.lowercase(java.util.Locale.ROOT),
            port.toString(),
            uuid.lowercase(java.util.Locale.ROOT),
            transport.lowercase(java.util.Locale.ROOT),
            security.lowercase(java.util.Locale.ROOT),
            sni.orEmpty().lowercase(java.util.Locale.ROOT),
            path.orEmpty(),
            host.orEmpty().lowercase(java.util.Locale.ROOT),
            mode.orEmpty().lowercase(java.util.Locale.ROOT),
            fingerprint.orEmpty().lowercase(java.util.Locale.ROOT),
            publicKey.orEmpty(),
            shortId.orEmpty(),
            flow.orEmpty(),
            password.orEmpty(),
            insecure.toString(),
            alpn.joinToString(","),
            obfsType.orEmpty(),
            obfsPassword.orEmpty(),
            pinSHA256.orEmpty(),
            congestionControl.lowercase(java.util.Locale.ROOT),
            extra.orEmpty()
        ).joinToString("\u001F")

        fun isValid(): Boolean {
            if (uuid.isBlank() || address.isBlank()) return false
            if (port !in 1..65535) return false
            if (transport in setOf("hysteria2", "hy2", "tuic")) return true
            if (security == "reality" && publicKey.isNullOrBlank()) return false
            return true
        }

        fun requiresSingBox(): Boolean =
            transport.equals("hysteria2", ignoreCase = true) ||
                transport.equals("hy2", ignoreCase = true) ||
                transport.equals("tuic", ignoreCase = true) ||
                transport.equals("xhttp", ignoreCase = true)
    }

    fun parse(rawContent: String, providerTag: String = ""): List<ParsedConfig> {
        val trimmed = rawContent.trim()
        if (trimmed.isBlank()) return emptyList()

        val decoded = tryBase64Decode(trimmed)
        if (decoded != null && (decoded.contains("vless://") || decoded.contains("hysteria2://") || decoded.contains("hy2://") || decoded.contains("tuic://"))) {
            return parseVlessLines(decoded, providerTag)
        }

        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            val jsonResult = tryParseJson(trimmed, providerTag)
            if (jsonResult.isNotEmpty()) return jsonResult
        }

        if (trimmed.contains("proxies:") || trimmed.contains("proxy-groups:")) {
            val yamlResult = tryParseClashYaml(trimmed, providerTag)
            if (yamlResult.isNotEmpty()) return yamlResult
        }

        if (trimmed.contains("vless://") || trimmed.contains("hysteria2://") || trimmed.contains("hy2://") || trimmed.contains("tuic://")) {
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
            .map { it.trim() }
            .filter { line ->
                line.startsWith("vless://") ||
                    line.startsWith("hysteria2://") ||
                    line.startsWith("hy2://") ||
                    line.startsWith("tuic://")
            }
            .mapNotNull { line ->
                when {
                    line.startsWith("vless://") -> parseVlessUri(line, providerTag)
                    line.startsWith("hysteria2://") || line.startsWith("hy2://") ->
                        parseHysteria2Uri(line, providerTag)
                    line.startsWith("tuic://") -> parseTuicUri(line, providerTag)
                    else -> null
                }
            }
    }

    fun parseHysteria2Uri(uri: String, providerTag: String = ""): ParsedConfig? {
        return try {
            val normalized = uri.replaceFirst("hy2://", "hysteria2://")
            val parsed = URI(normalized)
            val password = parsed.rawUserInfo?.takeIf { it.isNotBlank() }?.let(::decodeUriComponent) ?: return null
            val address = parsed.host ?: return null
            val port = parsed.port.takeIf { it > 0 } ?: 443
            val params = parseUriQuery(parsed.rawQuery)
            val sni = params["sni"] ?: params["peer"]
            val name = decodeUriComponent(parsed.rawFragment ?: "$address:$port")
            val config = ParsedConfig(
                uuid = password, address = address, port = port,
                transport = "hysteria2", security = "tls", sni = sni,
                path = null, host = null, mode = null, fingerprint = null,
                publicKey = null, shortId = null, flow = null,
                name = name, providerTag = providerTag,
                password = password,
                insecure = params["insecure"].isTruthy(),
                alpn = params["alpn"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                obfsType = params["obfs"] ?: params["obfs-type"],
                obfsPassword = params["obfs-password"],
                pinSHA256 = params["pinSHA256"] ?: params["pin_sha256"]
            )
            config.takeIf { it.isValid() }
        } catch (e: Exception) {
            logCollector.error(TAG, "Failed to parse hysteria2 URI", e)
            null
        }
    }

    fun parseTuicUri(uri: String, providerTag: String = ""): ParsedConfig? {
        return try {
            val parsed = URI(uri)
            val rawUserInfo = parsed.rawUserInfo?.takeIf { it.isNotBlank() } ?: return null
            val rawUuid = rawUserInfo.substringBefore(":").takeIf { it.isNotBlank() } ?: return null
            val rawPassword = rawUserInfo.substringAfter(":", missingDelimiterValue = "")
            val uuid = decodeUriComponent(rawUuid)
            val password = decodeUriComponent(rawPassword)
            val address = parsed.host ?: return null
            val port = parsed.port.takeIf { it > 0 } ?: 443
            val params = parseUriQuery(parsed.rawQuery)
            val sni = params["sni"] ?: params["peer"]
            val name = decodeUriComponent(parsed.rawFragment ?: "$address:$port")
            val config = ParsedConfig(
                uuid = uuid, address = address, port = port,
                transport = "tuic", security = "tls", sni = sni,
                path = null, host = null, mode = null, fingerprint = null,
                publicKey = null, shortId = null, flow = null,
                name = name, providerTag = providerTag,
                password = password,
                insecure = params["insecure"].isTruthy(),
                alpn = params["alpn"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                pinSHA256 = params["pinSHA256"] ?: params["pin_sha256"],
                congestionControl = (params["congestion_control"] ?: params["congestionControl"] ?: "bbr")
                    .takeIf { it in setOf("bbr", "cubic", "new_reno") } ?: "bbr"
            )
            config.takeIf { it.isValid() }
        } catch (e: Exception) {
            logCollector.error(TAG, "Failed to parse tuic URI", e)
            null
        }
    }

    private fun String?.isTruthy(): Boolean =
        this?.lowercase()?.let { it in setOf("1", "true", "yes", "on") } == true

    private fun parseUriQuery(rawQuery: String?): Map<String, String> =
        rawQuery.orEmpty()
            .split("&")
            .filter { it.isNotBlank() }
            .associate { part ->
                val separator = part.indexOf('=')
                val key = if (separator >= 0) part.substring(0, separator) else part
                val value = if (separator >= 0) part.substring(separator + 1) else ""
                decodeUriComponent(key) to decodeUriComponent(value)
            }

    private fun decodeUriComponent(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")

    /**
     * Optional XHTTP `extra`:
     * - missing / blank / literal "null" → empty JSON object without WARN
     * - valid JSON object → normalized string
     * - present but invalid / non-object → WARN once and null
     */
    private fun normalizeXhttpExtra(rawValue: String?): String? {
        if (rawValue == null) return "{}"
        val decoded = try {
            URLDecoder.decode(rawValue, "UTF-8")
        } catch (_: IllegalArgumentException) {
            rawValue
        }
        val trimmed = decoded.trim()
        // Missing/null optional payloads are normalized to an empty object without WARN logs.
        if (trimmed.isEmpty() ||
            trimmed.equals("null", ignoreCase = true) ||
            trimmed.equals("undefined", ignoreCase = true)
        ) {
            return "{}"
        }
        if (trimmed == "{}") return "{}"
        return try {
            val element = Json.parseToJsonElement(trimmed)
            when (element) {
                is JsonObject -> element.toString()
                is JsonNull -> "{}"
                else -> {
                    logCollector.warn(TAG, "XHTTP extra is not a JSON object: $trimmed")
                    null
                }
            }
        } catch (e: Exception) {
            logCollector.warn(TAG, "Invalid XHTTP extra JSON: ${e.message}")
            null
        }
    }

    fun parseVlessUri(uri: String, providerTag: String = ""): ParsedConfig? {
        return try {
            val parsed = URI(uri)
            val uuid = parsed.rawUserInfo?.takeIf { it.isNotBlank() }?.let(::decodeUriComponent) ?: return null
            val address = parsed.host ?: return null
            val port = parsed.port.takeIf { it > 0 } ?: 443
            val params = parseUriQuery(parsed.rawQuery)
            val transport = (params["type"] ?: params["transport"] ?: "tcp").lowercase()
            val security = (params["security"] ?: "none").lowercase()
            val sni = params["sni"] ?: params["serverName"]
            val path = params["path"]
            val host = params["host"]
            val fingerprint = params["fp"] ?: params["fingerprint"]
            val publicKey = params["pbk"] ?: params["publicKey"]
            val shortId = params["sid"] ?: params["shortId"] ?: ""
            val rawFlow = params["flow"]
            val rawMode = params["mode"]
            val mode = if (transport == "xhttp") rawMode?.takeIf { it.isNotBlank() } ?: "auto" else rawMode
            val flow = if (transport == "xhttp") "" else rawFlow
            val name = parsed.rawFragment?.let(::decodeUriComponent) ?: "$address:$port"
            val config = ParsedConfig(
                uuid = uuid, address = address, port = port,
                transport = transport, security = security,
                sni = sni, path = path, host = host, mode = mode,
                fingerprint = fingerprint, publicKey = publicKey,
                shortId = shortId, flow = flow, name = name,
                providerTag = providerTag,
                extra = if (transport.equals("xhttp", ignoreCase = true)) normalizeXhttpExtra(params["extra"]) else null
            )
            if (!config.isValid()) {
                logCollector.warn(TAG, "Invalid config skipped", mapOf("name" to name, "transport" to config.transport))
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
                sni = reality?.get("serverName")?.jsonPrimitive?.content ?: tls?.get("serverName")?.jsonPrimitive?.content,
                path = xhttp?.get("path")?.jsonPrimitive?.content,
                host = xhttp?.get("host")?.jsonPrimitive?.content,
                mode = xhttp?.get("mode")?.jsonPrimitive?.content,
                fingerprint = reality?.get("fingerprint")?.jsonPrimitive?.content ?: tls?.get("fingerprint")?.jsonPrimitive?.content,
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
                        mode = null, fingerprint = proxy["client-fingerprint"] as? String,
                        publicKey = null, shortId = null, flow = proxy["flow"] as? String,
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
