package com.vpnproxy.subscription

import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionDiffTest {

    private fun config(
        uuid: String = "11111111-1111-1111-1111-111111111111",
        address: String = "edge.example.com",
        port: Int = 443,
        transport: String = "xhttp",
        security: String = "reality",
        sni: String? = "edge.example.com",
        path: String? = "/xhttp",
        host: String? = "edge.example.com",
        name: String = "Node",
        providerTag: String = "Provider",
        publicKey: String? = "public-key",
        shortId: String? = "abcd"
    ) = ParsedConfig(
        uuid = uuid,
        address = address,
        port = port,
        transport = transport,
        security = security,
        sni = sni,
        path = path,
        host = host,
        mode = null,
        fingerprint = null,
        publicKey = publicKey,
        shortId = shortId,
        flow = null,
        name = name,
        providerTag = providerTag
    )

    @Test
    fun `first snapshot reports additions but is flagged as first snapshot`() {
        val result = SubscriptionDiff.compute(emptyList(), listOf(config(name = "A"), config(name = "B", port = 8443)))

        assertEquals(2, result.added.size)
        assertTrue(result.changed.isEmpty())
        assertTrue(result.removed.isEmpty())
        assertTrue(result.isFirstSnapshot)
        assertTrue(result.hasChanges)
    }

    @Test
    fun `identical snapshot has no changes`() {
        val profiles = listOf(config(name = "A"), config(name = "B", port = 8443))

        val result = SubscriptionDiff.compute(profiles, profiles.toList())

        assertFalse(result.hasChanges)
        assertEquals(2, result.unchangedCount)
        assertEquals(2, result.currentCount)
        assertFalse(result.isFirstSnapshot)
    }

    @Test
    fun `new profile is reported as added`() {
        val previous = listOf(config(name = "A"))
        val current = previous + config(name = "B", address = "new.example.com")

        val result = SubscriptionDiff.compute(previous, current)

        assertEquals(listOf("B"), result.added.map { it.displayName })
        assertTrue(result.changed.isEmpty())
        assertTrue(result.removed.isEmpty())
        assertEquals(1, result.unchangedCount)
    }

    @Test
    fun `missing profile is reported as removed`() {
        val removedProfile = config(name = "B", address = "old.example.com")
        val previous = listOf(config(name = "A"), removedProfile)

        val result = SubscriptionDiff.compute(previous, listOf(config(name = "A")))

        assertEquals(listOf("B"), result.removed.map { it.displayName })
        assertTrue(result.added.isEmpty())
        assertTrue(result.changed.isEmpty())
    }

    @Test
    fun `changed transport parameter is reported as changed not add plus remove`() {
        val previous = listOf(config(name = "A", path = "/old"))
        val current = listOf(config(name = "A", path = "/new"))

        val result = SubscriptionDiff.compute(previous, current)

        assertEquals(listOf("A"), result.changed.map { it.displayName })
        assertTrue(result.added.isEmpty())
        assertTrue(result.removed.isEmpty())
        assertEquals(0, result.unchangedCount)
    }

    @Test
    fun `rotated credential is a different profile`() {
        val previous = listOf(config(name = "A", uuid = "aaaaaaaa-1111-1111-1111-111111111111"))
        val current = listOf(config(name = "A", uuid = "bbbbbbbb-2222-2222-2222-222222222222"))

        val result = SubscriptionDiff.compute(previous, current)

        assertEquals(1, result.added.size)
        assertEquals(1, result.removed.size)
        assertTrue(result.changed.isEmpty())
    }

    @Test
    fun `same endpoint under different providers is tracked separately`() {
        val previous = listOf(config(name = "A", providerTag = "P1"))
        val current = listOf(config(name = "A", providerTag = "P1"), config(name = "A", providerTag = "P2"))

        val result = SubscriptionDiff.compute(previous, current)

        assertEquals(1, result.added.size)
        assertEquals("P2", result.added.single().providerTag)
        assertEquals(1, result.unchangedCount)
    }

    @Test
    fun `duplicate profiles are counted once`() {
        val profile = config(name = "A")
        val previous = listOf(profile)
        val current = listOf(profile, profile.copy(name = "A-copy"))

        val result = SubscriptionDiff.compute(previous, current)

        // Same provider and connection key: the repository deduplicates it, so the
        // second copy must not be reported as a new profile.
        assertEquals(1, result.unchangedCount)
        assertFalse(result.hasChanges)
        assertEquals(1, result.currentCount)
    }

    @Test
    fun `diff never exposes credentials or raw endpoints when a name exists`() {
        val uuid = "deadbeef-0000-1111-2222-333344445555"
        val previous = listOf(config(name = "Named node", uuid = uuid))
        val current = listOf(config(name = "Named node", uuid = uuid, path = "/changed"))

        val result = SubscriptionDiff.compute(previous, current)
        val entry = result.changed.single()

        assertFalse(entry.displayName.contains(uuid))
        assertFalse(entry.displayName.contains("/changed"))
        assertEquals("Named node", entry.displayName)
    }

    @Test
    fun `unnamed profile falls back to the visible endpoint`() {
        val previous = emptyList<ParsedConfig>()
        val current = listOf(config(name = "", address = "edge.example.com", port = 443))

        val result = SubscriptionDiff.compute(previous, current)

        assertEquals("edge.example.com:443", result.added.single().displayName)
    }
}
