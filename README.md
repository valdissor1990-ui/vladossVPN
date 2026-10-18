# VPNProxy

Android VPN-клиент на едином ядре **Sing-box / libbox 1.14.3** с VLESS/Reality (TCP, WS, gRPC, HTTPUpgrade), Hysteria2, TUIC и автоматическим выбором сервера.

## Возможности

- **Протоколы:** VLESS + Reality (включая Vision flow и поддерживаемые upstream транспорты), Hysteria2 и TUIC
- **Ограничение:** upstream libbox 1.14.3 не поддерживает XHTTP; такие URI не понижаются молча до TCP
- **Единый процесс:** libbox запускается внутри процесса приложения; отдельного процесса `:singbox` нет
- **TUN:** создаётся через `VpnService.Builder` из `PlatformInterface.openTun()`
- **Split Tunneling:** режимы INCLUDE_SELECTED и EXCLUDE_SELECTED с исключением самого VPN-приложения
- **UDP:** native UDP relay и маршрутизация через TUN
- **Умный автовыбор:** latency/speed probes, скоринг и автопереключение
- **Подписки:** base64, JSON, Clash YAML и URI `vless://`, `hysteria2://`, `hy2://`, `tuic://`
- **Безопасность:** EncryptedSharedPreferences, Android Keystore и маскировка UUID/ключей

## Быстрый старт

```bash
git clone https://github.com/valdissor1990-ui/vladossVPN.git
cd vladossVPN
chmod +x scripts/download_libbox.sh
./scripts/download_libbox.sh
./gradlew assembleDirectRelease
```

Полный `libbox.aar` скачивается скриптом из официального релиза 1.14.3; Go bridge-классы не удаляются. APK: `app/build/outputs/apk/direct/release/`.

## Архитектура

- `core/singbox/SingBoxCore.kt` — жизненный цикл `CommandServer` в основном процессе.
- `core/singbox/SingBoxPlatform.kt` — `PlatformInterface`, создание/закрытие TUN, маршруты, DNS и защита сокетов.
- `core/singbox/SingboxConfigBuilder.kt` — outbounds VLESS/Reality, Hysteria2, TUIC и VPN/probe-конфигурации.
- `core/splittunnel/SplitTunnelManager.kt` — per-app policy в том же `VpnService.Builder`.
- `service/VpnProxyService.kt` — foreground VPN service и сетевой reconnect.
- `smartconnect/DeepProbeEngine.kt` — временный proxy-only Sing-box для измерений.

## Документация

- [Архитектура](docs/ARCHITECTURE.md)
- [Протоколы](docs/PROTOCOLS.md)
- [Диагностика](docs/TROUBLESHOOTING.md)

## Disclaimer

Приложение предназначено для обеспечения приватности и обхода сетевых ограничений. Пользователь несёт ответственность за соблюдение местного законодательства.

## Лицензия

GPL-3.0.
