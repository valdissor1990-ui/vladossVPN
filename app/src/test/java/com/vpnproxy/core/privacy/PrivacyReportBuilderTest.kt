package com.vpnproxy.core.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyReportBuilderTest {

    private val builder = PrivacyReportBuilder()

    private fun PrivacyReport.fact(id: String): PrivacyFact =
        facts.first { it.id == id }

    @Test
    fun reportFollowsRealFeatureSwitches() {
        val off = builder.build(PrivacySnapshot())
        assertFalse(off.fact("network-updates").dataLeavesDevice)
        assertFalse(off.fact("network-geo-rules").dataLeavesDevice)
        assertFalse(off.fact("identifier-hwid").dataLeavesDevice)
        assertFalse(off.fact("identifier-device-meta").dataLeavesDevice)

        val on = builder.build(
            PrivacySnapshot(
                selfUpdateEnabled = true,
                deviceMetadataConsent = true,
                hwidEnabled = true,
                geoRuleSetsEnabled = true
            )
        )
        assertTrue(on.fact("network-updates").dataLeavesDevice)
        assertTrue(on.fact("network-geo-rules").dataLeavesDevice)
        assertTrue(on.fact("identifier-hwid").dataLeavesDevice)
        assertTrue(on.fact("identifier-device-meta").dataLeavesDevice)
    }

    @Test
    fun hwidWithoutConsentIsReportedAsAWarning() {
        val report = builder.build(
            PrivacySnapshot(hwidEnabled = true, deviceMetadataConsent = false)
        )
        assertFalse(report.fact("identifier-hwid").dataLeavesDevice)
        assertEquals(1, report.warnings.size)
        assertTrue(report.warnings.first().contains("согласия"))
    }

    @Test
    fun everyClaimHasAUniqueIdAndAVerifiableSource() {
        val report = builder.build(PrivacySnapshot())
        val ids = report.facts.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(report.facts.size >= 10)
        report.facts.forEach { fact ->
            assertTrue("empty title for ${fact.id}", fact.title.isNotBlank())
            assertTrue("empty detail for ${fact.id}", fact.detail.isNotBlank())
            assertTrue("empty source for ${fact.id}", fact.source.title.isNotBlank())
        }
    }

    @Test
    fun reportContainsNoSecretsOrAddresses() {
        val report = builder.build(
            PrivacySnapshot(selfUpdateEnabled = true, deviceMetadataConsent = true, hwidEnabled = true)
        )
        val text = report.facts.joinToString("\n") { "${it.title} ${it.detail} ${it.source.title}" }
        listOf(
            "http://", "https://", "www.", ".com", ".org", ".ru",
            "uuid", "token", "password", "passwd", "secret",
            "@", "127.0.0.1"
        ).forEach { forbidden ->
            assertFalse("report leaks '$forbidden'", text.contains(forbidden, ignoreCase = true))
        }
        // Nothing shaped like a UUID or a long hex identifier.
        assertFalse(Regex("""[0-9a-f]{32}""", RegexOption.IGNORE_CASE).containsMatchIn(text))
    }

    @Test
    fun optInFactsAreMarkedAndTelemetryIsOff() {
        val report = builder.build(PrivacySnapshot())
        val hwid = report.fact("identifier-hwid")
        assertTrue(hwid.requiresOptIn)
        assertTrue(report.fact("identifier-device-meta").requiresOptIn)

        assertFalse(report.fact("telemetry-sdk").dataLeavesDevice)
        assertFalse(report.fact("telemetry-upload").dataLeavesDevice)
    }
}
