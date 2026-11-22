# Protocol wiring status (1.0.826+)

## Production path (SubscriptionRepository → SubscriptionParser)

| Input | Status |
|-------|--------|
| `vless://` | production |
| `hysteria2://` / `hy2://` | production |
| `tuic://` | production |
| `ss://` | production |
| `vmess://` | production |
| `trojan://` | production |
| `socks://` / `socks5://` | production |
| Clash / Clash.Meta YAML | production — `vless`, `vmess`, `trojan`, `ss`, `socks5`, `hysteria2`, `tuic` |
| sing-box JSON `outbounds[]` | production — same type set as Clash |
| Legacy Xray JSON | production — `vless` and Hysteria v2 |

Mapping rules live in two Android-free objects so they are unit tested on the JVM:

* `subscription/ClashProxyMapper.kt` — Clash `proxies[]` entry → `ParsedConfig`
* `subscription/SingBoxOutboundMapper.kt` — sing-box outbound object → `ParsedConfig`

Both normalize the Clash stream network onto a sing-box transport name (`h2` → `http`,
`splithttp` → `xhttp`). Unsupported networks are rejected **at import time** with the
reason `unsupported_transport` instead of producing a profile that fails at connect time.

`http` proxies, WireGuard, AnyTLS, ShadowTLS, Naive and SSH are not supported by the
bundled libbox outbound builder and are reported as `unsupported_protocol`.

## Removed: parallel `core/protocols/` package

The `com.vpnproxy.core.protocols` package (14 files, ~1860 lines) was a second,
never-wired parser model. It had zero production imports — only its own tests — and
diverged from the live parser, so it was deleted. The live path is
`SubscriptionRepository` → `subscription/SubscriptionParser` → `ParsedConfig` →
`SingboxConfigBuilder.buildOutbound`.
