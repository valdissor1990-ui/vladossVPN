package com.vpnproxy.service

/**
 * Lifecycle state exposed by [VpnProxyService].
 * Separate from ConnectionState, which describes the coordinator's connection workflow.
 */
sealed class ServiceState {
    data object Idle : ServiceState()
    data object Starting : ServiceState()
    data object Active : ServiceState()
    data object Verifying : ServiceState()
    data object Stopping : ServiceState()
    data class Failed(val reason: String) : ServiceState()
}
