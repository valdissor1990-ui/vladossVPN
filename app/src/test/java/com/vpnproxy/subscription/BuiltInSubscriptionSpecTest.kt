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
    fun includesTheThreeRequestedMobileFeeds() {
        val sources = SubscriptionRepository.BUILT_IN_SUBSCRIPTIONS.associateBy { it.id }

        assertEquals(
            "https://final-sorted-base64.solovyov-jenya2004.workers.dev/",
            sources[SubscriptionRepository.SOLOVYOV_BASE64_ID]?.url
        )
        assertEquals(
            "https://random.solovyov-jenya2004.workers.dev/",
            sources[SubscriptionRepository.SOLOVYOV_RANDOM_ID]?.url
        )
        assertEquals(
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
            sources[SubscriptionRepository.IGARECK_ID]?.url
        )
    }

    @Test
    fun oldDefaultAndWhiteLiteFeedsAreNoLongerBundled() {
        val urls = SubscriptionRepository.BUILT_IN_SUBSCRIPTIONS.map { it.url }.toSet()
        val ids = SubscriptionRepository.BUILT_IN_SUBSCRIPTIONS.map { it.id }.toSet()

        assertTrue("https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_universal.txt" !in urls)
        assertTrue("https://raw.githubusercontent.com/FLAT447/v2ray-lists/main/WHITE_LITE.txt" !in urls)
        assertTrue("default-zieng2" !in ids)
        assertTrue("builtin-flat447-white-lite" !in ids)
    }
}
