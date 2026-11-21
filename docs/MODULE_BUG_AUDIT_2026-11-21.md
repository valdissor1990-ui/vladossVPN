# Аудит багов по модулям (2026-11-21)

Ветка-источник: `integration/full-vpn-client-final` (точка `8e2145f3`).
Область: модули **вне** `ConnectionCoordinator.kt` и `TrafficValidator.kt`
(по ним разбор был отдельным и уже исправлен в PR #280). Изменений кода
приложения здесь нет — только фиксация находок.

Метки серьёзности: **P1** — может ломать работу/крашить; **P2** — заметный
functional/UX-дефект; **P3** — латентно/редко.

## Подтверждённые находки

### 1. [P2] Ленивая инициализация зашифрованных настроек в `SubscriptionRepository`
`app/src/main/java/com/vpnproxy/subscription/SubscriptionRepository.kt:99,103`

`masterKey` и `encryptedPrefs` создаются эагерно в конструкторе `@Singleton`
(`MasterKey.Builder(...).build()`, `EncryptedSharedPreferences.create(...)`) без
`try/catch`. Если keystore недоступен/повреждён, конструктор бросает исключение
и падает Hilt-инъекция (в т.ч. в `BootReceiver`). Парный
`JsonConfigRepository` сделан ленивым и «никогда не бросает» — здесь защита
отсутствует.

**Правка:** сделать `encryptedPrefs` ленивым (`by lazy`) с `try/catch` → `null`,
обращения проверять на `null`; при недоступности — не падать, а работать в
деградированном режиме.

### 2. [P2] `removeSubscription` не чистит пул профилей и кэш провайдера
`app/src/main/java/com/vpnproxy/subscription/SubscriptionRepository.kt:300`

Удаляется только запись из `_subscriptions`; `_allConfigs` не пересобирается,
per-subscription кэш (`__subscription_body_*` / `__subscription_etag_*`) не
удаляется. Серверы удалённой подписки продолжают участвовать в подключении до
следующего полного `refreshAll`, а тела конфигов остаются зашифрованы на диске.
`removeBundledSubscriptions` эту очистку делает корректно.

**Правка:** пересобрать `_allConfigs` без `providerTag` удалённой подписки,
вызвать `removeSubscriptionBodyCache(url)` и `saveConfigsToCache(...)`.

### 3. [P2] Утечка `HomeViewModel`/Activity через `registerSelectionCancel`
`app/src/main/java/com/vpnproxy/ui/home/HomeViewModel.kt:142,151,406`

В `init` регистрируется колбэк в синглтоне `ConnectionCoordinator`
(`registerSelectionCancel`), хранимый полем синглтона. `onCleared()` не
переопределён, поэтому колбэк не снимается. Лямбда захватывает `HomeViewModel`,
а тот через `vpnPermissionRequester` косвенно держит Activity.

**Правка:** добавить `override fun onCleared()` с
`connectionCoordinator.registerSelectionCancel(null)` и сбросом
`vpnPermissionRequester = null`.

### 4. [P2] Мёртвая ветка постинкционного ранжирования в `HomeViewModel`
`app/src/main/java/com/vpnproxy/ui/home/HomeViewModel.kt:145,199-202`

`pendingRankingConfigs` только сбрасывается в `emptyList()` (199, 219, 695) и
нигде не заполняется непустым списком, поэтому условие
`pendingRankingConfigs.takeIf { it.isNotEmpty() }` всегда ложно — фоновое
ранжирование после подключения по этому пути не запускается никогда.

**Правка:** либо заполнять список перед подключением, либо удалить ветку.

### 5. [P2] `JsonConfigRepository.prefsInitAttempted` не сбрасывается вне импорта
`app/src/main/java/com/vpnproxy/subscription/JsonConfigRepository.kt:64,84-89,162,199,214`

Флаг выставляется до попытки создания хранилища; при неудаче `prefs()` навсегда
возвращает `null`. Повторная попытка реализована только в `addFromJsonConfig`
(строка 162), но не в `remove()` (199) и `clear()` (214). После временного сбоя
keystore пользователь сможет импортировать JSON, но не удалить/очистить записи.

**Правка:** общий хелпер сброса/ретрая, вызывать во всех точках, где `prefs()`
вернул `null`.

### 6. [P2] `findApkAsset` скатывается к любому APK, ослабляя flavor-гейт
`app/src/main/java/com/vpnproxy/update/GitHubRelease.kt:25-27`

При отсутствии ассета запрошенного flavor возвращается любой `.apk`:
`assets.firstOrNull { it.name.contains(flavor) && endsWith(".apk") } ?: assets.firstOrNull { endsWith(".apk") }`.
Из-за этого фильтр `it.findApkAsset("direct") != null` в `UpdateChecker` почти
всегда истинен. Сборка `direct` может скачать `...play` APK (и наоборот),
после чего установка отклонится по несовпадению `packageName`.

**Правка:** убрать fallback или возвращать `null`, если ассет нужного flavor
отсутствует.

### 7. [P2, латентно] `direct_udp_1111` использует DNS-порт 1111
`app/src/main/java/com/vpnproxy/core/singbox/SingboxConfigBuilder.kt:84`

`dnsServer("udp", "direct-dns", "1.1.1.1", 1111)` — для UDP-DNS порт должен быть
53 (как в соседнем `direct_udp_8888`, где порт не задан). Сейчас режим
недостижим: `DiagnosticSettings.dnsModes` его не содержит. Ошибка «выстрелит»,
если режим вернут в UI.

**Правка:** убрать явный порт (→ 53).

### 8. [P2] Изменяемые поля `AutoSwitcher` без синхронизации
`app/src/main/java/com/vpnproxy/smartconnect/AutoSwitcher.kt:63-65`

`consecutiveErrors`, `baselineRtt`, `previousRtt` — обычные `var`, изменяются из
health-check цикла, фонового ранжирования и вызывающих потоков. При этом
`state` (строка 67) помечен `@Volatile`. Возможны потерянные инкременты и
устаревшие чтения `baselineRtt` → искажение детекта деградации.

**Правка:** `@Volatile`/`AtomicInteger` и/или единый мьютекс для этих полей.

### 9. [P3] `TrojanUriParser.isDomainName` — удвоенные слэши в IPv4-регэкспе
`app/src/main/java/com/vpnproxy/core/protocols/parser/TrojanUriParser.kt:253-255`

`Regex("""\\d{1,3}(\\.\\d{1,3}){3}""")` в raw-строке даёт движку `\\d`
(литерал `\` + `d`), поэтому IPv4-литерал не матчится, `isDomainName("1.2.3.4")`
→ `true`, и предупреждение о выводе SNI (строка 84) не выдаётся.
Пакет `core/protocols/parser` в production не используется (только тесты) —
влияние латентное.

**Правка:** `"""\d{1,3}(\.\d{1,3}){3}"""`.

### 10. [P3] `parseTrojan`: `insecure` распознаёт только `"1"`
`app/src/main/java/com/vpnproxy/subscription/ExtraUriParsers.kt:221`

`query["allowinsecure"] == "1" || query["insecure"] == "1"` — в отличие от
`SubscriptionParser.isTruthy()`, не принимает `true/yes/on`. При
`allowInsecure=true` флаг не выставится, TLS-проверка останется включённой.

**Правка:** использовать общий `isTruthy()` для обоих ключей.

## Ложные срабатывания (проверено, багов нет)

- **`StringIteratorArray.len()` возвращает `0`** (`SingBoxPlatform.kt:677-681`) —
  **не баг**. Это ровно повторяет официальную реализацию `StringArray` из
  `sing-box-for-android`, где `len()` возвращает `0` с комментарием
  «`// not used by core`». Go-сторона конвертирует итератор через
  `iteratorToArray` (цикл по `HasNext()`/`Next()`), `len()` не используется.

## Признанные чистыми модули

- `ui/subscriptions/SubscriptionsViewModel.kt`, `ui/settings/SettingsViewModel.kt`,
  `ui/update/UpdateViewModel.kt`.
- `smartconnect/SwitchDecisionEngine.kt` (`@Synchronized`), `NetworkBucketProvider.kt`,
  `BatteryPolicy.kt`, `ProbeEvidence.kt`, `HealthMonitor.kt`.
- `core/singbox/DiagnosticProbe.kt`, `CorePortLease.kt`, `LifecycleGenerationGate.kt`,
  `DiagnosticSettings.kt`.
- `update/UpdateManager.kt`, `UpdateDownloader.kt`, `UpdateInstaller.kt`,
  `UpdateChecker.kt`, `PendingUpdateStore.kt`, `UpdateCheckWorker.kt` — обработка
  `CancellationException`, `use/close`, проверки подписи APK, таймауты корректны
  (кроме п.6 в `GitHubRelease.kt`).

## Связанные изменения

- Разбор `ConnectionCoordinator` / `TrafficValidator`: мёртвый код
  (`trafficFatalMessage`, `fallbackIndex`), дедупликация
  `isProfileConfigurationError`, исправление классификации `ENETUNREACH` в
  `TrafficValidator.mapException` + юнит-тест — вошли в PR #280 (ветка
  `chore/deps-upgrade-latest-stable`).
- `docs/DEPENDENCY_UPGRADE_2026-11.md` — обновление тулчейна/ядра.

## Статус исправлений (ветка `fix/module-audit-findings-2026-11-21`)

Все 10 подтверждённых находок закрыты:

| № | Уровень | Правка |
|---|---------|--------|
| 1 | P2 | `SubscriptionRepository.encryptedPrefs` сделан `by lazy` с `runCatching` → `null`; все обращения проверяют `null` (деградация вместо падения Hilt-инъекции). |
| 2 | P2 | `removeSubscription` чистит `__subscription_body_*` / `__subscription_etag_*` удалённого провайдера и пересобирает `_allConfigs` + кэш без его `providerTag`. |
| 3 | P2 | Добавлен `HomeViewModel.onCleared()`: `registerSelectionCancel(null)` и сброс `vpnPermissionRequester`. |
| 4 | P2 | Удалена мёртвая ветка `pendingRankingConfigs` (поле и три обращения). |
| 5 | P2 | Общий `retryPrefsInitIfNeeded()` вызывается в `load()`, `remove()` и `clear()`. |
| 6 | P2 | Убран fallback на любой `.apk` в `findApkAsset`. |
| 7 | P2 | `direct_udp_1111` без явного порта (sing-box использует 53). |
| 8 | P2 | `AutoSwitcher.consecutiveErrors` → `AtomicInteger`; `baselineRtt` / `previousRtt` → `@Volatile`. |
| 9 | P3 | Исправлен IPv4-регэксп в `TrojanUriParser.isDomainName` (убраны двойные слэши). |
| 10 | P3 | `ExtraUriParsers.isTruthy()` для `allowinsecure` / `insecure`. |
