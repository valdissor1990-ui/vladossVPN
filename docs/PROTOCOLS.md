# Протоколы и конфигурация

## Production path (парсер → SingboxConfigBuilder → libbox)

| Протокол | URI | Outbound |
|----------|-----|----------|
| VLESS (+ Reality, XHTTP, WS, gRPC, …) | `vless://` | да |
| Hysteria2 | `hysteria2://` / `hy2://` | да |
| TUIC | `tuic://` | да |
| Shadowsocks | `ss://` | да (1.0.776) |
| VMess | `vmess://` | да (1.0.776) |
| Trojan | `trojan://` | да (1.0.776) |
| SOCKS5 | `socks://` / `socks5://` | да (1.0.776) |

## Как кодируется протокол в `ParsedConfig`

- `transport = hysteria2 | tuic | shadowsocks | socks | trojan | vmess` — тип outbound
- для VLESS `transport` = сеть (`tcp`/`ws`/`xhttp`/…)
- для VMess/Trojan сеть лежит в `mode` (`tcp`/`ws`/…)
- Shadowsocks: `security` = method, `password`/`uuid` = пароль

## Пакет `core/protocols/`

Отдельная модель для будущей миграции. Сейчас production идёт через `subscription.SubscriptionParser` + `ExtraUriParsers`.

## XHTTP через sing-box-lx

Приложение использует полный `libbox.aar` из `Leadaxe/sing-box-lx` `1.14.3-lx.23` с `with_xhttp`.
