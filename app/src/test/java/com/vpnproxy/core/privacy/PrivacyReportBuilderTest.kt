package com.vpnproxy.core.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyReportBuilderTest {

    private val builder = PrivacyReportBuilder()

    private fun PrivacyReport.fact(id: String): PrivacyFact =
        facts.first { it.id == id }

    private fun inputs(
        selfUpdate: Boolean = false,
        consent: Boolean = false,
        ruRequested: Boolean = false,
        ruReady: Boolean = false
    ) = PrivacyInputs(
        selfUpdateEnabled = selfUpdate,
        deviceMetadataConsent = consent,
        ruRuleSetsRequested = ruRequested,
        ruRuleSetsReady = ruReady
    )

    @Test
    fun reportFollowsTheRealSwitches() {
        val off = builder.build(inputs())
        assertFalse(off.fact("network-updates").dataLeavesDevice)
        assertFalse(off.fact("network-geo-rules").dataLeavesDevice)
        assertFalse(off.fact("identifier-hwid").dataLeavesDevice)
        assertFalse(off.fact("identifier-device-meta").dataLeavesDevice)

        val on = builder.build(inputs(selfUpdate = true, consent = true, ruRequested = true, ruReady = true))
        assertTrue(on.fact("network-updates").dataLeavesDevice)
        assertTrue(on.fact("network-geo-rules").dataLeavesDevice)
        assertTrue(on.fact("identifier-hwid").dataLeavesDevice)
        assertTrue(on.fact("identifier-device-meta").dataLeavesDevice)
    }

    @Test
    fun consentGatesEveryIdentifierFact() {
        val withoutConsent = builder.build(inputs(consent = false))
        listOf("identifier-hwid", "identifier-device-meta").forEach { id ->
            assertFalse(id, withoutConsent.fact(id).dataLeavesDevice)
            assertTrue(id, withoutConsent.fact(id).detail.contains("Не переда", ignoreCase = true))
        }

        val withConsent = builder.build(inputs(consent = true))
        listOf("identifier-hwid", "identifier-device-meta").forEach { id ->
            assertTrue(id, withConsent.fact(id).dataLeavesDevice)
        }
    }

    @Test
    fun requestedRuleSetsThatAreNotReadyProduceAWarning() {
        val pending = builder.build(inputs(ruRequested = true, ruReady = false))
        assertEquals(1, pending.warnings.size)
        assertTrue(pending.warnings.first().contains("не скачаны"))

        // The same request is no longer a warning once the files are on disk.
        assertTrue(builder.build(inputs(ruRequested = true, ruReady = true)).warnings.isEmpty())
        assertTrue(builder.build(inputs(ruRequested = false, ruReady = false)).warnings.isEmpty())
    }

    @Test
    fun everyClaimHasAUniqueIdAndAVerifiableSource() {
        val report = builder.build(inputs())
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
    fun everyManifestPermissionThatMattersIsListed() {
        val ids = builder.build(inputs()).facts
            .filter { it.kind == PrivacyKind.PERMISSION }
            .map { it.id }
            .toSet()
        // These were missing before and the report claimed to list the permissions.
        listOf(
            "perm-change-network",
            "perm-foreground-special",
            "perm-wake-lock",
            "perm-battery"
        ).forEach { assertTrue("missing $it", it in ids) }
    }

    @Test
    fun fixedPhrasesContainNoAddressesOrTokens() {
        // Guards future edits of the static text: the builder never interpolates user data,
        // so any address here would have been typed in by mistake.
        val report = builder.build(inputs(selfUpdate = true, consent = true, ruRequested = true))
        val text = report.facts.joinToString("\n") { "${it.title} ${it.detail} ${it.source.title}" }
        listOf(
            "http://", "https://", "www.", ".com", ".org", ".ru",
            "uuid", "token", "password", "passwd", "secret",
            "@", "127.0.0.1"
        ).forEach { forbidden ->
            assertFalse("report leaks '$forbidden'", text.contains(forbidden, ignoreCase = true))
        }
        assertFalse(Regex("""[0-9a-f]{32}""", RegexOption.IGNORE_CASE).containsMatchIn(text))
    }

    @Test
    fun optInFactsAreMarkedAndTelemetryIsOff() {
        val report = builder.build(inputs())
        assertTrue(report.fact("identifier-hwid").requiresOptIn)
        assertTrue(report.fact("identifier-device-meta").requiresOptIn)

        assertFalse(report.fact("telemetry-sdk").dataLeavesDevice)
        assertFalse(report.fact("telemetry-upload").dataLeavesDevice)
    }
}
