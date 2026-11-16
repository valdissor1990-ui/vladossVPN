# Архитектура VPNProxy

## Основные компоненты

| Компонент | Ответственность |
|---|---|
| `VpnProxyService` | Android `VpnService`, foreground notification, reconnect/watchdog |
| `SingBoxCore` | Запуск и остановка libbox 1.14.3 через `CommandServer` / `CommandServerHandler` |
| `SingBoxPlatform` | Реализация `PlatformInterface`; `openTun()`, маршруты, DNS, split tunneling и `protect(fd)` |
| `SingboxConfigBuilder` | JSON-конфигурации VPN и временных proxy-only проверок |
| `SplitTunnelManager` | Применение INCLUDE_SELECTED / EXCLUDE_SELECTED через Android per-app VPN API |
| `HealthChecker` | Проверка доступности и скорости через Sing-box proxy |
| `DeepProbeEngine` | Loopback inbound на каждый сервер и измерение latency, jitter, loss и speed |

## Поток запуска VPN

```text
VpnProxyService
  -> SingboxConfigBuilder.buildVpn()
  -> SingBoxCore.start(configJson)
  -> CommandServer.startOrReloadService()
  -> libbox вызывает SingBoxPlatform.openTun(TunOptions)
  -> VpnService.Builder: addresses, DNS, routes, split tunneling
  -> establish() -> fd возвращается libbox
  -> Sing-box обрабатывает TCP и UDP через выбранный outbound
```

## Ограничения и гарантии

- Ядро запускается в основном процессе приложения; отдельного Android-сервиса и процесса `:singbox` нет.
- Используется полный `libbox.aar` версии 1.14.3, включая Go bridge-классы.
- TUN и его `ParcelFileDescriptor` принадлежат `SingBoxPlatform` и закрываются при остановке ядра.
- `autoDetectInterfaceControl(fd)` вызывает `VpnService.protect(fd)`, чтобы прямые соединения ядра не попадали обратно в TUN.
- Android per-app VPN API допускает allow-list или deny-list, но не обе одновременно. При конфликтующих правилах приложение должно сообщить об ошибке, а не молча менять политику.
- Deep Probe использует временный proxy-only `CommandServer` и не заменяет активную VPN-конфигурацию.
- Успешная сборка не заменяет проверку реального трафика на Android-устройстве, включая UDP и оба режима split tunneling.


## Поток данных подписки и профиля

```text
Subscription source
  -> SubscriptionRepository / SubscriptionParser
  -> ParsedConfig (in-memory model)
  -> encrypted persistence for user secrets
  -> ScoringEngine / DeepProbeEngine
  -> AutoSwitcher
  -> ConnectionCoordinator
  -> SingboxConfigBuilder
  -> SingBoxCore / libbox
```

Секреты не должны попадать в обычные логи, диагностические отчёты, BuildConfig или тестовые fixtures. Для хранения пользовательских учётных данных используйте EncryptedSharedPreferences и Android Keystore. См. [THREAT_MODEL.md](THREAT_MODEL.md).

## Жизненный цикл VPN-сервиса

1. Android запускает `VpnProxyService` как foreground service после пользовательского действия и получения VPN-разрешения.
2. Координатор проверяет допустимость перехода состояния и готовит профиль/конфигурацию.
3. `SingBoxCore` запускает `CommandServer`; libbox вызывает платформенные callbacks.
4. `SingBoxPlatform.openTun()` создаёт TUN через `VpnService.Builder.establish()` и передаёт файловый дескриптор ядру.
5. При остановке, ошибке или смене сети координатор управляет остановкой/перезапуском; TUN descriptor и временные ресурсы должны закрываться ровно один раз.
6. При уничтожении сервиса отменяются его coroutine scopes, callbacks и сетевые наблюдатели. Нельзя оставлять фоновые задачи, способные повторно запустить уже остановленное ядро.

## Границы тестирования

- JVM unit tests покрывают чистую логику скоринга, parser/import, конфигурационные контракты и политики reconnect.
- Проверки Android `VpnService`, реального TUN, системной маршрутизации и UDP требуют Android instrumentation/device test либо отдельного тестового стенда. Успешный JVM test не доказывает реальную маршрутизацию на устройстве.
- Проверки Compose должны использовать fake ViewModel/state и не запускать реальное VPN-подключение.
