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

    /**
     * Safe, structured summary of one parsed subscription payload.
     * It intentionally contains no raw URI, UUID, password, key or source URL.
     */
    data class ParseReport(
        val detectedFormat: String,
        val candidateCount: Int?,
        val acceptedCount: Int,
        val rejectedCount: Int?,
        val duplicateCount: Int,
        val protocolCounts: Map<String, Int>,
        val rejectionReasons: Map<String, Int>,
        val decodedBase64: Boolean
    )

    data class ParseOutcome(
        val configs: List<ParsedConfig>,
        val report: ParseReport
    )

    /**
     * Parse once through the existing parser and return an explainable count summary.
     * Rejection reasons are deliberately format-level until individual parser branches
     * can provide precise, non-sensitive reason codes.
     */
    fun parseWithReport(rawContent: String, providerTag: String = ""): ParseOutcome {
        val trimmed = rawContent.trim()
        val decoded = trimmed.takeIf { it.isNotBlank() }?.let(::tryBase64Decode)?.trim()
        val useDecoded = !decoded.isNullOrBlank() && decoded != trimmed &&
            detectPayloadFormat(decoded) != "unknown"
        val effective = if (useDecoded) decoded!! else trimmed
        val format = detectPayloadFormat(effective)
        val rejectionReasons = linkedMapOf<String, Int>()
        val configs = parseInternal(rawContent, providerTag, rejectionReasons)
        val countedCandidates = countPayloadCandidates(effective, format)
        val candidateCount = countedCandidates?.coerceAtLeast(configs.size)
        val rejectedCount = candidateCount?.minus(configs.size)?.coerceAtLeast(0)
        val reportedRejections = rejectionReasons.toMutableMap()
        val classifiedCount = reportedRejections.values.sum()
        if (rejectedCount != null && classifiedCount < rejectedCount) {
            reportedRejections["malformed_or_unclassified_entry"] = rejectedCount - classifiedCount
        } else if (rejectedCount == null && configs.isEmpty()) {
            reportedRejections.putIfAbsent("unrecognized_or_malformed_payload", 1)
        }
        val duplicateCount = (configs.size - configs.distinctBy { it.connectionKey() }.size).coerceAtLeast(0)
        val report = ParseReport(
            detectedFormat = format,
            candidateCount = candidateCount,
            acceptedCount = configs.size,
            rejectedCount = rejectedCount,
            duplicateCount = duplicateCount,
            protocolCounts = configs.groupingBy { it.transport.lowercase(java.util.Locale.ROOT) }.eachCount(),
            rejectionReasons = reportedRejections,
            decodedBase64 = useDecoded
        )
        return ParseOutcome(configs, report)
    }

    private fun detectPayloadFormat(content: String): String = when {
        content.isBlank() -> "empty"
        content.startsWith("[") || content.startsWith("{") -> "json"
        content.contains("proxies:") || content.contains("proxy-groups:") -> "clash_yaml"
        content.lineSequence().any { it.trim().contains("://") && !it.trim().startsWith("#") } -> "uri"
        else -> "unknown"
    }

    private fun MutableMap<String, Int>.increment(reason: String) {
        this[reason] = (this[reason] ?: 0) + 1
    }

    private fun classifyUriRejection(line: String, scheme: String): String {
        if (scheme !in setOf("vless", "hysteria2", "hy2", "tuic", "ss", "vmess", "trojan", "socks", "socks5")) return "unsupported_scheme"
        val uri = runCatching { URI(line.substringBefore('#')) }.getOrElse { return "malformed_uri" }
        if (uri.host.isNullOrBlank()) return "missing_server"
        if (uri.port > 65535 || uri.port == 0) return "invalid_port"
        if (scheme in setOf("vless", "hysteria2", "hy2", "tuic") && uri.rawUserInfo.isNullOrBlank()) return "missing_credentials"
        if (scheme == "vless") {
            val query = runCatching { parseUriQuery(uri.rawQuery) }
                .getOrElse { return "invalid_query_parameters" }
            if (query["security"].equals("reality", ignoreCase = true) &&
                query["publickey"].isNullOrBlank() && query["pbk"].isNullOrBlank()
            ) return "missing_reality_public_key"
        }
        return "invalid_or_malformed_entry"
    }

    private fun classifyJsonRejection(obj: JsonObject): String {
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        if (!type.isNullOrBlank()) {
            if (!type.equals("vless", ignoreCase = true)) return "unsupported_protocol"
            if (obj["server"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) return "missing_server"
            val port = obj["server_port"]?.jsonPrimitive?.intOrNull ?: return "missing_port"
            if (port !in 1..65535) return "invalid_port"
            if (obj["uuid"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) return "missing_credentials"
            val reality = obj["tls"]?.jsonObject?.get("reality")?.jsonObject
            if (reality?.get("enabled")?.jsonPrimitive?.booleanOrNull == true &&
                reality["public_key"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
            ) return "missing_reality_public_key"
            return "invalid_or_malformed_entry"
        }
        val protocol = obj["protocol"]?.jsonPrimitive?.contentOrNull ?: return "missing_protocol"
        if (!protocol.equals("vless", ignoreCase = true) && !protocol.equals("hysteria", ignoreCase = true)) return "unsupported_protocol"
        if (protocol.equals("hysteria", ignoreCase = true)) {
            val settings = obj["settings"]?.jsonObject ?: return "missing_required_settings"
            val stream = obj["streamSettings"]?.jsonObject
            val version = settings["version"]?.jsonPrimitive?.intOrNull
                ?: stream?.get("hysteriaSettings")?.jsonObject?.get("version")?.jsonPrimitive?.intOrNull
            if (version != 2) return "unsupported_protocol_version"
            if (settings["address"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() &&
                settings["server"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
            ) return "missing_server"
            if (settings["auth"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() &&
                settings["password"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() &&
                stream?.get("hysteriaSettings")?.jsonObject?.get("auth")?.jsonPrimitive?.contentOrNull.isNullOrBlank()
            ) return "missing_credentials"
            return "invalid_or_malformed_entry"
        }
        val settings = obj["settings"]?.jsonObject ?: return "missing_required_settings"
        val vnext = settings["vnext"]?.jsonArray?.firstOrNull()?.jsonObject ?: return "missing_server"
        if (vnext["address"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) return "missing_server"
        val user = vnext["users"]?.jsonArray?.firstOrNull()?.jsonObject ?: return "missing_credentials"
        if (user["id"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) return "missing_credentials"
        val port = vnext["port"]?.jsonPrimitive?.intOrNull ?: 443
        if (port !in 1..65535) return "invalid_port"
        val stream = obj["streamSettings"]?.jsonObject
        val reality = stream?.get("realitySettings")?.jsonObject
        if (stream?.get("security")?.jsonPrimitive?.contentOrNull.equals("reality", ignoreCase = true) &&
            reality?.get("publicKey")?.jsonPrimitive?.contentOrNull.isNullOrBlank()
        ) return "missing_reality_public_key"
        return "invalid_or_malformed_entry"
    }

    private fun countPayloadCandidates(content: String, format: String): Int? = runCatching {
        when (format) {
            "uri" -> content.lineSequence().count { line ->
                val candidate = line.trim()
                candidate.contains("://") && !candidate.startsWith("#")
            }
            "json" -> when (val element = Json.parseToJsonElement(content)) {
                is JsonArray -> element.count { it is JsonObject }
                is JsonObject -> {
                    val outbounds = element["outbounds"]
                    if (outbounds is JsonArray) outbounds.count { it is JsonObject } else 1
                }
                else -> 0
            }
            "clash_yaml" -> {
                val root = Yaml().load<Map<String, Any>>(content)
                (root?.get("proxies") as? List<*>)?.size
            }
            else -> null
        }
    }.getOrNull()

    fun parse(rawContent: String, providerTag: String = ""): List<ParsedConfig> =
        parseInternal(rawContent, providerTag, null)

    private fun parseInternal(
        rawContent: String,
        providerTag: String,
        rejectionCounts: MutableMap<String, Int>?
    ): List<ParsedConfig> {
        val trimmed = rawContent.trim()
        if (trimmed.isBlank()) {
            logCollector.warn(TAG, "Subscription import rejected: empty payload provider=$providerTag")
            return emptyList()
        }

        val lineCount = trimmed.lineSequence().count { it.isNotBlank() }
        val detectedFormat = when {
            trimmed.startsWith("[") || trimmed.startsWith("{") -> "json"
            trimmed.contains("proxies:") || trimmed.contains("proxy-groups:") -> "clash_yaml"
            else -> "base64_or_uri_text"
        }
        logCollector.info(TAG, "Subscription import started: provider=$providerTag chars=${trimmed.length} nonEmptyLines=$lineCount formatHint=$detectedFormat")

        val decoded = tryBase64Decode(trimmed)?.trim()
        if (!decoded.isNullOrBlank() && decoded != trimmed) {
            // Some providers Base64-wrap JSON as well as plain URI lists.
            if (decoded.startsWith("[") || decoded.startsWith("{")) {
                val jsonResult = tryParseJson(decoded, providerTag, rejectionCounts)
                if (jsonResult.isNotEmpty()) {
                    logCollector.info(TAG, "Decoded Base64 JSON subscription: parsed=${jsonResult.size}")
                    return jsonResult
                }
            }
            if (decoded.contains("proxies:") || decoded.contains("proxy-groups:")) {
                val yamlResult = tryParseClashYaml(decoded, providerTag, rejectionCounts)
                if (yamlResult.isNotEmpty()) return yamlResult
            }
            if (containsUriCandidate(decoded)) return parseVlessLines(decoded, providerTag, rejectionCounts)
        }

        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            val jsonResult = tryParseJson(trimmed, providerTag, rejectionCounts)
            if (jsonResult.isNotEmpty()) return jsonResult
        }

        if (trimmed.contains("proxies:") || trimmed.contains("proxy-groups:")) {
            val yamlResult = tryParseClashYaml(trimmed, providerTag, rejectionCounts)
            if (yamlResult.isNotEmpty()) return yamlResult
        }

        if (containsUriCandidate(trimmed)) {
            // Keep scheme detection case-insensitive, matching parseVlessLines().
            return parseVlessLines(trimmed, providerTag, rejectionCounts)
        }

        logCollector.warn(TAG, "Unknown subscription format", mapOf("length" to trimmed.length))
        return emptyList()
    }

    private fun tryBase64Decode(input: String): String? {
        val clean = input.replace("\n", "").replace("\r", "").replace(" ", "")
        // java.util.Base64 is deterministic in local JVM unit tests; Android's Base64
        // stub can fail in tests and does not consistently cover URL-safe provider output.
        return runCatching {
            String(java.util.Base64.getDecoder().decode(clean), Charsets.UTF_8)
        }.recoverCatching {
            String(java.util.Base64.getUrlDecoder().decode(clean), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun containsUriCandidate(text: String): Boolean =
        text.lineSequence().any { it.trim().contains("://") && !it.trim().startsWith("#") }

    private fun parseVlessLines(
        text: String,
        providerTag: String,
        rejectionCounts: MutableMap<String, Int>? = null
    ): List<ParsedConfig> {
        val candidates = text.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.contains("://") && !line.startsWith("#")
            }.toList()
        val parsed = mutableListOf<ParsedConfig>()
        val skippedByScheme = linkedMapOf<String, Int>()
        candidates.forEachIndexed { index, line ->
            val scheme = line.substringBefore("://").lowercase()
            val config = when {
                scheme == "vless" -> parseVlessUri(line, providerTag)
                scheme == "hysteria2" || scheme == "hy2" -> parseHysteria2Uri(line, providerTag)
                scheme == "tuic" -> parseTuicUri(line, providerTag)
                scheme == "ss" -> ExtraUriParsers.parseShadowsocks(line, providerTag, logCollector)
                scheme == "vmess" -> ExtraUriParsers.parseVmess(line, providerTag, logCollector)
                scheme == "trojan" -> ExtraUriParsers.parseTrojan(line, providerTag, logCollector)
                scheme == "socks" || scheme == "socks5" -> ExtraUriParsers.parseSocks(line, providerTag, logCollector)
                else -> null
            }
            if (config != null) {
                parsed += config
            } else {
                skippedByScheme[scheme] = (skippedByScheme[scheme] ?: 0) + 1
                rejectionCounts?.increment(classifyUriRejection(line, scheme))
                val label = line.substringAfter('#', "").take(64).ifBlank { "no-name" }
                    .replace(Regex("[^\\p{L}\\p{N} _.,()\\-]"), "_")
                // Never log the URI itself: it contains UUIDs/passwords/Reality keys.
                logCollector.warn(TAG, "Subscription candidate rejected: provider=$providerTag index=${index + 1} scheme=$scheme label=$label reason=parser_returned_null")
            }
        }
        val protocolCounts = parsed.groupingBy { it.transport.lowercase() }.eachCount()
        logCollector.info(
            TAG,
            "URI subscription parse summary: candidates=${candidates.size} parsed=${parsed.size} skipped=${candidates.size - parsed.size} protocols=$protocolCounts skippedByScheme=$skippedByScheme"
        )
        return parsed
    }

    fun parseHysteria2Uri(uri: String, providerTag: String = ""): ParsedConfig? {
        return try {
            val normalized = uri.replaceFirst("hy2://", "hysteria2://")
            val (parsed, rawFragment) = parseUriAndFragment(normalized)
            val password = parsed.rawUserInfo?.takeIf { it.isNotBlank() }?.let(::decodeUriComponent) ?: return null
            val address = parsed.host ?: return null
            val port = parsed.port.takeIf { it > 0 } ?: 443
            val params = parseUriQuery(parsed.rawQuery)
            val sni = params["sni"] ?: params["peer"]
            val name = rawFragment?.let(::decodeUriComponentSafely) ?: "$address:$port"
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
                pinSHA256 = params["pinsha256"] ?: params["pin_sha256"]
            )
            config.takeIf { it.isValid() }
        } catch (e: Exception) {
            logCollector.debug(TAG, "Failed to parse hysteria2 URI: ${e.javaClass.simpleName}")
            null
        }
    }

    fun parseTuicUri(uri: String, providerTag: String = ""): ParsedConfig? {
        return try {
            val (parsed, rawFragment) = parseUriAndFragment(uri)
            val rawUserInfo = parsed.rawUserInfo?.takeIf { it.isNotBlank() } ?: return null
            val rawUuid = rawUserInfo.substringBefore(":").takeIf { it.isNotBlank() } ?: return null
            val rawPassword = rawUserInfo.substringAfter(":", missingDelimiterValue = "")
            val uuid = decodeUriComponent(rawUuid)
            val password = decodeUriComponent(rawPassword)
            val address = parsed.host ?: return null
            val port = parsed.port.takeIf { it > 0 } ?: 443
            val params = parseUriQuery(parsed.rawQuery)
            val sni = params["sni"] ?: params["peer"]
            val name = rawFragment?.let(::decodeUriComponentSafely) ?: "$address:$port"
            val config = ParsedConfig(
                uuid = uuid, address = address, port = port,
                transport = "tuic", security = "tls", sni = sni,
                path = null, host = null, mode = null, fingerprint = null,
                publicKey = null, shortId = null, flow = null,
                name = name, providerTag = providerTag,
                password = password,
                insecure = params["insecure"].isTruthy(),
                alpn = params["alpn"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                pinSHA256 = params["pinsha256"] ?: params["pin_sha256"],
                congestionControl = (params["congestion_control"] ?: params["congestioncontrol"] ?: "bbr")
                    .takeIf { it in setOf("bbr", "cubic", "new_reno") } ?: "bbr"
            )
            config.takeIf { it.isValid() }
        } catch (e: Exception) {
            logCollector.debug(TAG, "Failed to parse tuic URI: ${e.javaClass.simpleName}")
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
            // Provider implementations differ in the capitalization of query keys
            // (notably serviceName, publicKey and shortId). Keep original spellings
            // and add normalized aliases so existing lookups accept either form.
            .let { decoded ->
                decoded + decoded.entries.associate { (key, value) ->
                    key.lowercase(java.util.Locale.ROOT) to value
                }
            }

    /**
     * Parse the URI without its display-name fragment. java.net.URI rejects raw
     * Unicode (including flag emoji) in some fragments, although the fragment is
     * only a user-visible profile name and is not part of the connection endpoint.
     */
    private fun parseUriAndFragment(rawUri: String): Pair<URI, String?> {
        val fragmentIndex = rawUri.indexOf('#')
        val uriPart = if (fragmentIndex >= 0) rawUri.substring(0, fragmentIndex) else rawUri
        val rawFragment = if (fragmentIndex >= 0) rawUri.substring(fragmentIndex + 1) else null
        return URI(uriPart) to rawFragment
    }

    /** A malformed percent escape in a display name must not discard the server. */
    private fun decodeUriComponentSafely(value: String): String =
        runCatching { decodeUriComponent(value) }.getOrDefault(value)

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
                logCollector.warn(TAG, "Invalid XHTTP extra JSON: ${e.javaClass.simpleName}")
                return null
            }
        }

        val normalized = extraObject.toMutableMap()
        val placementKeys = setOf(
            "session_placement", "sessionPlacement", "sessionIDPlacement",
            "sessionIdPlacement", "session_id_placement"
        )
        if (normalized.keys.none { it in placementKeys }) {
            queryParams["sessionidplacement"]?.takeIf { it.isNotBlank() }?.let {
                normalized["sessionIDPlacement"] = JsonPrimitive(it)
            }
        }
        val sessionKeyKeys = setOf(
            "session_key", "sessionKey", "sessionIDKey", "sessionIdKey", "session_id_key"
        )
        if (normalized.keys.none { it in sessionKeyKeys }) {
            queryParams["sessionidkey"]?.takeIf { it.isNotBlank() }?.let {
                normalized["sessionIDKey"] = JsonPrimitive(it)
            }
        }
        return JsonObject(normalized).toString()
    }

    fun parseVlessUri(uri: String, providerTag: String = ""): ParsedConfig? {
        return try {
            val (parsed, rawFragment) = parseUriAndFragment(uri)
            val uuid = parsed.rawUserInfo?.takeIf { it.isNotBlank() }?.let(::decodeUriComponent) ?: return null
            val address = parsed.host ?: return null
            val port = parsed.port.takeIf { it > 0 } ?: 443
            val params = parseUriQuery(parsed.rawQuery)
            val transport = (params["type"] ?: params["transport"] ?: "tcp").lowercase()
            val security = (params["security"] ?: "none").lowercase()
            val sni = params["sni"] ?: params["servername"]
            // VLESS gRPC carries its service path in serviceName/authority.
            // ParsedConfig.path is consumed as Sing-box's service_name by the builder.
            val grpcServiceName = params["servicename"] ?: params["service_name"] ?: params["authority"]
            val path = if (transport == "grpc") grpcServiceName ?: params["path"] else params["path"]
            val host = params["host"]
            val fingerprint = params["fp"] ?: params["fingerprint"]
            val publicKey = params["pbk"] ?: params["publickey"]
            val shortId = params["sid"] ?: params["shortid"] ?: ""
            val rawFlow = params["flow"]
            val rawMode = params["mode"]
            val mode = if (transport == "xhttp") rawMode?.takeIf { it.isNotBlank() } ?: "auto" else rawMode
            val flow = if (transport == "xhttp") "" else rawFlow
            val name = rawFragment?.let(::decodeUriComponentSafely) ?: "$address:$port"
            val config = ParsedConfig(
                uuid = uuid, address = address, port = port,
                transport = transport, security = security,
                sni = sni, path = path, host = host, mode = mode,
                fingerprint = fingerprint, publicKey = publicKey,
                shortId = shortId, flow = flow, name = name,
                providerTag = providerTag,
                insecure = (params["allowinsecure"] ?: params["insecure"]).isTruthy(),
                alpn = params["alpn"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                extra = if (transport.equals("xhttp", ignoreCase = true)) normalizeXhttpExtra(params["extra"], params) else null
            )
            if (!config.isValid()) {
                logCollector.warn(TAG, "Invalid config skipped", mapOf("name" to name, "transport" to config.transport))
                return null
            }
            config
        } catch (e: Exception) {
            logCollector.debug(TAG, "Failed to parse vless URI: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun tryParseJson(
        json: String,
        providerTag: String,
        rejectionCounts: MutableMap<String, Int>? = null
    ): List<ParsedConfig> {
        return try {
            val element = Json.parseToJsonElement(json)
            val configs = mutableListOf<ParsedConfig>()
            var candidateCount = 0
            when (element) {
                is JsonArray -> element.forEach { item ->
                    if (item is JsonObject) {
                        candidateCount++
                        val parsed = extractFromJsonObject(item, providerTag)
                        if (parsed != null) configs.add(parsed)
                        else rejectionCounts?.increment(classifyJsonRejection(item))
                    }
                }
                is JsonObject -> {
                    element["outbounds"]?.jsonArray?.forEach { item ->
                        if (item is JsonObject) {
                            candidateCount++
                            val parsed = extractFromJsonObject(item, providerTag)
                            if (parsed != null) configs.add(parsed)
                            else rejectionCounts?.increment(classifyJsonRejection(item))
                        }
                    } ?: run {
                        candidateCount = 1
                        val parsed = extractFromJsonObject(element, providerTag)
                        if (parsed != null) configs.add(parsed)
                        else rejectionCounts?.increment(classifyJsonRejection(element))
                    }
                }
                else -> {}
            }
            val schemaKinds = when (element) {
                is JsonArray -> element.filterIsInstance<JsonObject>().groupingBy {
                    it["type"]?.jsonPrimitive?.contentOrNull ?: it["protocol"]?.jsonPrimitive?.contentOrNull ?: "missing_type"
                }.eachCount()
                is JsonObject -> element["outbounds"]?.jsonArray?.filterIsInstance<JsonObject>()?.groupingBy {
                    it["type"]?.jsonPrimitive?.contentOrNull ?: it["protocol"]?.jsonPrimitive?.contentOrNull ?: "missing_type"
                }?.eachCount() ?: mapOf("single_object" to candidateCount)
                else -> emptyMap()
            }
            logCollector.info(TAG, "JSON subscription parse summary: candidates=$candidateCount parsed=${configs.size} skipped=${candidateCount - configs.size} schemaKinds=$schemaKinds supportedSchema=sing-box_outbound_or_legacy_xray_vless")
            configs
        } catch (e: Exception) {
            logCollector.warn(TAG, "JSON parse failed: ${e.javaClass.simpleName}")
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
                    path = if (transport.equals("grpc", ignoreCase = true)) {
                        transportObject?.get("service_name")?.jsonPrimitive?.content
                            ?: transportObject?.get("serviceName")?.jsonPrimitive?.content
                            ?: transportObject?.get("path")?.jsonPrimitive?.content
                    } else transportObject?.get("path")?.jsonPrimitive?.content,
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

            // Legacy Xray Hysteria v2 schema. Xray calls the protocol "hysteria",
            // while Sing-box uses the outbound type "hysteria2".
            val protocol = obj["protocol"]?.jsonPrimitive?.content ?: return null
            if (protocol.equals("hysteria", ignoreCase = true)) {
                val settings = obj["settings"]?.jsonObject ?: return null
                val stream = obj["streamSettings"]?.jsonObject
                val hysteriaSettings = stream?.get("hysteriaSettings")?.jsonObject
                val version = settings["version"]?.jsonPrimitive?.intOrNull
                    ?: hysteriaSettings?.get("version")?.jsonPrimitive?.intOrNull
                if (version != 2) {
                    logCollector.warn(TAG, "Unsupported legacy Xray Hysteria version skipped: version=$version")
                    return null
                }
                val address = settings["address"]?.jsonPrimitive?.content
                    ?: settings["server"]?.jsonPrimitive?.content ?: return null
                val port = settings["port"]?.jsonPrimitive?.intOrNull ?: return null
                val auth = hysteriaSettings?.get("auth")?.jsonPrimitive?.content
                    ?: settings["auth"]?.jsonPrimitive?.content
                    ?: settings["password"]?.jsonPrimitive?.content
                    ?: return null
                val tls = stream?.get("tlsSettings")?.jsonObject
                val parsed = ParsedConfig(
                    uuid = auth,
                    address = address,
                    port = port,
                    transport = "hysteria2",
                    security = "tls",
                    sni = tls?.get("serverName")?.jsonPrimitive?.content
                        ?: tls?.get("server_name")?.jsonPrimitive?.content,
                    path = null,
                    host = null,
                    mode = null,
                    fingerprint = tls?.get("fingerprint")?.jsonPrimitive?.content,
                    publicKey = null,
                    shortId = null,
                    flow = null,
                    name = obj["tag"]?.jsonPrimitive?.content ?: address + ":" + port,
                    providerTag = providerTag,
                    password = auth,
                    insecure = tls?.get("allowInsecure")?.jsonPrimitive?.booleanOrNull
                        ?: tls?.get("insecure")?.jsonPrimitive?.booleanOrNull ?: false,
                    alpn = tls?.get("alpn")?.jsonArray
                        ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                        ?: emptyList()
                )
                return parsed.takeIf { it.isValid() }
            }
            if (!protocol.equals("vless", ignoreCase = true)) return null
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
            val grpc = stream?.get("grpcSettings")?.jsonObject
            val tls = stream?.get("tlsSettings")?.jsonObject
            val transportPath = if (transport.equals("grpc", ignoreCase = true)) {
                grpc?.get("serviceName")?.jsonPrimitive?.content ?: grpc?.get("service_name")?.jsonPrimitive?.content
            } else xhttp?.get("path")?.jsonPrimitive?.content
            ParsedConfig(
                uuid = uuid, address = address, port = port,
                transport = transport, security = security,
                sni = reality?.get("serverName")?.jsonPrimitive?.content ?: tls?.get("serverName")?.jsonPrimitive?.content,
                path = transportPath,
                host = xhttp?.get("host")?.jsonPrimitive?.content,
                mode = xhttp?.get("mode")?.jsonPrimitive?.content,
                fingerprint = reality?.get("fingerprint")?.jsonPrimitive?.content ?: tls?.get("fingerprint")?.jsonPrimitive?.content,
                publicKey = reality?.get("publicKey")?.jsonPrimitive?.content,
                shortId = reality?.get("shortId")?.jsonPrimitive?.content,
                flow = user["flow"]?.jsonPrimitive?.content,
                name = "$address:$port",
                providerTag = providerTag,
                insecure = tls?.get("allowInsecure")?.jsonPrimitive?.booleanOrNull ?: false,
                alpn = tls?.get("alpn")?.jsonArray
                    ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    ?: emptyList()
            ).takeIf { it.isValid() }
        } catch (_: Exception) { null }
    }

    @Suppress("UNCHECKED_CAST")
    private fun tryParseClashYaml(
        yamlText: String,
        providerTag: String,
        rejectionCounts: MutableMap<String, Int>? = null
    ): List<ParsedConfig> {
        return try {
            val yaml = Yaml()
            val root = yaml.load<Map<String, Any>>(yamlText) ?: return emptyList()
            val proxies = root["proxies"] as? List<Map<String, Any>> ?: return emptyList()
            buildList {
                proxies.forEach { proxy ->
                    val parsed = try {
                        val type = proxy["type"] as? String
                        if (!type.equals("vless", ignoreCase = true)) {
                            null
                        } else {
                            val name = proxy["name"] as? String ?: "unknown"
                            val server = proxy["server"] as? String
                            val port = (proxy["port"] as? Number)?.toInt() ?: 443
                            val uuid = proxy["uuid"] as? String
                            if (server.isNullOrBlank() || uuid.isNullOrBlank()) null
                            else {
                                val network = proxy["network"] as? String ?: "tcp"
                                val tls = proxy["tls"] as? Boolean ?: false
                                val reality = proxy["reality-opts"] as? Map<String, Any>
                                val xhttpOptions = proxy["xhttp-opts"] as? Map<String, Any>
                                val xhttpHeaders = xhttpOptions?.get("headers") as? Map<String, Any>
                                val wsHeaders = (proxy["ws-opts"] as? Map<String, Any>)?.get("headers") as? Map<String, Any>
                                val security = when {
                                    reality != null -> "reality"
                                    tls -> "tls"
                                    else -> "none"
                                }
                                ParsedConfig(
                                    uuid = uuid, address = server, port = port,
                                    transport = network, security = security,
                                    sni = proxy["servername"] as? String ?: proxy["sni"] as? String,
                                    path = if (network.equals("grpc", ignoreCase = true)) {
                                        val grpcOptions = proxy["grpc-opts"] as? Map<String, Any>
                                        grpcOptions?.get("grpc-service-name") as? String
                                            ?: grpcOptions?.get("service-name") as? String
                                    } else if (network.equals("xhttp", ignoreCase = true)) {
                                        xhttpOptions?.get("path") as? String
                                    } else {
                                        (proxy["ws-opts"] as? Map<String, Any>)?.get("path") as? String
                                    },
                                    host = if (network.equals("xhttp", ignoreCase = true)) {
                                        xhttpOptions?.get("host") as? String
                                            ?: xhttpHeaders?.get("Host") as? String
                                            ?: xhttpHeaders?.get("host") as? String
                                    } else {
                                        wsHeaders?.get("Host") as? String ?: wsHeaders?.get("host") as? String
                                    },
                                    mode = (proxy["xhttp-opts"] as? Map<String, Any>)?.get("mode") as? String,
                                    fingerprint = proxy["client-fingerprint"] as? String,
                                    publicKey = reality?.get("public-key") as? String,
                                    shortId = reality?.get("short-id") as? String,
                                    flow = proxy["flow"] as? String,
                                    name = name, providerTag = providerTag
                                ).takeIf { it.isValid() }
                            }
                        }
                    } catch (_: Exception) { null }
                    if (parsed != null) add(parsed)
                    else rejectionCounts?.increment(classifyClashRejection(proxy))
                }
            }
        } catch (e: Exception) {
            logCollector.warn(TAG, "Clash YAML parse failed: " + e.javaClass.simpleName)
            emptyList()
        }
    }

    private fun classifyClashRejection(proxy: Map<String, Any>): String {
        if (!(proxy["type"] as? String).equals("vless", ignoreCase = true)) return "unsupported_protocol"
        if ((proxy["server"] as? String).isNullOrBlank()) return "missing_server"
        if ((proxy["uuid"] as? String).isNullOrBlank()) return "missing_credentials"
        val port = (proxy["port"] as? Number)?.toInt() ?: 443
        if (port !in 1..65535) return "invalid_port"
        if (proxy["reality-opts"] is Map<*, *>) {
            val reality = proxy["reality-opts"] as? Map<*, *>
            if ((reality?.get("public-key") as? String).isNullOrBlank()) return "missing_reality_public_key"
        }
        return "invalid_or_malformed_entry"
    }
}
