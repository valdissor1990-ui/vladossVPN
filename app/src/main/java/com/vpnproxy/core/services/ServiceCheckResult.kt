package com.vpnproxy.core.services

/** Outcome of one control request. */
enum class ServiceCheckStatus {
    /** The control request returned 2xx: the tunnel carried it end to end. */
    REACHABLE,

    /**
     * The server answered with an HTTP error (4xx/5xx). The tunnel works — the request
     * reached the target — but the target refused this specific request. Not a VPN failure.
     */
    ANSWERED,

    /**
     * No verdict: redirect, timeout, reset or another transport failure. The control request
     * proves nothing either way, so the UI must not claim the service is blocked.
     */
    UNKNOWN,

    /** VPN is not active, so no control request was attempted. */
    VPN_OFF,

    /** Core is starting or stopping; the local proxy port may be unavailable. */
    TRANSITION
}

/**
 * Privacy-safe result: carries the catalogue [serviceId], never the endpoint URL.
 */
data class ServiceCheckResult(
    val serviceId: String,
    val status: ServiceCheckStatus,
    val latencyMs: Long? = null,
    val httpCode: Int? = null,
    val reason: String? = null
)
