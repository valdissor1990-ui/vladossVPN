package com.vpnproxy.smartconnect

/**
 * Stable, collision-resistant identity for a server entry.
 *
 * SHA-1 is used only to produce a compact UI/cache key; it is not used for
 * cryptographic security. The server tuple is still deduplicated at its source.
 */
object ServerMetrics {
    fun serverKey(address: String, port: Int, uuid: String): String {
        val raw = "$address|$port|$uuid"
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-1")
            md.digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } catch (_: Exception) {
            raw.hashCode().toString(16) + "_" + raw.length
        }
    }
}
