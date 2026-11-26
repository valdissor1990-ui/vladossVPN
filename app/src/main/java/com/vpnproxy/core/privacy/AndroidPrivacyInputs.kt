package com.vpnproxy.core.privacy

import android.content.Context
import com.vpnproxy.BuildConfig
import com.vpnproxy.core.singbox.RoutingPreset
import com.vpnproxy.core.singbox.RoutingSettings
import com.vpnproxy.core.singbox.RuleSetStore
import com.vpnproxy.subscription.SubscriptionRepository

/**
 * The single production mapping from real app state to [PrivacyInputs].
 *
 * Every value is read from the same source that drives behaviour:
 * - updates: `BuildConfig.ENABLE_SELF_UPDATE` of the current flavor (no separate toggle);
 * - device metadata: `SubscriptionRepository.deviceMetadataConsent` (default off);
 * - rule-sets: the routing preset and the local files in `RuleSetStore`.
 *
 * When the «Приватность» screen is wired, it must call this provider instead of building
 * [PrivacyInputs] by hand, otherwise the report can drift from what the app actually does.
 */
class AndroidPrivacyInputsProvider(
    private val context: Context,
    private val subscriptions: SubscriptionRepository
) {
    fun read(): PrivacyInputs = PrivacyInputs(
        selfUpdateEnabled = BuildConfig.ENABLE_SELF_UPDATE,
        deviceMetadataConsent = subscriptions.deviceMetadataConsent.value,
        ruRuleSetsRequested = RoutingSettings.preset(context) == RoutingPreset.BYPASS_RU,
        ruRuleSetsReady = RuleSetStore.isReady(context)
    )
}
