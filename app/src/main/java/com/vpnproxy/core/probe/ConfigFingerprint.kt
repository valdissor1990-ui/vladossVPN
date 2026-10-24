package com.vpnproxy.core.probe

import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Stable, non-reversible fingerprint of the current canonical connection profile.
 * connectionKey() intentionally excludes display name/provider attribution.
 *
 * IMPORTANT: this digest is safe to persist instead of the raw key, but its field set must
 * stay aligned with SingboxConfigBuilder.buildOutbound(). Review that mapping when outbound
 * fields are added. Never log connectionKey(), because it contains credentials.
 */
fun ParsedConfig.configFingerprint(): String {
    val rawIdentity = connectionKey()
    return MessageDigest.getInstance("SHA-256")
        .digest(rawIdentity.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
