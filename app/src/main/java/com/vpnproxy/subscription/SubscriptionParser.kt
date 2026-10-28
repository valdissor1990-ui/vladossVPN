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
 * 1. base64-encoded список vless/ss/vmess/trojan/socks/hysteria2/tuic ссылок
 * 2. JSON-массив legacy / Sing-box конфигов
 * 3. Clash YAML
 * 4. Обычный текст с vless/ss/vmess/trojan/socks/hysteria2/tuic построчно
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
            if (address.isBlank()) return false
            if (port !in 1..65535) return false
            if (transport in setOf("hysteria2", "hy2", "tuic", "shadowsocks", "socks")) {
                if (transport == "shadowsocks" && password.isNullOrBlank() && uuid.isBlank()) return false
                return true
            }
            if (uuid.isBlank()) return false
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
        if (decoded != null && (
            decoded.contains("vless://") || decoded.contains("hysteria2://") || decoded.contains("hy2://") ||
            decoded.contains("tuic://") || decoded.contains("ss://") || decoded.contains("vmess://") ||
            decoded.contains("trojan://") || decoded.contains("socks://") || decoded.contains("socks5://")
        )) {
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

        if (
            trimmed.contains("vless://") || trimmed.contains("hysteria2://") || trimmed.contains("hy2://") ||
            trimmed.contains("tuic://") || trimmed.contains("ss://") || trimmed.contains("vmess://") ||
            trimmed.contains("trojan://") || trimmed.contains("socks://") || trimmed.contains("socks5://")
        ) {
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
                    line.startsWith("tuic://") ||
                    line.startsWith("ss://") ||
                    line.startsWith("vmess://") ||
                    line.startsWith("trojan://") ||
                    line.startsWith("socks://") ||
                    line.startsWith("socks5://")
            }
            .mapNotNull { line ->
                when {
                    line.startsWith("vless://") -> parseVlessUri(line, providerTag)
                    line.startsWith("hysteria2://") || line.startsWith("hy2://") ->
                        parseHysteria2Uri(line, providerTag)
                    line.startsWith("tuic://") -> parseTuicUri(line, providerTag)
                    line.startsWith("ss://") -> ExtraUriParsers.parseShadowsocks(line, providerTag, logCollector)
                    line.startsWith("vmess://") -> ExtraUriParsers.parseVmess(line, providerTag, logCollector)
                    line.startsWith("trojan://") -> ExtraUriParsers.parseTrojan(line, providerTag, logCollector)
                    line.startsWith("socks://") || line.startsWith("socks5://") ->
                        ExtraUriParsers.parseSocks(line, providerTag, logCollector)
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
            logCollector.debug(TAG, "Failed to parse hysteria2 URI: ${e.message}")
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
            logCollector.debug(TAG, "Failed to parse tuic URI: ${e.message}")
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

    private fun normalizeXhttpExtra(
        decodedValue: String?,
        queryParams: Map<String, String> = emptyMap()
    ): String? {
        val trimmed = decodedValue.orEmpty().trim()
        val extraObject = if (
            trimmed.isEmpty() ||
            trimmed.equals("null", ignoreCase = true) ||
            trimmed.equals("undefined", ignoreCase = true)
        ) {
            JsonObject(emptyMap())
        } else {
            try {
                when (val element = Json.parseToJsonElement(trimmed)) {
                    is JsonObject -> element
                    is JsonNull -> JsonObject(emptyMap())
                    else -> {
                        logCollector.warn(TAG, "XHTTP extra is not a JSON object: $trimmed")
                        return null
                    }
                }
            } catch (e: Exception) {
                logCollector.warn(TAG, "Invalid XHTTP extra JSON: ${e.message}")
                return null
            }
        }

        val normalized = extraObject.toMutableMap()
        val placementKeys = setOf(
            "session_placement", "sessionPlacement", "sessionIDPlacement",
            "sessionIdPlacement", "session_id_placement"
        )
        if (normalized.keys.none { it in placementKeys }) {
            queryParams["sessionIDPlacement"]?.takeIf { it.isNotBlank() }?.let {
                normalized["sessionIDPlacement"] = JsonPrimitive(it)
            }
        }
        val sessionKeyKeys = setOf(
            "session_key", "sessionKey", "sessionIDKey", "sessionIdKey", "session_id_key"
        )
        if (normalized.keys.none { it in sessionKeyKeys }) {
            queryParams["sessionIDKey"]?.takeIf { it.isNotBlank() }?.let {
                normalized["sessionIDKey"] = JsonPrimitive(it)
            }
        }
        return JsonObject(normalized).toString()
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
                extra = if (transport.equals("xhttp", ignoreCase = true)) normalizeXhttpExtra(params["extra"], params) else null
            )
            if (!config.isValid()) {
                logCollector.warn(TAG, "Invalid config skipped", mapOf("name" to name, "transport" to config.transport))
                return null
            }
            config
        } catch (e: Exception) {
            logCollector.debug(TAG, "Failed to parse vless URI: ${e.message}")
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
            // sing-box outbound schema: { type, server, server_port, uuid, tls, transport }
            if (obj["type"]?.jsonPrimitive?.content == "vless") {
                val address = obj["server"]?.jsonPrimitive?.content ?: return null
                val port = obj["server_port"]?.jsonPrimitive?.int ?: return null
                val uuid = obj["uuid"]?.jsonPrimitive?.content ?: return null
                val tls = obj["tls"]?.jsonObject
                val reality = tls?.get("reality")?.jsonObject
                val realityEnabled = reality?.get("enabled")?.jsonPrimitive?.boolean == true
                val transportObject = obj["transport"]?.jsonObject
                val transport = transportObject?.get("type")?.jsonPrimitive?.content ?: "tcp"
                val headers = transportObject?.get("headers")?.jsonObject
                val host = headers?.get("Host")?.jsonPrimitive?.content
                    ?: headers?.get("host")?.jsonPrimitive?.content
                val parsed = ParsedConfig(
                    uuid = uuid,
                    address = address,
                    port = port,
                    transport = transport,
                    security = if (tls?.get("enabled")?.jsonPrimitive?.boolean == true) {
                        if (realityEnabled) "reality" else "tls"
                    } else "none",
                    sni = tls?.get("server_name")?.jsonPrimitive?.content,
                    path = transportObject?.get("path")?.jsonPrimitive?.content,
                    host = host,
                    mode = transportObject?.get("mode")?.jsonPrimitive?.content,
                    fingerprint = tls?.get("utls")?.jsonObject?.get("fingerprint")?.jsonPrimitive?.content,
                    publicKey = if (realityEnabled) reality?.get("public_key")?.jsonPrimitive?.content else null,
                    shortId = if (realityEnabled) reality?.get("short_id")?.jsonPrimitive?.content else null,
                    flow = obj["flow"]?.jsonPrimitive?.content,
                    name = obj["tag"]?.jsonPrimitive?.content ?: "$address:$port",
                    providerTag = providerTag,
                    insecure = tls?.get("insecure")?.jsonPrimitive?.boolean ?: false,
                    alpn = tls?.get("alpn")?.jsonArray?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: emptyList()
                )
                return parsed.takeIf { it.isValid() }
            }

            // Legacy V2Ray/Xray JSON schema.
            val protocol = obj["protocol"]?.jsonPrimitive?.content ?: return null
            if (protocol != "vless") return null
            val settings = obj["settings"]?.jsonObject ?: return null
            val vnext = settings["vnext"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val address = vnext["address"]?.jsonPrimitive?.content ?: return null
            val port = vnext["port"]?.jsonPrimitive?.int ?: 443
            val user = vnext["users"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val uuid = user["id"]?.jsonPrimitive?.content ?: return null
            val stream = obj["streamSettings"]?.jsonObject
            val transport = stream?.get("network")?.jsonPrimitive?.content ?: "tcp"
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
                    val reality = proxy["reality-opts"] as? Map<String, Any>
                    val security = when {
                        reality != null -> "reality"
                        tls -> "tls"
                        else -> "none"
                    }
                    ParsedConfig(
                        uuid = uuid, address = server, port = port,
                        transport = network, security = security,
                        sni = proxy["servername"] as? String ?: proxy["sni"] as? String,
                        path = (proxy["ws-opts"] as? Map<String, Any>)?.get("path") as? String
                            ?: (proxy["xhttp-opts"] as? Map<String, Any>)?.get("path") as? String,
                        host = ((proxy["ws-opts"] as? Map<String, Any>)?.get("headers") as? Map<String, Any>)?.get("Host") as? String,
                        mode = (proxy["xhttp-opts"] as? Map<String, Any>)?.get("mode") as? String,
                        fingerprint = proxy["client-fingerprint"] as? String,
                        publicKey = reality?.get("public-key") as? String,
                        shortId = reality?.get("short-id") as? String,
                        flow = proxy["flow"] as? String,
                        name = name, providerTag = providerTag
                    ).takeIf { it.isValid() }
                } catch (_: Exception) { null }
            }
        } catch (e: Exception) {
            logCollector.warn(TAG, "Clash YAML parse failed: ${e.message}")
            emptyList()
        }
    }
}
