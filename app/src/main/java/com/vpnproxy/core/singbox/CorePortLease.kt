package com.vpnproxy.core.singbox

import kotlinx.coroutines.sync.Mutex

/**
 * Process-wide ownership gate for the fixed local proxy port shared by the VPN core
 * and temporary proxy-only probes. Every core start/stop sequence must use this gate.
 */
object CorePortLease {
    val mutex = Mutex()
}
