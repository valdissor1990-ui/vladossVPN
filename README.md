# VPNProxy

Production-ready Android VPN-клиент на VLESS + XHTTP + Reality с умным автовыбором серверов.

## Возможности

- **Протоколы:** VLESS + XHTTP (stream-one) + Reality, fallback TCP + Reality + Vision
- **Умный автовыбор:** параллельный latency-тест, speed-тест топ-5, скоринг, автосвитч
- **Мультиподписки:** base64, JSON, Clash YAML, `subscription-userinfo`
- **Журнал отладки:** ring buffer 5000 записей, фильтры, экспорт через MediaStore, маскировка чувствительных данных
- **Kill Switch:** блокировка трафика при разрыве туннеля, IPv6 egress заблокирован
- **UI:** Jetpack Compose Material 3, динамическая цветная строка состояния, Material You
- **Безопасность:** EncryptedSharedPreferences, Android Keystore, маскировка UUID/ключей

## Быстрый старт

```bash
git clone https://github.com/valdissor1990-ui/vladossVPN.git
cd vladossVPN
chmod +x scripts/download_xray.sh
./scripts/download_xray.sh
./gradlew assembleDirectRelease
```

APK: app/build/outputs/apk/direct/release/app-direct-release.apk

## Архитектура

```
app/src/main/java/com/vpnproxy/
├── core/
│   ├── xray/XrayCore.kt          # JNI-обёртка над libxray.so
│   ├── tunnel/TunnelManager.kt   # VpnService + tun2socks
│   ├── tunnel/TrafficStatsCollector.kt
│   └── tunnel/KillSwitch.kt
├── subscription/
│   ├── SubscriptionParser.kt     # vless://, base64, JSON, YAML
│   ├── SubscriptionRepository.kt
│   └── SubscriptionUpdater.kt    # WorkManager
├── smartconnect/
│   ├── HealthChecker.kt          # latency + speed
│   ├── ScoringEngine.kt
│   └── AutoSwitcher.kt
├── logging/
│   ├── LogCollector.kt           # ring buffer 5000
│   ├── LogExporter.kt            # MediaStore + FileProvider
│   └── SensitiveMasker.kt
├── ui/
│   ├── home/HomeScreen.kt
│   ├── home/StatusBar.kt         # ★ цветная строка состояния
│   ├── subscriptions/SubscriptionsScreen.kt
│   ├── servers/ServersListScreen.kt
│   ├── settings/SettingsScreen.kt
│   ├── debug/DebugScreen.kt
│   └── theme/
├── service/VpnProxyService.kt    # foreground service
└── di/
```

## Документация

· ARCHITECTURE.md
· PROTOCOLS.md
· TROUBLESHOOTING.md

## Ссылки

· Xray-core
· XHTTP specification
· REALITY

## Disclaimer

Приложение предназначено для обеспечения приватности и обхода несправедливых блокировок. Пользователь несёт ответственность за соблюдение местного законодательства.

## Лицензия

GPL-3.0 (совместимо с sing-box и hev-socks5-tunnel).
