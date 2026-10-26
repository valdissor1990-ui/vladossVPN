package com.vpnproxy.core.protocols.model

enum class TransportKind(val value: String) {
    TCP("tcp"),
    WS("ws"),
    GRPC("grpc"),
    HTTP("http"),
    HTTP_UPGRADE("httpupgrade"),
    XHTTP("xhttp"),
    QUIC("quic");

    companion object {
        fun fromString(s: String): TransportKind? =
            entries.find { it.value.equals(s, ignoreCase = true) }
    }
}
