package com.vpnproxy.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the exact pure policy called by UpdateInstaller. */
class ApkSignerCompatibilityTest {
    @Test
    fun rejectsMissingInstalledSigner() {
        assertFalse(signerSetsCompatible(emptySet(), setOf("a")))
    }

    @Test
    fun rejectsMissingArchiveSignerHistory() {
        assertFalse(signerSetsCompatible(setOf("a"), emptySet()))
    }

    @Test
    fun acceptsSameSingleSigner() {
        assertTrue(signerSetsCompatible(setOf("a"), setOf("a")))
    }

    @Test
    fun acceptsSingleSignerInDeclaredRotationLineage() {
        assertTrue(signerSetsCompatible(setOf("old"), setOf("old", "new")))
    }

    @Test
    fun rejectsUnrelatedSingleSignerLineage() {
        assertFalse(signerSetsCompatible(setOf("installed"), setOf("unrelated", "new")))
    }

    @Test
    fun requiresExactMatchForMultipleSigners() {
        assertTrue(signerSetsCompatible(setOf("a", "b"), setOf("a", "b")))
        assertFalse(signerSetsCompatible(setOf("a", "b"), setOf("a", "b", "c")))
        assertFalse(signerSetsCompatible(setOf("a", "b"), setOf("a", "c")))
    }
}
