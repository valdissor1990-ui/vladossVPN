package com.vpnproxy.core.durability

import com.vpnproxy.core.singbox.DurabilityOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BypassProfileTest {

    @Test
    fun normalIsTheGoldenPath() {
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
        assertEquals("chrome", options.utlsFingerprint)
    }

    @Test
    fun detectMatchesAppliedPreset() {
        BypassProfile.entries.forEach { profile ->
            val options = profile.applyTo(DurabilityOptions())
            assertEquals(profile, BypassProfile.detect(options))
        }
    }

    @Test
    fun detectTreatsRecordFragmentOnlyAsHardNetwork() {
        val options = DurabilityOptions(tlsRecordFragmentEnabled = true)

        assertEquals(BypassProfile.HARD_NETWORK, BypassProfile.detect(options))
    }
}
