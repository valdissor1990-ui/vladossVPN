# Protocol wiring status (1.0.775)

## Production path (SubscriptionRepository → SubscriptionParser)

Supported URI schemes after this release:

| Scheme | Status |
|--------|--------|
| vless:// | production |
| hysteria2:// / hy2:// | production |
| tuic:// | production |
| ss:// | production (new) |
| vmess:// | production (new) |
| trojan:// | production (new) |
| socks:// / socks5:// | production (new) |
| Clash YAML / JSON | existing (mostly vless) |

## Parallel package `core/protocols/`

Model + parsers (VLESS/VMESS/SS/Trojan/SOCKS/HTTP) exist for the future migration.
They are **not** yet the SubscriptionRepository input type (`ParsedConfig` types differ).
Do not delete; next step is gradual migration once sing-box outbound builders map the new model.

## Out of production path for now

WIREGUARD, ANYTLS, SHADOWTLS, NAIVE, SSH — only in `ProtocolKind` enum.
