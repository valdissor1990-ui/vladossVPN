# Диагностика

## Сборка не находит libbox

Выполните:

```bash
chmod +x scripts/download_libbox.sh
./scripts/download_libbox.sh
```

Скрипт загружает полный официальный `libbox.aar` 1.14.3 и проверяет наличие `PlatformInterface` и Go bridge-классов. Не заменяйте AAR stripped-версией.

## VPN не запускается

1. Убедитесь, что Android выдал приложению VPN-разрешение.
2. Проверьте журналы `VpnProxyService`, `SingBoxCore` и `SingBoxPlatform`.
3. Проверьте URI/подписку: адрес, порт, UUID или пароль, SNI и параметры TLS/Reality.
4. Убедитесь, что `CommandServer.startOrReloadService()` не возвращает ошибку конфигурации.

## VLESS/Reality не устанавливает соединение

Проверьте соответствие `pbk` (public key), `sid` (short ID), `sni` и `flow`. Для Vision обычно используется `xtls-rprx-vision`; значение flow должно совпадать с сервером. Используется sing-box-lx 1.14.3-lx.21 с поддержкой XHTTP. Проверьте `transport.type=xhttp`, режим `auto`/ `packet-up`/ `stream-one`, а также поля `host`, `path` и `extra`.

## Hysteria2 / TUIC не передают трафик

Проверьте пароль, SNI, ALPN, obfs-параметры Hysteria2 и `congestion_control` / `udp_relay_mode` для TUIC. Проверяйте реальный HTTPS-запрос и UDP-трафик, а не только факт старта ядра.

## Split tunneling работает не так, как ожидалось

- **INCLUDE_SELECTED:** в VPN направляются только выбранные установленные приложения.
- **EXCLUDE_SELECTED:** выбранные приложения обходят VPN, остальные используют VPN.
- Само VPN-приложение исключается из пользовательского списка.
- Android не позволяет одновременно задать allow-list и deny-list на одном `VpnService.Builder`; конфликтующие правила должны приводить к диагностируемой ошибке.

## Deep Probe и APK

Deep Probe нельзя запускать одновременно с активным VPN. Он использует временный proxy-only Sing-box в том же процессе. После `./gradlew assembleDirectRelease` CI проверяет отсутствие старых native cores и отдельного процесса `:singbox`. Реальные протоколы, UDP и split tunneling всё равно требуют проверки на Android-устройстве.
