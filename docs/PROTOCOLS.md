# Протоколы и конфигурация

## Поддерживаемые протоколы

- **VLESS + Reality:** UUID, SNI, fingerprint, public key (`pbk`), short ID (`sid`) и flow.
- **VLESS transport:** TCP, WebSocket, gRPC, HTTP, HTTPUpgrade и QUIC — при условии совместимости транспорта с сервером и upstream libbox 1.14.3.
- **Hysteria2:** пароль, TLS/SNI, ALPN и obfs Salamander.
- **TUIC:** UUID, пароль, TLS/SNI, ALPN, `congestion_control` и `udp_relay_mode=native`.

## Ограничение XHTTP

Официальный upstream Sing-box/libbox 1.14.3 не включает XHTTP в список V2Ray transports. Поэтому конфигурация с `type=xhttp` явно отклоняется с диагностикой, а не превращается в TCP и не создаёт ложное впечатление совместимости. Если XHTTP обязателен, нужен отдельный XHTTP-enabled core/fork; это уже не официальный неизменённый libbox 1.14.3.

## VLESS + Reality: поля URI

| URI parameter | Sing-box JSON |
|---|---|
| `pbk` | `tls.reality.public_key` |
| `sid` | `tls.reality.short_id` |
| `sni` | `tls.server_name` |
| `fp` | `tls.utls.fingerprint` |
| `flow` | outbound `flow` (например, `xtls-rprx-vision`) |

Reality + Vision flow подходит для поддерживаемого TCP-сценария. Не задавайте Vision flow для транспорта, который его не поддерживает.
