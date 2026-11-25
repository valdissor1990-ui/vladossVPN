package com.vpnproxy.core.doctor

import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig

/**
 * Pure applicability rules for the transport probes.
 *
 * A probe that cannot possibly succeed must not report `FAIL`: a QUIC profile has no TCP
 * endpoint, and a profile without TLS has no handshake. The Doctor turns a non-null reason
 * into `WARN`, never into a false diagnosis.
 */
internal object DoctorProbeRules {

    private val QUIC_TRANSPORTS = setOf("hysteria2", "hy2", "tuic")
    private val TLS_SECURITY = setOf("tls", "reality")

    fun tcpSkipReason(config: ParsedConfig): String? =
        if (config.transport.lowercase() in QUIC_TRANSPORTS) {
            "TCP-проба не требуется: профиль работает по UDP."
        } else {
            null
        }

    fun tlsSkipReason(config: ParsedConfig): String? {
        val transport = config.transport.lowercase()
        if (transport in QUIC_TRANSPORTS) {
            return "TLS-рукопожатие не проверяется: профиль работает по UDP."
        }
        if (config.security.lowercase() !in TLS_SECURITY) {
            return "TLS-рукопожатие не проверяется: в профиле нет TLS."
        }
        return null
    }
}
