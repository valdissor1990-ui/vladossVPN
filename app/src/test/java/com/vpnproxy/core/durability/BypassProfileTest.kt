package com.vpnproxy.core.durability

import com.vpnproxy.core.singbox.DurabilityOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BypassProfileTest {

    @Test
    fun normalIsTheGoldenTransportPath() {
        val options = BypassProfile.NORMAL.applyTo(DurabilityOptions(nativeUrlTestEnabled = true))

        assertFalse(options.tlsFragmentEnabled)
        assertFalse(options.tlsRecordFragmentEnabled)
        assertNull(options.utlsFingerprint)
        // Unrelated options must survive a preset change.
        assertTrue(options.nativeUrlTestEnabled)
    }

    @Test
    fun hardNetworkFragmentsTlsWithoutFingerprint() {
        val options = BypassProfile.HARD_NETWORK.applyTo(DurabilityOptions())

        assertTrue(options.tlsFragmentEnabled)
        assertFalse(options.tlsRecordFragmentEnabled)
        assertNull(options.utlsFingerprint)
    }

    @Test
    fun maximumStealthEnablesBothFragmentsAndFingerprint() {
        val options = BypassProfile.MAXIMUM_STEALTH.applyTo(DurabilityOptions())

        assertTrue(options.tlsFragmentEnabled)
        assertTrue(options.tlsRecordFragmentEnabled)
        assertEquals(BypassProfile.STEALTH_FINGERPRINT, options.utlsFingerprint)
    }

    @Test
    fun detectMatchesEverySelectablePreset() {
        BypassProfile.entries.filter { it.selectable }.forEach { profile ->
            val options = profile.applyTo(DurabilityOptions())
            assertEquals(profile, BypassProfile.detect(options))
        }
    }

    @Test
    fun recordFragmentOnlyIsCustom() {
        val options = DurabilityOptions(tlsRecordFragmentEnabled = true)

        assertEquals(BypassProfile.CUSTOM, BypassProfile.detect(options))
    }

    @Test
    fun fingerprintWithoutFragmentationIsCustom() {
        val options = DurabilityOptions(utlsFingerprint = "chrome")

        assertEquals(BypassProfile.CUSTOM, BypassProfile.detect(options))
    }

    @Test
    fun unexpectedFingerprintIsCustom() {
        val options = DurabilityOptions(
            tlsFragmentEnabled = true,
            tlsRecordFragmentEnabled = true,
            utlsFingerprint = "safari"
        )

        assertEquals(BypassProfile.CUSTOM, BypassProfile.detect(options))
    }

    @Test
    fun customPresetDoesNotRewriteOptions() {
        val options = DurabilityOptions(utlsFingerprint = "safari")

        assertEquals(options, BypassProfile.CUSTOM.applyTo(options))
        assertFalse(BypassProfile.CUSTOM.selectable)
    }

    @Test
    fun normalClearsFingerprintLeftByCustomSetup() {
        val custom = DurabilityOptions(tlsFragmentEnabled = true, utlsFingerprint = "safari")

        val normal = BypassProfile.NORMAL.applyTo(custom)

        assertFalse(normal.tlsFragmentEnabled)
        assertNull(normal.utlsFingerprint)
    }
}
