package com.vpnproxy.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInSubscriptionSpecTest {
    @Test
    fun builtInSourcesHaveUniqueIdsAndUrls() {
        val sources = SubscriptionRepository.BUILT_IN_SUBSCRIPTIONS

        assertEquals(3, sources.size)
        assertEquals(sources.size, sources.map { it.id }.toSet().size)
        assertEquals(sources.size, sources.map { it.url }.toSet().size)
        assertTrue(sources.all { it.url.startsWith("https://") })
    }

    @Test
    fun includesDefaultIgaReckAndWhiteLiteSources() {
        val ids = SubscriptionRepository.BUILT_IN_SUBSCRIPTIONS.map { it.id }.toSet()

        assertTrue(SubscriptionRepository.DEFAULT_SUBSCRIPTION_ID in ids)
        assertTrue(SubscriptionRepository.IGARECK_ID in ids)
        assertTrue(SubscriptionRepository.WHITE_LITE_ID in ids)
    }
}
