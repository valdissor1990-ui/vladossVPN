package com.vpnproxy.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubReleaseTest {

    @Test
    fun extractsVersionCodeUsingReleaseWorkflowFormula() {
        assertEquals(11797, release("v1.0.797").extractVersionCode())
        assertEquals(11101, release("v1.1.1").extractVersionCode())
        assertEquals(12000, release("v2.0.0").extractVersionCode())
    }

    @Test
    fun acceptsTagWithoutVPrefix() {
        assertEquals(11797, release("1.0.797").extractVersionCode())
    }

    @Test
    fun rejectsMalformedOrNonPositiveTags() {
        assertEquals(0, release("latest").extractVersionCode())
        assertEquals(0, release("v1.0").extractVersionCode())
        assertEquals(11000, release("v1.0.0").extractVersionCode())
        assertEquals(0, release("v1.x.797").extractVersionCode())
    }

    private fun release(tag: String) = GitHubRelease(tagName = tag)

    @Test
    fun findApkAssetMatchesRequestedFlavor() {
        val release = GitHubRelease(
            tagName = "v1.0.0",
            assets = listOf(
                GitHubRelease.Asset("app-play-1.0.0-release.apk", "https://example/play.apk"),
                GitHubRelease.Asset("app-direct-1.0.0-release.apk", "https://example/direct.apk")
            )
        )
        assertEquals("app-direct-1.0.0-release.apk", release.findApkAsset("direct")?.name)
        assertEquals("app-play-1.0.0-release.apk", release.findApkAsset("play")?.name)
    }

    @Test
    fun findApkAssetDoesNotFallBackToOtherFlavor() {
        val playOnly = GitHubRelease(
            tagName = "v1.0.0",
            assets = listOf(GitHubRelease.Asset("app-play-1.0.0-release.apk", "https://example/play.apk"))
        )
        assertNull("direct build must not pick up the play APK", playOnly.findApkAsset("direct"))

        val noApk = GitHubRelease(
            tagName = "v1.0.0",
            assets = listOf(GitHubRelease.Asset("version.json", "https://example/version.json"))
        )
        assertNull(noApk.findApkAsset("direct"))
    }
}
