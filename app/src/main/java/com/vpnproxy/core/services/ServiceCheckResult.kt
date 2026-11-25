package com.vpnproxy.core.services

/** Outcome of one control request. */
enum class ServiceCheckStatus {
    /** A control request through the tunnel returned a 2xx/3xx response. */
    REACHABLE,

    /** The tunnel returned an HTTP error for the control request. */
    UNREACHABLE,

    /** The control request failed at the network layer; not proof of blocking. */
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
