package com.vpnproxy.core.doctor

/**
 * Snapshot of the app state the Doctor is allowed to know.
 *
 * The orchestrator never reads Android services directly, so applicability rules stay pure
 * and testable. [isolatedCoreConsent] is true only after the user explicitly agreed to stop
 * the tunnel for the `VPN_DOWN` steps.
 */
data class DoctorContext(
    val tunnelUp: Boolean,
    val coreTransitioning: Boolean = false,
    val isolatedCoreConsent: Boolean = false
)
