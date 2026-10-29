package com.vpnproxy.subscription

import android.util.Base64
import com.vpnproxy.logging.LogCollector
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/**
 * Additional URI schemes for the production [SubscriptionParser] path.
 * Keeps SubscriptionParser size stable while expanding protocol coverage.
 */
internal object ExtraUriParsers {
    private const val TAG = "ExtraUriParsers"

    // Keep this list aligned with cipher methods accepted by the bundled libbox.
    // In particular, "chacha20-poly1305" is not the same declared method as
    // "chacha20-ietf-poly1305" and currently fails during libbox initialization.
    private val supportedShadowsocksMethods = setOf(
        "2022-blake3-aes-128-gcm",
        "2022-blake3-aes-256-gcm",
        "2022-blake3-chacha20-poly1305",
        "none",
        "aes-128-gcm",
        "aes-192-gcm",
        "aes-256-gcm",
        "chacha20-ietf-poly1305",
        "xchacha20-ietf-poly1305"
    )

    fun parseShadowsocks(
        uri: String,
        providerTag: String,
        logCollector: LogCollector
    ): SubscriptionParser.ParsedConfig? {
        return try {
            val raw = uri.removePrefix("ss://")
            val hashIdx = raw.indexOf('#')
            val name = if (hashIdx >= 0) {
                URLDecoder.decode(raw.substring(hashIdx + 1), Charsets.UTF_8.name())
            } else "SS"
            val body = if (hashIdx >= 0) raw.substring(0, hashIdx) else raw
            val method: String
            val password: String
            val host: String
            val port: Int
            val atIdx = body.lastIndexOf('@')
            if (atIdx >= 0) {
                val encodedUserInfo = body.substring(0, atIdx)
                val decodedUserInfo = runCatching {
                    String(
                        Base64.decode(
                            encodedUserInfo,
                            Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP
                        ),
                        Charsets.UTF_8
                    )
                }.getOrNull()?.takeIf { it.contains(':') }
                val userInfo = decodedUserInfo ?: runCatching {
                    URLDecoder.decode(encodedUserInfo.replace("+", "%2B"), Charsets.UTF_8.name())
                }.getOrNull() ?: return null
                val colon = userInfo.indexOf(':')
                if (colon <= 0) return null
                method = userInfo.substring(0, colon).trim().lowercase()
                password = userInfo.substring(colon + 1)
                val hostPort = body.substring(atIdx + 1)
                val hp = hostPort.split(':')
                if (hp.size < 2) return null
                host = hp[0]
                port = hp[1].toIntOrNull() ?: return null
            } else {
                val decoded = String(
                    Base64.decode(body, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP)
                )
                val at = decoded.lastIndexOf('@')
                if (at < 0) return null
                val userInfo = decoded.substring(0, at)
                val colon = userInfo.indexOf(':')
                if (colon <= 0) return null
                method = userInfo.substring(0, colon)
                password = userInfo.substring(colon + 1)
                val hostPort = decoded.substring(at + 1)
                val hp = hostPort.split(':')
                if (hp.size < 2) return null
                host = hp[0]
                port = hp[1].toIntOrNull() ?: return null
            }
            if (method !in supportedShadowsocksMethods) {
                logCollector.warn(
                    TAG,
                    "Skipping Shadowsocks profile with unsupported cipher",
                    mapOf("method" to method, "server" to host, "reason" to "unsupported_by_bundled_libbox")
                )
                return null
            }
            SubscriptionParser.ParsedConfig(
                uuid = password,
                address = host,
                port = port,
                transport = "shadowsocks",
                security = method,
                sni = null,
                path = null,
                host = null,
                mode = null,
                fingerprint = null,
                publicKey = null,
                shortId = null,
                flow = null,
                name = name.ifBlank { "SS $host" },
                providerTag = providerTag,
                password = password
            ).takeIf { it.isValid() }
        } catch (e: Exception) {
            logCollector.debug(TAG, "Failed to parse ss URI: ${e.message}")
            null
        }
    }

    fun parseVmess(
        uri: String,
        providerTag: String,
        logCollector: LogCollector
    ): SubscriptionParser.ParsedConfig? {
        return try {
            val b64 = uri.removePrefix("vmess://")
            val jsonText = String(
                Base64.decode(b64, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP)
            )
            val obj = JSONObject(jsonText)
            val address = obj.optString("add").ifBlank { obj.optString("host") }
            val port = obj.optInt("port", 0)
            val uuid = obj.optString("id")
            if (address.isBlank() || port <= 0 || uuid.isBlank()) return null
            val net = obj.optString("net", "tcp").ifBlank { "tcp" }
            val tls = obj.optString("tls", "")
            val security = if (tls.equals("tls", true) || tls == "1") "tls" else "none"
            val name = obj.optString("ps").ifBlank { "VMess $address" }
            SubscriptionParser.ParsedConfig(
                uuid = uuid,
                address = address,
                port = port,
                transport = "vmess",
                security = security,
                sni = obj.optString("sni").ifBlank {
                    obj.optString("host").takeIf { it.isNotBlank() }
                },
                path = obj.optString("path").takeIf { it.isNotBlank() },
                host = obj.optString("host").takeIf { it.isNotBlank() },
                mode = net,
                fingerprint = obj.optString("fp").takeIf { it.isNotBlank() },
                publicKey = null,
                shortId = null,
                flow = null,
                name = name,
                providerTag = providerTag
            ).takeIf { it.isValid() }
        } catch (e: Exception) {
            logCollector.debug(TAG, "Failed to parse vmess URI: ${e.message}")
            null
        }
    }

    fun parseTrojan(
        uri: String,
        providerTag: String,
        logCollector: LogCollector
    ): SubscriptionParser.ParsedConfig? {
        return try {
            val parsed = URI(uri)
            val password = parsed.userInfo ?: return null
            val host = parsed.host ?: return null
            val port = if (parsed.port > 0) parsed.port else 443
            val query = parsed.rawQuery.orEmpty().split("&").filter { it.isNotBlank() }.associate {
                val i = it.indexOf('=')
                if (i < 0) it to ""
                else URLDecoder.decode(it.substring(0, i), "UTF-8") to
                    URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }
            val name = parsed.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "Trojan $host"
            SubscriptionParser.ParsedConfig(
                uuid = password,
                address = host,
                port = port,
                transport = "trojan",
                security = "tls",
                sni = query["sni"] ?: query["peer"],
                path = query["path"],
                host = query["host"],
                mode = query["type"]?.takeIf { it.isNotBlank() && it != "trojan" },
                fingerprint = query["fp"],
                publicKey = null,
                shortId = null,
                flow = null,
                name = name,
                providerTag = providerTag,
                password = password,
                insecure = query["allowInsecure"] == "1" || query["insecure"] == "1"
            ).takeIf { it.isValid() }
        } catch (e: Exception) {
            logCollector.debug(TAG, "Failed to parse trojan URI: ${e.message}")
            null
        }
    }

    fun parseSocks(
        uri: String,
        providerTag: String,
        logCollector: LogCollector
    ): SubscriptionParser.ParsedConfig? {
        return try {
            val normalized = uri.replaceFirst("socks5://", "socks://")
            val parsed = URI(normalized)
            val host = parsed.host ?: return null
            val port = if (parsed.port > 0) parsed.port else 1080
            val userInfo = parsed.userInfo
            val user = userInfo?.substringBefore(':')
            val pass = userInfo?.substringAfter(':', missingDelimiterValue = "")
            val name = parsed.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "SOCKS $host"
            SubscriptionParser.ParsedConfig(
                uuid = user ?: "",
                address = host,
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
                password = pass
            ).takeIf { it.isValid() }
        } catch (e: Exception) {
            logCollector.debug(TAG, "Failed to parse socks URI: ${e.message}")
            null
        }
    }
}
