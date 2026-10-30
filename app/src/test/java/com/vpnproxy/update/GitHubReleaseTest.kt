package com.vpnproxy.update

import org.junit.Assert.assertEquals
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
        assertEquals(0, release("v1.0.0").extractVersionCode())
        assertEquals(0, release("v1.x.797").extractVersionCode())
    }

    private fun release(tag: String) = GitHubRelease(tagName = tag)
}
