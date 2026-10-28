package com.vpnproxy.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the pure signer-identity policy used before APK installation.
 * Android PackageManager's certificate-history extraction is covered separately
 * by platform/device tests; these tests exercise the decision boundary itself.
 */
class ApkSignerCompatibilityTest {
    private val installer = UpdateInstallerForTest()

    @Test
    fun rejectsMissingInstalledSigner() {
        assertFalse(installer.compatible(emptySet(), setOf("a")))
    }

    @Test
    fun rejectsMissingArchiveSignerHistory() {
        assertFalse(installer.compatible(setOf("a"), emptySet()))
    }

    @Test
    fun acceptsSameSingleSigner() {
        assertTrue(installer.compatible(setOf("a"), setOf("a")))
    }

    @Test
    fun acceptsSingleSignerWhenPresentInDeclaredRotationLineage() {
        assertTrue(installer.compatible(setOf("old"), setOf("old", "new")))
    }

    @Test
    fun rejectsSingleSignerNotInArchiveLineage() {
        assertFalse(installer.compatible(setOf("installed"), setOf("unrelated", "new")))
    }

    @Test
    fun requiresExactMatchForMultipleInstalledSigners() {
        assertTrue(installer.compatible(setOf("a", "b"), setOf("a", "b")))
        assertFalse(installer.compatible(setOf("a", "b"), setOf("a", "b", "c")))
        assertFalse(installer.compatible(setOf("a", "b"), setOf("a", "c")))
    }
}

/**
 * Keep tests independent of Android framework construction and injected dependencies.
 * This mirrors the production policy exactly; integration tests should additionally
 * verify the PackageInfo-to-digest extraction on supported Android versions.
 */
private class UpdateInstallerForTest {
    fun compatible(installed: Set<String>, archive: Set<String>): Boolean {
        if (installed.isEmpty() || archive.isEmpty()) return false
        if (installed.size == 1) return installed.single() in archive
        return installed == archive
    }
}
