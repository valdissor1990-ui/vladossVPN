package com.vpnproxy.core.singbox

import android.content.Context

/**
 * Optional transport-durability features that harden a connection against DPI and server
 * churn **without changing the default (golden) connect path**. Every flag defaults to off,
 * so a release build behaves exactly as before until the user explicitly enables a feature.
 *
 * The knobs implemented here map to native sing-box capabilities:
 * - [nativeUrlTestEnabled] packs the top ranked standbys into an in-core `urltest` group so
 *   the core can fail over between servers without an app-level reconnect.
 * - [tlsFragmentEnabled] / [tlsRecordFragmentEnabled] split the TLS ClientHello / records,
 *   which defeats SNI-based and length-based blocking that plain TLS sessions hit in RU.
 * - [utlsFingerprint] overrides the uTLS ClientHello fingerprint used by TLS outbounds.
 */
data class DurabilityOptions(
    val nativeUrlTestEnabled: Boolean = false,
    val urlTestStandbyCount: Int = DEFAULT_STANDBY_COUNT,
    val tlsFragmentEnabled: Boolean = false,
    val tlsRecordFragmentEnabled: Boolean = false,
    val utlsFingerprint: String? = null
) {
    companion object {
        const val DEFAULT_STANDBY_COUNT = 2
        const val MAX_STANDBY_COUNT = 4

        /** Prefer 90s when urltest standbys are enabled — less radio than 45s. */
        const val URL_TEST_INTERVAL = "90s"
        const val URL_TEST_TOLERANCE_MS = 80
        const val URL_TEST_PROBE_URL = "https://www.gstatic.com/generate_204"
    }
}

/**
 * Persistence for [DurabilityOptions].
 *
 * User-facing durability knobs are read in every build: the plan's bypass autopilot must
 * work for ordinary users, not only in debug. Safety comes from the defaults — every flag
 * is `false`, so an untouched install produces exactly the golden config. Enabling a knob
 * is an explicit user action in the durability section and takes effect on the next core
 * start, because [com.vpnproxy.service.VpnProxyService] reads these options once at launch.
 */
object DurabilitySettings {
    private const val PREFS = "durability_settings"

    val utlsFingerprints = listOf("chrome", "firefox", "edge", "safari", "ios", "android", "random", "randomized")

    fun load(context: Context): DurabilityOptions {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fingerprint = prefs.getString("utls_fingerprint", null)?.takeIf { it in utlsFingerprints }
        return DurabilityOptions(
            nativeUrlTestEnabled = prefs.getBoolean("native_url_test", false),
            urlTestStandbyCount = prefs.getInt("url_test_standby_count", DurabilityOptions.DEFAULT_STANDBY_COUNT)
                .coerceIn(0, DurabilityOptions.MAX_STANDBY_COUNT),
            tlsFragmentEnabled = prefs.getBoolean("tls_fragment", false),
            tlsRecordFragmentEnabled = prefs.getBoolean("tls_record_fragment", false),
            utlsFingerprint = fingerprint
        )
    }

    fun save(context: Context, options: DurabilityOptions) {
        val fingerprint = options.utlsFingerprint?.takeIf { it in utlsFingerprints }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("native_url_test", options.nativeUrlTestEnabled)
            .putInt("url_test_standby_count", options.urlTestStandbyCount.coerceIn(0, DurabilityOptions.MAX_STANDBY_COUNT))
            .putBoolean("tls_fragment", options.tlsFragmentEnabled)
            .putBoolean("tls_record_fragment", options.tlsRecordFragmentEnabled)
            .putString("utls_fingerprint", fingerprint)
            .apply()
    }
}
