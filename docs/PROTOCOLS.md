# Протоколы и конфигурация

## Поддерживаемые протоколы

- **VLESS + Reality:** UUID, SNI, fingerprint, public key (`pbk`), short ID (`sid`) и flow.
- **VLESS transport:** TCP, WebSocket, gRPC, HTTP, HTTPUpgrade, QUIC и XHTTP (XHTTP предоставляется форком sing-box-lx 1.14.3-lx.21).
- **Hysteria2:** пароль, TLS/SNI, ALPN и obfs Salamander.
- **TUIC:** UUID, пароль, TLS/SNI, ALPN, `congestion_control` и `udp_relay_mode=native`.

## XHTTP через sing-box-lx

Приложение использует полный `libbox.aar` из `Leadaxe/sing-box-lx` версии `1.14.3-lx.21`, собранный с `with_xhttp`. Builder создаёт `transport.type=xhttp` и передаёт `host`, `path`, `mode`; поля JSON из URI-параметра `extra` разворачиваются в поддерживаемые XHTTP options. TLS/Reality остаётся на уровне outbound `tls`. Релиз AAR фиксируется SHA-256 в `scripts/download_libbox.sh`.

## VLESS + Reality: поля URI

| URI parameter | Sing-box JSON |
|---|---|
| `pbk` | `tls.reality.public_key` |
| `sid` | `tls.reality.short_id` |
| `sni` | `tls.server_name` |
| `fp` | `tls.utls.fingerprint` |
| `flow` | outbound `flow` (например, `xtls-rprx-vision`) |

Reality + Vision flow подходит для поддерживаемого TCP-сценария. Не задавайте Vision flow для транспорта, который его не поддерживает.
