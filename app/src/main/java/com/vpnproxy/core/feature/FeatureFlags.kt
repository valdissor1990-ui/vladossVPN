package com.vpnproxy.core.feature

/**
 * Compile-time switches for the breakthrough program.
 *
 * Risky features default to `false` and stay disabled until their wave passes
 * audit and device checks. A disabled feature must not change the golden
 * connect path in any way.
 */
object FeatureFlags {
    /** Service reachability screen and the Doctor's service step. */
    const val SERVICE_CHECK_ENABLED = true

    /** Connection Doctor screen and safe diagnostic steps. Enabled after wave 1c audit. */
    const val DOCTOR_ENABLED = false

    /** Adaptive DPI bypass presets and orchestrator. Enabled after wave 1b audit. */
    const val BYPASS_AUTOPILOT_ENABLED = false

    /** One-click migration from other clients. */
    const val MIGRATION_ENABLED = false

    /** Privacy report screen. */
    const val PRIVACY_REPORT_ENABLED = false

    /** Local proxy gateway for TV and laptop. */
    const val LOCAL_GATEWAY_ENABLED = false

    /** Usage profiles (streaming, work, privacy, bypass). */
    const val USAGE_PROFILES_ENABLED = false
}
