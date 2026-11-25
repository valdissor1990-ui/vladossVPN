package com.vpnproxy.core.doctor

import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DoctorProbeRulesTest {

    private fun config(transport: String, security: String) = ParsedConfig(
        uuid = "11111111-1111-1111-1111-111111111111",
        address = "example.com",
        port = 443,
        transport = transport,
        security = security,
        sni = null,
        path = null,
        host = null,
        mode = null,
        fingerprint = null,
        publicKey = null,
        shortId = null,
        flow = null,
        name = "node"
    )

    @Test
    fun quicProfilesHaveNoTcpProbe() {
        listOf("hysteria2", "hy2", "tuic", "HYSTERIA2").forEach {
            assertNotNull(DoctorProbeRules.tcpSkipReason(config(it, "tls")))
        }
        assertNull(DoctorProbeRules.tcpSkipReason(config("tcp", "tls")))
    }

    @Test
    fun quicProfilesHaveNoPlainTlsProbe() {
        assertNotNull(DoctorProbeRules.tlsSkipReason(config("hysteria2", "tls")))
        assertNotNull(DoctorProbeRules.tlsSkipReason(config("tuic", "tls")))
    }

    @Test
    fun profilesWithoutTlsAreSkipped() {
        assertNotNull(DoctorProbeRules.tlsSkipReason(config("tcp", "none")))
        assertNotNull(DoctorProbeRules.tlsSkipReason(config("ws", "")))
        assertNull(DoctorProbeRules.tlsSkipReason(config("tcp", "tls")))
        assertNull(DoctorProbeRules.tlsSkipReason(config("tcp", "reality")))
        // Case must not matter: a profile with TLS in capitals is still testable.
        assertNull(DoctorProbeRules.tlsSkipReason(config("tcp", "TLS")))
        assertNull(DoctorProbeRules.tlsSkipReason(config("tcp", "Reality")))
    }

    @Test
    fun emptyTransportStillGetsTcpProbe() {
        // An empty transport means a plain TCP profile, so the probe is applicable.
        assertNull(DoctorProbeRules.tcpSkipReason(config("", "tls")))
        assertNull(DoctorProbeRules.tlsSkipReason(config("", "tls")))
    }

    @Test
    fun tcpProbeAppliesToNonQuicWithoutTls() {
        assertNull(DoctorProbeRules.tcpSkipReason(config("ws", "none")))
        assertNull(DoctorProbeRules.tcpSkipReason(config("grpc", "")))
    }
}
