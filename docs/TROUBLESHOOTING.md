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
3. Проверьте валидность URI/подписки: адрес, порт, UUID или пароль, SNI и параметры TLS/Reality.
4. Убедитесь, что `CommandServer.startOrReloadService()` не возвращает ошибку конфигурации.

## VLESS/Reality не устанавливает соединение

Проверьте соответствие `pbk` (public key), `sid` (short ID), `sni` и `flow`. Для Vision обычно используется `xtls-rprx-vision`; значение flow должно совпадать с настройками сервера.

## Hysteria2 / TUIC не передают трафик

Проверьте пароль, SNI, ALPN, obfs-параметры Hysteria2 и `congestion_control` / `udp_relay_mode` для TUIC. Проверяйте реальный HTTPS-запрос и UDP-трафик, а не только факт старта ядра.

## Split tunneling работает не так, как ожидалось

- **INCLUDE_SELECTED:** в VPN направляются только выбранные установленные приложения.
- **EXCLUDE_SELECTED:** выбранные приложения обходят VPN, остальные приложения используют VPN.
- Само VPN-приложение исключается из пользовательского списка, чтобы соединения управления не зацикливались.
- Android не позволяет одновременно задать allow-list и deny-list на одном `VpnService.Builder`; противоречивые правила должны приводить к диагностируемой ошибке.

## Deep Probe

Deep Probe нельзя запускать одновременно с активным VPN. Он использует временный proxy-only Sing-box в том же процессе и должен останавливать его после завершения измерений.

## Проверка APK

После `./gradlew assembleDirectRelease` проверьте содержимое APK: в нём должен быть libbox для нужных ABI и не должно быть библиотек Xray/HEV. Реальные VLESS/Reality, Hysteria2, TUIC, UDP и split tunneling требуют дополнительной проверки на устройстве.
