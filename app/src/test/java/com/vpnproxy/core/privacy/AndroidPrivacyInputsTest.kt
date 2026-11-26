package com.vpnproxy.core.privacy

import com.vpnproxy.core.singbox.RoutingPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure flavor mapping without Android. The provider only reads the sources and
 * delegates to [mapPrivacyInputs], so this is the whole decision logic.
 */
class AndroidPrivacyInputsTest {

    private fun map(
        preset: RoutingPreset = RoutingPreset.BYPASS_LAN,
        selfUpdate: Boolean = false,
        consent: Boolean = false,
        rulesReady: Boolean = false,
        battery: Boolean = false,
        installAllowed: Boolean = false
    ) = mapPrivacyInputs(
        selfUpdateEnabled = selfUpdate,
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
            selfUpdate = true,
            consent = true,
            rulesReady = true,
            battery = true,
            installAllowed = true
        )
        assertTrue(inputs.selfUpdateEnabled)
        assertTrue(inputs.deviceMetadataConsent)
        assertTrue(inputs.ruRuleSetsRequested)
        assertTrue(inputs.ruRuleSetsReady)
        assertTrue(inputs.batteryExemptionOffered)
        assertTrue(inputs.installPackagesAllowed)
    }

    @Test
    fun playFlavorDropsBatteryAndInstallPermissions() {
        // The play flavor sets ENABLE_BATTERY_EXEMPTION=false and IS_PLAY_STORE=true, so the
        // provider passes false/false here.
        val play = map(battery = false, installAllowed = false)
        assertFalse(play.batteryExemptionOffered)
        assertFalse(play.installPackagesAllowed)

        val direct = map(battery = true, installAllowed = true)
        assertTrue(direct.batteryExemptionOffered)
        assertTrue(direct.installPackagesAllowed)
    }

    @Test
    fun reportWarnsOnlyWhenRuleSetsAreRequestedButNotReady() {
        val builder = PrivacyReportBuilder()
        assertTrue(builder.build(map(preset = RoutingPreset.BYPASS_RU, rulesReady = true)).warnings.isEmpty())
        assertEquals(1, builder.build(map(preset = RoutingPreset.BYPASS_RU, rulesReady = false)).warnings.size)
        assertTrue(builder.build(map(preset = RoutingPreset.BYPASS_LAN, rulesReady = false)).warnings.isEmpty())
    }
}
