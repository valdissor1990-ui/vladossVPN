package com.vpnproxy.core.privacy

import com.vpnproxy.core.singbox.RoutingPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure mapping from sources to [PrivacyInputs] without Android. The provider only
 * reads the sources and delegates to [mapPrivacyInputs].
 */
class AndroidPrivacyInputsTest {

    private fun map(
        preset: RoutingPreset = RoutingPreset.BYPASS_LAN,
        selfUpdateBuild: Boolean = false,
        updateChecks: Boolean = true,
        consent: Boolean = false,
        rulesReady: Boolean = false,
        battery: Boolean = false,
        installAllowed: Boolean = false
    ) = mapPrivacyInputs(
        selfUpdateBuildEnabled = selfUpdateBuild,
        updateChecksEnabled = updateChecks,
        deviceMetadataConsent = consent,
        preset = preset,
        ruleSetsReady = rulesReady,
        batteryExemptionEnabled = battery,
        installPackagesAllowed = installAllowed
    )

    @Test
    fun bypassRuRequestsRuleSetsButOtherPresetsDoNot() {
        assertTrue(map(preset = RoutingPreset.BYPASS_RU).ruRuleSetsRequested)
        assertFalse(map(preset = RoutingPreset.BYPASS_LAN).ruRuleSetsRequested)
        assertFalse(map(preset = RoutingPreset.ALL_VPN).ruRuleSetsRequested)
    }

    @Test
    fun everySourceIsCopied() {
        val inputs = map(
            preset = RoutingPreset.BYPASS_RU,
            selfUpdateBuild = true,
            updateChecks = true,
            consent = true,
            rulesReady = true
        )
        assertTrue(inputs.selfUpdateBuildEnabled)
        assertTrue(inputs.deviceMetadataConsent)
        assertTrue(inputs.ruRuleSetsRequested)
        assertTrue(inputs.ruRuleSetsReady)
    }

    @Test
    fun batteryAndInstallFlagsAreCopied() {
        // The provider passes BuildConfig.ENABLE_BATTERY_EXEMPTION and !IS_PLAY_STORE here.
        // The report itself only drops these permissions when the flags are false; see
        // PrivacyReportBuilderTest.flavorSpecificPermissionsFollowTheBuild.
        assertFalse(map(battery = false, installAllowed = false).batteryExemptionOffered)
        assertFalse(map(battery = false, installAllowed = false).installPackagesAllowed)
        assertTrue(map(battery = true, installAllowed = true).batteryExemptionOffered)
        assertTrue(map(battery = true, installAllowed = true).installPackagesAllowed)
    }

    @Test
    fun reportWarnsOnlyWhenRuleSetsAreRequestedButNotReady() {
        val builder = PrivacyReportBuilder()
        assertTrue(builder.build(map(preset = RoutingPreset.BYPASS_RU, rulesReady = true)).warnings.isEmpty())
        assertEquals(1, builder.build(map(preset = RoutingPreset.BYPASS_RU, rulesReady = false)).warnings.size)
        assertTrue(builder.build(map(preset = RoutingPreset.BYPASS_LAN, rulesReady = false)).warnings.isEmpty())
    }
}
