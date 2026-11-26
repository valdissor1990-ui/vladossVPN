package com.vpnproxy.core.privacy

import android.content.Context
import com.vpnproxy.BuildConfig
import com.vpnproxy.core.singbox.RoutingPreset
import com.vpnproxy.core.singbox.RoutingSettings
import com.vpnproxy.core.singbox.RuleSetStore
import com.vpnproxy.subscription.SubscriptionRepository

/**
 * Pure mapping from the individual sources to [PrivacyInputs].
 *
 * Extracted so a JVM test can cover every flavor combination without Android: the provider
 * below only reads the sources and delegates here.
 */
internal fun mapPrivacyInputs(
    selfUpdateBuildEnabled: Boolean,
    updateChecksEnabled: Boolean,
    deviceMetadataConsent: Boolean,
    preset: RoutingPreset,
    ruleSetsReady: Boolean,
    batteryExemptionEnabled: Boolean,
    installPackagesAllowed: Boolean
): PrivacyInputs = PrivacyInputs(
    selfUpdateBuildEnabled = selfUpdateBuildEnabled,
    updateChecksEnabled = updateChecksEnabled,
    deviceMetadataConsent = deviceMetadataConsent,
    ruRuleSetsRequested = preset == RoutingPreset.BYPASS_RU,
    ruRuleSetsReady = ruleSetsReady,
    batteryExemptionOffered = batteryExemptionEnabled,
    installPackagesAllowed = installPackagesAllowed
)

/**
 * The single production mapping from real app state to [PrivacyInputs].
 *
 * Every value is read from the same source that drives behaviour:
 * - updates: `BuildConfig.ENABLE_SELF_UPDATE` of the current flavor, combined with the
 *   user's `check_updates` toggle;
 * - device metadata: `SubscriptionRepository.deviceMetadataConsent` (default off);
 * - rule-sets: the routing preset and the validated files in `RuleSetStore`;
 * - battery exemption and APK install: the same flavor flags the manifest uses.
 *
 * The `check_updates` toggle lives in the settings DataStore, whose delegate is private to
 * the settings screen, so the screen passes its current value in. That keeps one store and
 * avoids a second `preferencesDataStore` delegate for the same file.
 *
 * When the «Приватность» screen is wired, it must call this provider instead of building
 * [PrivacyInputs] by hand, otherwise the report can drift from what the app actually does.
 */
class AndroidPrivacyInputsProvider(
    private val context: Context,
    private val subscriptions: SubscriptionRepository
) {
    fun read(checkUpdatesEnabled: Boolean): PrivacyInputs = mapPrivacyInputs(
        selfUpdateBuildEnabled = BuildConfig.ENABLE_SELF_UPDATE,
        updateChecksEnabled = checkUpdatesEnabled,
        deviceMetadataConsent = subscriptions.deviceMetadataConsent.value,
        preset = RoutingSettings.preset(context),
        ruleSetsReady = RuleSetStore.isReady(context),
        batteryExemptionEnabled = BuildConfig.ENABLE_BATTERY_EXEMPTION,
        installPackagesAllowed = !BuildConfig.IS_PLAY_STORE
    )
}
