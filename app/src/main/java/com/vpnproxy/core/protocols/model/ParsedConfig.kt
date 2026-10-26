package com.vpnproxy.core.protocols.model

sealed class ParsedConfig {
    abstract val protocol: ProtocolKind
    abstract val server: String
    abstract val port: Int
    abstract val name: String
    abstract val providerTag: String
    abstract val remark: String?
    abstract val connectionKey: String
    abstract val extra: Map<String, String>
}
