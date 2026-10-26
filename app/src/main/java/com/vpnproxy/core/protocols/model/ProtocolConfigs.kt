package com.vpnproxy.core.protocols.model

data class TlsConfig(
    val serverName: String? = null,
    val insecure: Boolean = false,
    val alpn: List<String> = emptyList(),
    val utls: UtlsConfig? = null,
    val reality: RealityConfig? = null
)

data class UtlsConfig(
    val fingerprint: String
)

data class RealityConfig(
    val publicKey: String,
    val shortId: String? = null
)

data class TransportConfig(
    val type: TransportKind,
    val ws: WsConfig? = null,
    val grpc: GrpcConfig? = null,
    val http: HttpTransportConfig? = null,
    val httpUpgrade: HttpUpgradeConfig? = null,
    val xhttp: XhttpConfig? = null
)

data class WsConfig(
    val path: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val maxEarlyData: Int? = null,
    val earlyDataHeaderName: String? = null
)

data class GrpcConfig(
    val serviceName: String? = null
)

data class HttpTransportConfig(
    val host: String? = null,
    val path: String? = null,
    val method: String? = null,
    val headers: Map<String, String> = emptyMap()
)

data class HttpUpgradeConfig(
    val path: String? = null,
    val host: String? = null
)

data class XhttpConfig(
    val mode: String? = null,
    val path: String? = null,
    val host: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val maxEarlyData: Int? = null,
    val earlyDataHeaderName: String? = null,
    val extraOptions: Map<String, String> = emptyMap()
)

data class MultiplexConfig(
    val enabled: Boolean = true,
    val protocol: String = "h2mux",
    val maxConnections: Int? = null,
    val minStreams: Int? = null,
    val maxStreams: Int? = null,
    val padding: Boolean = false
)

data class VlessConfig(
    override val server: String,
    override val port: Int,
    override val name: String,
    override val providerTag: String,
    override val remark: String? = null,
    override val extra: Map<String, String> = emptyMap(),
    val uuid: String,
    val flow: String? = null,
    val tlsKind: TlsKind = TlsKind.NONE,
    val tls: TlsConfig? = null,
    val transport: TransportConfig? = null,
    val multiplex: MultiplexConfig? = null
) : ParsedConfig() {
    override val protocol: ProtocolKind = ProtocolKind.VLESS

    override val connectionKey: String =
        ProtocolKind.connectionKey(
            protocol = protocol,
            server = server,
            port = port,
            uuid = uuid
        )
}

data class VmessConfig(
    override val server: String,
    override val port: Int,
    override val name: String,
    override val providerTag: String,
    override val remark: String? = null,
    override val extra: Map<String, String> = emptyMap(),
    val uuid: String,
    val security: String = "auto",
    val alterId: Int = 0,
    val tlsKind: TlsKind = TlsKind.NONE,
    val tls: TlsConfig? = null,
    val transport: TransportConfig? = null,
    val multiplex: MultiplexConfig? = null
) : ParsedConfig() {
    override val protocol: ProtocolKind = ProtocolKind.VMESS

    override val connectionKey: String =
        ProtocolKind.connectionKey(
            protocol = protocol,
            server = server,
            port = port,
            uuid = uuid
        )
}

data class TrojanConfig(
    override val server: String,
    override val port: Int,
    override val name: String,
    override val providerTag: String,
    override val remark: String? = null,
    override val extra: Map<String, String> = emptyMap(),
    val password: String,
    val tls: TlsConfig,
    val tlsKind: TlsKind = TlsKind.TLS,
    val transport: TransportConfig? = null,
    val multiplex: MultiplexConfig? = null
) : ParsedConfig() {
    override val protocol: ProtocolKind = ProtocolKind.TROJAN

    override val connectionKey: String =
        ProtocolKind.connectionKey(
            protocol = protocol,
            server = server,
            port = port,
            password = password
        )
}

data class ShadowsocksConfig(
    override val server: String,
    override val port: Int,
    override val name: String,
    override val providerTag: String,
    override val remark: String? = null,
    override val extra: Map<String, String> = emptyMap(),
    val method: String,
    val password: String,
    val plugin: String? = null,
    val pluginOpts: String? = null,
    val multiplex: MultiplexConfig? = null
) : ParsedConfig() {
    override val protocol: ProtocolKind = ProtocolKind.SHADOWSOCKS

    override val connectionKey: String =
        ProtocolKind.connectionKey(
            protocol = protocol,
            server = server,
            port = port,
            method = method,
            password = password
        )
}

data class SocksConfig(
    override val server: String,
    override val port: Int,
    override val name: String,
    override val providerTag: String,
    override val remark: String? = null,
    override val extra: Map<String, String> = emptyMap(),
    val version: String = "5",
    val username: String? = null,
    val password: String? = null
) : ParsedConfig() {
    override val protocol: ProtocolKind = ProtocolKind.SOCKS

    override val connectionKey: String =
        ProtocolKind.connectionKey(
            protocol = protocol,
            server = server,
            port = port,
            username = username,
            password = password
        )
}

data class HttpConfig(
    override val server: String,
    override val port: Int,
    override val name: String,
    override val providerTag: String,
    override val remark: String? = null,
    override val extra: Map<String, String> = emptyMap(),
    val username: String? = null,
    val password: String? = null,
    val tls: TlsConfig? = null,
    val tlsKind: TlsKind = TlsKind.NONE
) : ParsedConfig() {
    override val protocol: ProtocolKind = ProtocolKind.HTTP

    override val connectionKey: String =
        ProtocolKind.connectionKey(
            protocol = protocol,
            server = server,
            port = port,
            username = username,
            password = password,
            tlsKind = tlsKind
        )
}

data class Hysteria2Config(
    override val server: String,
    override val port: Int,
    override val name: String,
    override val providerTag: String,
    override val remark: String? = null,
    override val extra: Map<String, String> = emptyMap(),
    val password: String
) : ParsedConfig() {
    override val protocol: ProtocolKind = ProtocolKind.HYSTERIA2

    override val connectionKey: String =
        ProtocolKind.connectionKey(
            protocol = protocol,
            server = server,
            port = port,
            password = password
        )
}

data class TuicConfig(
    override val server: String,
    override val port: Int,
    override val name: String,
    override val providerTag: String,
    override val remark: String? = null,
    override val extra: Map<String, String> = emptyMap(),
    val uuid: String,
    val password: String? = null
) : ParsedConfig() {
    override val protocol: ProtocolKind = ProtocolKind.TUIC

    override val connectionKey: String =
        ProtocolKind.connectionKey(
            protocol = protocol,
            server = server,
            port = port,
            uuid = uuid
        )
}

data class WireguardConfig(
    override val server: String,
    override val port: Int,
    override val name: String,
    override val providerTag: String,
    override val remark: String? = null,
    override val extra: Map<String, String> = emptyMap(),
    val publicKey: String
) : ParsedConfig() {
    override val protocol: ProtocolKind = ProtocolKind.WIREGUARD

    override val connectionKey: String =
        ProtocolKind.connectionKey(
            protocol = protocol,
            server = server,
            port = port,
            publicKey = publicKey
        )
}

data class AnyTlsConfig(
    override val server: String,
    override val port: Int,
    override val name: String,
    override val providerTag: String,
    override val remark: String? = null,
    override val extra: Map<String, String> = emptyMap(),
    val password: String
) : ParsedConfig() {
    override val protocol: ProtocolKind = ProtocolKind.ANYTLS

    override val connectionKey: String =
        ProtocolKind.connectionKey(
            protocol = protocol,
            server = server,
            port = port,
            password = password
        )
}

data class ShadowTlsConfig(
    override val server: String,
    override val port: Int,
    override val name: String,
    override val providerTag: String,
    override val remark: String? = null,
    override val extra: Map<String, String> = emptyMap(),
    val password: String,
    val innerProtocol: String? = null
) : ParsedConfig() {
    override val protocol: ProtocolKind = ProtocolKind.SHADOWTLS

    override val connectionKey: String =
        ProtocolKind.connectionKey(
            protocol = protocol,
            server = server,
            port = port,
            password = password,
            innerProtocol = innerProtocol
        )
}

data class NaiveConfig(
    override val server: String,
    override val port: Int,
    override val name: String,
    override val providerTag: String,
    override val remark: String? = null,
    override val extra: Map<String, String> = emptyMap(),
    val username: String? = null,
    val password: String? = null
) : ParsedConfig() {
    override val protocol: ProtocolKind = ProtocolKind.NAIVE

    override val connectionKey: String =
        ProtocolKind.connectionKey(
            protocol = protocol,
            server = server,
            port = port,
            username = username,
            password = password
        )
}

data class SshConfig(
    override val server: String,
    override val port: Int,
    override val name: String,
    override val providerTag: String,
    override val remark: String? = null,
    override val extra: Map<String, String> = emptyMap(),
    val username: String,
    val authMethod: String? = null
) : ParsedConfig() {
    override val protocol: ProtocolKind = ProtocolKind.SSH

    override val connectionKey: String =
        ProtocolKind.connectionKey(
            protocol = protocol,
            server = server,
            port = port,
            username = username,
            authMethod = authMethod
        )
}
