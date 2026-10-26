package com.vpnproxy.core.protocols.model

enum class ProtocolKind {
    VLESS,
    VMESS,
    TROJAN,
    SHADOWSOCKS,
    SOCKS,
    HTTP,
    HYSTERIA2,
    TUIC,
    WIREGUARD,
    ANYTLS,
    SHADOWTLS,
    NAIVE,
    SSH;

    companion object {
        fun connectionKey(
            protocol: ProtocolKind,
            server: String,
            port: Int,
            uuid: String? = null,
            password: String? = null,
            username: String? = null,
            method: String? = null,
            publicKey: String? = null,
            tlsKind: TlsKind = TlsKind.NONE,
            innerProtocol: String? = null,
            authMethod: String? = null
        ): String = when (protocol) {
            VLESS, VMESS ->
                "$server:$port:${requireNotNull(uuid)}"

            TROJAN, HYSTERIA2, ANYTLS ->
                "$server:$port:${requireNotNull(password)}"

            SHADOWSOCKS ->
                "$server:$port:${requireNotNull(method)}:${requireNotNull(password)}"

            SOCKS ->
                "$server:$port:${username.orEmpty()}:${password.orEmpty()}"

            HTTP ->
                "$server:$port:${username.orEmpty()}:${password.orEmpty()}:$tlsKind"

            TUIC ->
                "$server:$port:${requireNotNull(uuid)}"

            WIREGUARD ->
                "$server:$port:${requireNotNull(publicKey)}"

            SHADOWTLS ->
                "$server:$port:${requireNotNull(password)}:${innerProtocol.orEmpty()}"

            NAIVE ->
                "$server:$port:${username.orEmpty()}:${password.orEmpty()}"

            SSH ->
                "$server:$port:${requireNotNull(username)}:${authMethod.orEmpty()}"
        }
    }
}
