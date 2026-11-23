package com.vpnproxy.core.singbox

import android.content.Context
import com.vpnproxy.util.DebugFlags

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
 * Persistence for [DurabilityOptions]. Mirrors [DiagnosticSettings]: values only take effect
 * in diagnostic-enabled builds, so a production build keeps the golden defaults regardless
 * of what is stored on disk.
 */
object DurabilitySettings {
    private const val PREFS = "durability_settings"

    val utlsFingerprints = listOf("chrome", "firefox", "edge", "safari", "ios", "android", "random", "randomized")

    fun load(context: Context): DurabilityOptions {
        if (!DebugFlags.diagnosticEnabled()) return DurabilityOptions()
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
