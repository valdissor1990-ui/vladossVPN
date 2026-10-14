# Архитектура VPNProxy

## Слои

```
┌─────────────────────────────────────────┐
│  Presentation (Compose + ViewModel)     │
├─────────────────────────────────────────┤
│  Domain (UseCases, Models)              │
├─────────────────────────────────────────┤
│  Data (Repositories, Parsers, Network)  │
├─────────────────────────────────────────┤
│  Core (Xray, Tunnel, TrafficStats)      │
└─────────────────────────────────────────┘
```

## Ключевые компоненты

| Компонент | Ответственность |
|-----------|----------------|
| `XrayCore` | JNI-обёртка над libxray.so, старт/стоп/статистика |
| `TunnelManager` | Создание TUN, запуск tun2socks, связка с VpnService |
| `TrafficStatsCollector` | Сбор Rx/Tx из TrafficStats + Xray Stats API, Flow 1Hz |
| `SubscriptionParser` | Парсинг vless://, base64, JSON, Clash YAML |
| `HealthChecker` | Параллельный latency-тест (Semaphore 10), speed-тест |
| `ScoringEngine` | Скоринг: 0.6·latency + 0.3·speed + 0.1·success |
| `AutoSwitcher` | Фоновый health-check 60с, автосвитч при 3 ошибках |
| `LogCollector` | Ring buffer 5000, Flow<List<LogEntry>>, Timber Tree |
| `VpnProxyService` | Foreground Service, persistent notification |

## Поток данных туннеля

```
Приложение → kernel → TUN (VpnService) → hev-socks5-tunnel → 127.0.0.1:2081 (Xray SOCKS-in) → VLESS/XHTTP/Reality → VPS → Internet
```

## DI (Hilt)

Все компоненты — `@Singleton`, инжектятся через `@Inject constructor`. ViewModel — `@HiltViewModel`.

## Рекомендации по безопасности

- Kill Switch: `setUnderlyingNetworks(null)` + `addDisallowedApplication` для системных
- IPv6 блокировка: `addRoute("::", 0)` + DNS только через туннель
- EncryptedSharedPreferences для URL подписок и токенов
- Маскировка UUID/ключей в логах через `SensitiveMasker`
