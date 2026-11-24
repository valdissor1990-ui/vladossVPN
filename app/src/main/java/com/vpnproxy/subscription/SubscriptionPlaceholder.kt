package com.vpnproxy.subscription

import java.net.InetAddress
import java.net.URI
import java.util.Base64
import java.util.Locale

/**
 * Device-limit and expired-subscription stubs point every profile at a loopback or
 * unspecified address. Importing them replaces a working server list with entries
 * that can never connect, so they are rejected before they reach the cache or UI.
 */
internal fun isPlaceholderAddress(value: String): Boolean {
    val host = value.trim().removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
    if (host.isEmpty()) return false
    if (host == "localhost" || host.endsWith(".localhost")) return true
    if (host.contains(':')) {
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return address.isLoopbackAddress || address.isAnyLocalAddress
    }
    val parts = host.split('.')
    if (parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) {
        val first = parts[0].toInt()
        return first == 0 || first == 127
    }
    return false
}

/**
 * True when the whole payload is a provider stub: known wording, or every URI host is a
 * placeholder. Base64 payloads are decoded with both the standard and URL-safe alphabets.
 */
internal fun looksLikeDeviceLimitStub(body: String): Boolean = runCatching {
    val compact = body.filterNot { it.isWhitespace() }
    val decoded = if (compact.contains("://")) body else decodeBase64OrSelf(compact) ?: body
    val lower = decoded.lowercase(Locale.ROOT)
    if (STUB_MARKERS.any(lower::contains)) return@runCatching true
    val hosts = decoded.lineSequence()
        .map { it.trim() }
        .filter { it.contains("://") }
        .mapNotNull { line -> runCatching { URI(line.substringBefore('#')).host }.getOrNull() }
        .toList()
    hosts.isNotEmpty() && hosts.all(::isPlaceholderAddress)
}.getOrDefault(false)

private val STUB_MARKERS = listOf(
    "лимит устройств", "device limit", "max devices", "too many devices"
)

private fun decodeBase64OrSelf(input: String): String? = runCatching {
    String(Base64.getDecoder().decode(input), Charsets.UTF_8)
}.recoverCatching {
    String(Base64.getUrlDecoder().decode(input), Charsets.UTF_8)
}.getOrNull()
