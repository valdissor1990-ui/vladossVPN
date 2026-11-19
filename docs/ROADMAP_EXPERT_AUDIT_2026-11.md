# vladossVPN — единый план развития по результатам независимого аудита

Дата фиксации: 2026-11-19  
Основание: перекрёстный анализ заключений Gemini, DeepSeek и Grok, а также выборочная проверка исходников, PR и GitHub Actions.  
Статус: рабочий план; не является заявлением о прохождении runtime-проверок.

## Цель

Довести vladossVPN до предсказуемого novice-first Android VPN-клиента: пользователь импортирует личную подписку, получает обоснованный выбор сервера, стабильный туннель и понятное объяснение ошибок. Приоритет — безопасность и корректность реального трафика, а не количество функций.

## Правила выполнения

1. Не коммитить напрямую в main. Каждая функциональная задача — отдельная feature/fix-ветка и Draft PR.
2. Перед изменением изучить текущую реализацию, call sites, историю и связанные открытые PR. Не дублировать существующую работу.
3. Не сливать PR только на основании зелёного CI. Сначала проверить diff, конфликты, актуальный HEAD и последствия для релизных вариантов.
4. Не менять libbox.aar 1.14.3, его SHA-256, GPL-3.0-or-later и защищённый брендинг без отдельного обоснования и разрешения.
5. Не добавлять встроенные подписки или серверы. Не раскрывать UUID, пароли, ключи и URL подписок в логах.
6. Не добавлять и не запускать ручные тесты без отдельного разрешения пользователя. Уже настроенные GitHub Actions могут выполняться автоматически; результаты фиксировать честно.
7. Не объявлять runtime-стабильность, отсутствие утечек или совместимость OEM доказанными без фактических испытаний на устройствах.
8. Небольшие изолированные изменения; сохранять рабочее поведение и обратную совместимость настроек.

## Очерёдность и критерии завершения

### P0 — Сетевой lifecycle и восстановление

- [x] P0.1 Перекрёстно проверить PR #180 (очередь handover-событий), #173 (стабильность network buckets), #176 (сериализация coordinator), #169 (teardown сервиса); проверить актуальные HEAD, diff, конфликты и CI. Статический аудит выполнен 19.11.2026; все четыре PR остаются Draft.
- [ ] P0.2 Проверить, что событие смены физической сети не теряется во время busy/transition, а после перехода обрабатывается ровно один раз с актуальной сетью.
- [ ] P0.3 Проверить порядок start/stop/reconnect, отмену корутин, освобождение TUN/портов и защиту от параллельного запуска ядра.
- [ ] P0.4 Проверить поведение после уничтожения процесса, повторного запуска сервиса, reboot и Doze статическим анализом; runtime-пункты оставить открытыми до тестов на устройствах.
- [ ] P0.5 Зафиксировать таблицу состояний и инварианты ConnectionCoordinator/VpnProxyService/SingBoxCore.
- Критерий: нет очевидного пути потери handover-события или двойного запуска; CI PR зелёный; runtime-валидация отдельно обозначена.

### P1 — Импорт и управление подписками

- [x] P1.1 Перепроверить PR #175 и актуальные parser changes; сравнить оба SubscriptionParser и их ответственность.
- [x] P1.2 Спроектировать типизированный ImportReport: получено, распознано, отклонено, дубликаты, ошибки с безопасными причинами, распределение по протоколам.
- [ ] P1.3 Реализовать и проверить отчёт без хранения/логирования исходных секретов и URL подписок (первая реализация в PR #184; требуется аудит точности и CI).
- [ ] P1.4 Добавить diff обновления подписки: новые/изменённые/удалённые профили; сохранить пользовательские параметры.
- [ ] P1.5 Подготовить обезличенный набор fixtures для URI, Base64, JSON, Clash, VLESS gRPC/TLS, XHTTP, Hysteria2, TUIC и некорректных записей. Не включать личные ключи/подписки.
- Критерий: пользователь видит, что импортировано и почему запись не принята; ни один секрет не попадает в отчёт.

### P2 — Интегрированный Smart Autopilot

- [x] P2.1 Проследить реальные call sites ScoringEngine, DeepProbeEngine, ServerScanner и AutoSwitcher; документировать, какие метрики реально участвуют в каждом решении.
- [x] P2.2 Зафиксировать единый контракт измерений и скоринга; определить безопасное применение preflight, active health-check и deep diagnostic. Документ: `docs/SMART_AUTOPILOT_SCORING_CONTRACT.md`.
- [ ] P2.3 Разделить scoring history по physical network bucket и корректно привязать hard failures к network epoch (Draft PR #186; CI выполняется).
- [ ] P2.4 Реализовать типизированный ProbeResult/ServerQuality contract с latency, jitter, loss, throughput, traffic validation, confidence, timestamp и network identity.
- [ ] P2.5 Обеспечить, чтобы автоматический выбор учитывал только свежие результаты текущей сети; Wi-Fi и cellular не смешивать. Отличать исторический успех от измеренного ping (Draft PR #187; CI выполняется).
- [ ] P2.6 Проверить hysteresis, cooldown, quarantine и защиту от thrashing; показывать объяснение «почему выбран этот сервер».
- Критерий: каждый score имеет объяснимые исходные измерения; отсутствие метрики не подменяется фиктивным успехом.

### P3 — Безопасность и приватность

- [ ] P3.1 Перепроверить PR #179 (шифрование файлового кэша), #170 (безопасные логи), #181 (FileProvider), #182 (Play flavor).
- [ ] P3.2 Проследить все копии конфигураций, экспорт, backup, crash reports и диагностические файлы; проверить UUID/password/token/URL masking.
- [ ] P3.3 Проверить DNS, IPv4/IPv6 routes, Kill Switch и setUnderlyingNetworks на уровне конфигурации и lifecycle; не применять неподтверждённые workaround.
- [ ] P3.4 Проверить Android ID derivative/DeviceIdManager: назначение, отправка, ротация, раскрытие пользователю и privacy disclosure.
- [ ] P3.5 Проверить supply-chain: provenance закреплённого libbox AAR, SHA-256, зависимости, SBOM, лицензии и release artifacts.
- Критерий: все пути хранения/экспорта классифицированы; нет известных открытых копий секретов; runtime leak-тесты остаются обязательным релизным gate.

### P4 — Диагностика, UX и производительность

- [ ] P4.1 Сопоставить существующие VpnFailureReason/diagnostics/log collector с пользовательскими сообщениями; не создавать параллельную систему.
- [ ] P4.2 Сделать пошаговую диагностику: подписка → профиль → probe → handshake → туннель → реальный трафик → DNS.
- [ ] P4.3 Упростить первый запуск и рекомендованный режим подключения, перепроверить PR #178.
- [ ] P4.4 Проверить список из 500–2000 профилей, кэширование, UI responsiveness и память статически; численные KPI назначать после baseline.
- [ ] P4.5 Проверить частоту фоновых probes и BatteryPolicy, разделить пользовательские и диагностические режимы.
- Критерий: пользователь получает понятную причину и безопасное следующее действие; производительность измеряется, а не предполагается.

### P5 — Релизная готовность

- [ ] P5.1 Свести результаты всех PR, проверить main и релизную ветку, лицензионные уведомления и branding.
- [ ] P5.2 Проверить Direct APK и Play flavor отдельно; подпись, permissions, updater, FileProvider, versionCode и SHA-256.
- [ ] P5.3 Сформировать release checklist и публичную документацию пользователя.
- [ ] P5.4 Провести разрешённые пользователем runtime испытания на Xiaomi, Samsung и Pixel: handover, reboot, Doze, Kill Switch, DNS/IPv6, TCP/UDP и длительная сессия.
- [ ] P5.5 Не выпускать массовый релиз при открытых Critical/High дефектах или неподтверждённых security gates.
- Критерий: воспроизводимая сборка, прозрачный отчёт проверок, отсутствие известных критических дефектов; runtime-гейты закрыты доказательствами.

## Существующие PR для первичного разбора

- #168 CI hardening / docs
- #169 service teardown
- #170 privacy-safe logging
- #173 network buckets
- #174 scan cache
- #175 import normalization
- #176 coordinator serialization
- #177 product strategy
- #178 recommendation-first connection
- #179 encrypted subscription cache
- #180 preserve handover events
- #181 FileProvider scope
- #182 Play compliance

Состояние каждого PR нужно повторно получать из GitHub перед решением. Предыдущий CI run не подтверждает текущий HEAD.

## Реестр доказательств

Для каждого пункта вести: ID, тип (дефект/риск/гипотеза/рекомендация), точный файл/метод, доказательство, влияние, минимальное изменение, PR, CI SHA и статус runtime-проверки.

## Definition of Done для каждой задачи

- проблема подтверждена по актуальному коду или чётко обозначена как риск;
- проверены все call sites и связанные PR;
- изменение изолировано и не нарушает ограничения проекта;
- diff просмотрен целиком;
- CI-результаты привязаны к конкретному HEAD SHA;
- ручные тесты не заявляются, если не выполнялись;
- есть краткое описание результата, остаточных рисков и следующего шага.

## Журнал выполнения

### 2026-11-19 — P0.1: первичный аудит lifecycle/network PR

Проверены актуальные PR HEAD, patches и GitHub Actions для #169, #173, #176 и #180.

| PR | Что меняет | CI на проверенном HEAD | Статический вывод |
|---|---|---|---|
| #180 | Сохраняет последнее handover-событие через `conflate()`, ждёт завершения busy-перехода до 90 секунд, затем проверяет активность VPN | 7/7 успешно | Направление исправления обосновано; после 90 секунд событие пропускается с предупреждением. Runtime-проверка ещё нужна |
| #173 | Детерминирует выбор сетевой корзины по приоритету Wi-Fi / other / cellular | 7/7 успешно | Устраняет зависимость от порядка `allNetworks`; приоритет Wi-Fi над cellular — продуктовая политика, требует подтверждения |
| #176 | Передаёт смену provider scope и manual mode в actor через события | 7/7 успешно | Снижает конкурентный доступ к actor-owned fallback state; не доказывает сериализацию всех mutable полей класса |
| #169 | Останавливает фоновые workers и сериализует stopCore при onDestroy через отдельный IO scope | 7/7 успешно | Lock order lifecycle → CorePortLease совпадает с обычным stop path; остаётся риск, что ОС завершит процесс до завершения асинхронного cleanup |

Проверенные SHA:
- #169 `f2bc7c432d31b505c59d6333bfc6038b1b8f20e9`
- #173 `bf0d9cacd3480c4ec0aa6c8aec82688e4e52f1ce`
- #176 `89e6195d6caffff23bfdae47307dee3273189df4`
- #180 `09a664dbb5b4e3fcbfad80db71d2870930b4cf07`

Все PR остаются открытыми Draft и не входят в `main`. CI успех относится только к перечисленным HEAD SHA. Runtime-проверки на Xiaomi/Samsung/Pixel не выполнялись. Новые ручные тесты не добавлялись и не запускались.

### 2026-11-19 — P0.2: уточнение handover busy-state

При просмотре полного call graph обнаружено дополнительное окно: `ConnectionCoordinator` намеренно игнорирует `NetworkChanged` в состояниях `Connecting`, `Reconnecting` и `Verifying`, тогда как исходный `isBusy` в `VpnProxyService` учитывал только ServiceState Starting/Stopping и AutoSwitcher.isSwitching(). Поэтому PR #180 ожидал окончания части переходов, но не всех состояний actor.

В PR #180 добавлен минимальный follow-up commit:
- SHA `f824c3b07f59687c847eac3be44e444e8ad33575`
- `VpnProxyService.isBusy` теперь также учитывает `ConnectionState.Connecting`, `Reconnecting` и `Verifying`.
- Изменение сделано непосредственно в существующей handover-ветке, без создания дублирующего PR.

Статус: follow-up commit в PR #180 прошёл все 7/7 GitHub Actions checks на SHA `f824c3b07f59687c847eac3be44e444e8ad33575`. PR остаётся Draft и не слит. Остаётся проверить edge-case нескольких handover-событий во время уже выполняющегося reconnect и timeout границу 90 секунд; runtime-проверки не выполнялись.

### 2026-11-19 — P0.3: перенос native cleanup с главного потока

В PR #169 обнаружено, что `ServerScanner.cancelBackgroundWork()` вызывает `DeepProbeEngine.cancel()`, а тот синхронно вызывает `SingboxProxyClient.stop()` и native `core.stop()`. Исходный PR переносил остановку основного VPN core на IO, но оставлял остановку временного probe-core в `onDestroy()` на главном потоке.

Добавлен follow-up commit в существующую ветку PR #169:
- SHA `0a7e9253c0d69948f645f306caea14a4c60b0411`
- Весь teardown фоновых компонентов, включая остановку временного probe-core, перенесён в независимую IO-корутину.
- Флаг `_isCoreTransitioning` выставляется до запуска cleanup, чтобы блокировать новые probes/preflight.
- Сохранён порядок блокировок: lifecycle lock → CorePortLease.

Статус: на SHA `0a7e9253c0d69948f645f306caea14a4c60b0411` все 7/7 GitHub Actions checks завершились успешно, включая CodeQL. PR остаётся Draft и не слит. Android process-death/OEM runtime-проверки не выполнялись.

### 2026-11-19 — P1.1: аудит существующего import PR

Проверен PR #175:
- Нормализует регистр URI-схем и query-параметров (например, `serviceName`, `publicKey`, `shortId`, `allowInsecure`).
- На проверенном HEAD `7d3b70e7cc34880838d694d1d2782dae1dd30313` все 7 CI checks завершились успешно.
- Изменения обоснованы; PR остаётся Draft и не слит в main.
- В репозитории обнаружены два класса `SubscriptionParser`: основной `com.vpnproxy.subscription.SubscriptionParser` используется приложением; `com.vpnproxy.core.protocols.parser.SubscriptionParser` не имеет найденных production call sites. Не удалять автоматически: сначала определить, является ли это заделом новой parser architecture.

### 2026-11-19 — P1.2/P1.3: отчёт импорта и privacy hardening

Создан и расширен Draft PR #184, ветка `feature/subscription-import-report`, основанная на PR #175:
- добавлен `SubscriptionParser.parseWithReport()` с форматом, числом кандидатов, принятых/отклонённых записей, дубликатами и протоколами;
- `SubscriptionRepository` агрегирует отчёты по провайдерам и отдельно отмечает восстановление из зашифрованного/локального кэша;
- `JsonConfigRepository` теперь публикует отчёт прямого JSON/sing-box импорта; `SubscriptionsViewModel` и экран показывают его отдельно;
- отчёт не выдумывает точные причины: пока используются форматные категории `invalid_or_unsupported_*`;
- из затронутого `SubscriptionRepository` удалено логирование URL подписок и идентификаторов источников; сообщения об ошибках больше не выводят URL или текст исключения.

Текущий HEAD PR #184: `4c4e1c510b203f5b2298752739727a06097e9378`. Изменено 5 файлов. PR остаётся Draft, stacked на #175. GitHub Actions не вернул workflow runs для этого commit; CI не запускался/не подтверждён.

Ограничения и следующие действия:
- точные per-field причины отклонения ещё не реализованы;
- ответ HTTP 304 теперь явно помечается как `etag_cache`, а UI отличает ETag-кэш от свежей загрузки;
- необходима повторная проверка diff и сборки CI после включения PR в основную цепочку;
- ручные тесты и APK/device validation не выполнялись.

### 2026-11-19 — P2.1 follow-up: текущий health-check использовал неподходящий probe path

При проверке полного call path обнаружено отдельное несоответствие:
- `VpnProxyService` запускает `AutoSwitcher.startMonitoring()` при получении `ConnectionState.Connected`.
- Монитор текущего сервера вызывает `HealthMonitor.checkSelectedServer()` → `HealthChecker.testLatency()`.
- `HealthChecker.withSingboxClient()` намеренно запрещает этот путь при активном VPN (`isRunning=true`), поскольку он предназначен для запуска изолированного ядра проверки альтернативного профиля.
- В результате активный health monitor мог получать искусственный failed result и повторно входить в direct-vs-VPN validation вместо проверки трафика текущего туннеля.

Создан Draft PR #185:
- ветка `fix/autoswitcher-active-tunnel-healthcheck`;
- текущий HEAD `02a1a9670a608df7ba0d0923ed6d0a98b1533242`;
- добавлен отдельный `testActiveTunnelLatency()` через локальный mixed inbound `127.0.0.1:10809`;
- изолированный `testLatency()` для альтернативных профилей оставлен без изменений;
- `HealthMonitor.checkSelectedServer()` переключён на проверку уже работающего туннеля.

На актуальном HEAD PR #185 (`02a1a9670a608df7ba0d0923ed6d0a98b1533242`) все 7/7 GitHub Actions checks завершились успешно. Ручные тесты и runtime-проверки не проводились. PR остаётся Draft и не слит.

### 2026-11-19 — P2.1: статический аудит реального scoring pipeline

Проверены `ScoringEngine.kt`, `AutoSwitcher.kt`, `DeepProbeEngine.kt`, `ServerScanner.kt`, `HealthChecker.kt` и production call sites через поиск по репозиторию.

Подтверждённые факты:
- `HomeViewModel` вызывает `AutoSwitcher.rankInBackground()` после `ConnectionState.Connected`, но сам метод первым делом отменяет ranking и возвращает управление, если `VpnProxyService.isRunning` или core transition активны. Это нормальное состояние после успешного подключения; поэтому обычный post-connect путь не запускает сравнительное ранжирование через ScoringEngine.
- Если `rankInBackground()` всё же выполняется в допустимом состоянии, он вызывает `HealthChecker.testLatency()` для RU/foreign групп и затем `ScoringEngine.computeScore(ruResults + foreignResults)` без speedResults. В таком вызове компонент `0.25 * normalizedSpeed` получает нулевую скорость.
- `ScoringEngine.rankAll()` умеет измерить скорость для top-5 latency-кандидатов, но production-вызовов `rankAll()` в доступном коде не найдено. Метод не участвует в основном AutoSwitcher path.
- `ServerScanner.scoredServer()` преобразует результат `DeepProbeEngine.pingProfiles()` в `ScoredServer` с `speedMbps=0`, `successRate=1/0` и отдельной формулой `1 / (1 + latency/1000)`. Сортировка списка при этом использует успешность, историю успешных подключений в текущем network bucket и latency, а не общий composite score.
- Начальный выбор в `HomeViewModel` строится на preflight: verified profiles сортируются по latency, затем `ConnectServerSelector` в automatic mode выбирает `usable.first()`. Это фактически выбор самого быстрого по RTT из доступных, без измерения скорости/jitter/loss; ручной выбор и last-success учитываются отдельно.
- Комментарий `ConnectServerSelector` обещает background ranking после подключения, но вызов `rankInBackground()` из состояния Connected попадает под guard `VpnProxyService.isRunning` и обычно сразу завершается. Поэтому composite score не участвует в стандартном цикле выбора после подключения.
- `DeepProbeEngine.pingProfiles()` делает до четырёх HTTP GET попыток по двум endpoint-ам, считает профиль успешным при двух успешных samples и возвращает median RTT. Этот режим используется scanner/preflight, но не измеряет speed/jitter.
- `DeepProbeEngine.run()` собирает latency, jitter, loss и скорость загрузки 1 MB, затем вызывает `scoreMetrics()` с весами 0.4/0.3/0.2/0.1. Найденный production call site — ручной запуск из `ServersListScreen`; связь этого composite score с AutoSwitcher не обнаружена.

Вывод:
- В проекте существуют как минимум три разных модели оценки: ScoringEngine composite (latency/speed/history), ServerScanner simple latency score и DeepProbe manual composite (latency/speed/jitter/loss).
- Обычный post-connect путь сейчас не использует comparative ScoringEngine ranking: HomeViewModel вызывает его после Connected, а AutoSwitcher блокирует запуск при активном VPN. При этом ServerScanner уже поставляет проверенные preflight-кандидаты с latency-only score и отдельной сортировкой.
- Это архитектурная несогласованность, а не доказательство сетевого дефекта. Нельзя просто заменить все формулы одной без анализа пользовательских сценариев и порогов.
- Следующий шаг P2.2: описать единый контракт качества и определить, какие измерения допустимы для preflight, фонового мониторинга и ручной диагностики; отдельно сохранить быстрый preflight и запретить автоматическое переключение по несопоставимым/устаревшим метрикам.

Ручные тесты не запускались; runtime поведение не проверялось.

### 2026-11-19 — P2.5: исторический успех не должен выглядеть как измеренный ping

При проверке кэша `ServerScanner.restoreCachedResults()` обнаружено:
- если свежего scan cache нет, успешное подключение за последние 7 дней восстанавливалось как `ScoredServer(latencyMs=1, successRate=0.75, score=0.5)`;
- 1 ms не является измерением RTT и может отображаться как актуальный ping, когда свежий scan невозможен (например, пока VPN core активен);
- preflight уже отфильтровывает `Long.MAX_VALUE`, поэтому исторический успех можно сохранить отдельно, не превращая его в кандидата с выдуманной задержкой.

Создан Draft PR #187, ветка `fix/scanner-history-not-current-latency`:
- текущий HEAD `6fd6e93269ed95d29a5882874a51cec69b3cc633`;
- исторический hint `successRate=0.75` сохранён;
- latency теперь `Long.MAX_VALUE`, score — `0f`, чтобы UI показывал «н/д», а не 1 ms;
- Unit Tests и Gradle Dependency Graph прошли. Первая попытка CodeQL не смогла разрешить Gradle plugin `com.google.dagger.hilt.android:2.53.1` до анализа исходников; job перезапущен. Остальные проверки выполняются. Runtime/UI проверка на устройстве не проводилась.

### 2026-11-19 — P2.3: изоляция истории качества по физической сети

Создан Draft PR #186, ветка `fix/scoring-history-network-scope`:
- выделен общий `NetworkBucketProvider` на основе прежней логики `AutoSwitcher.currentNetworkBucket()`;
- история успехов/ошибок `ScoringEngine` теперь адресуется полным ключом профиля + physical network bucket;
- новая история хранится в `server_scoring_history_v2`; старое хранилище v1 не удаляется и не переносится в неизвестную сеть;
- `ConnectionCoordinator` фиксирует network bucket при выборе сервера и при начале TrafficValidator probe, чтобы поздний результат не записывал hard failure в другой bucket после handover;
- существующий конструктор `ScoringEngine(context)` сохранён для совместимости текущих call sites и тестов.

Текущий HEAD: `9c3ec475fa8c44dfd2d357d76252feeaa8d9deda`. Все 7/7 GitHub Actions checks завершились успешно на этом SHA, включая CodeQL. Предыдущий SHA не использовался как подтверждение. Ручные тесты не добавлялись и не запускались; runtime проверка на Wi-Fi/cellular не выполнялась.

Остаточный риск: network bucket — это coarse class (Wi-Fi/cellular/other), а не уникальный Network handle/epoch. Полноценный типизированный результат с network generation и freshness остаётся P2.4/P2.5.

### 2026-11-19 — P2.2: контракт измерений Smart Autopilot

Создан документ `docs/SMART_AUTOPILOT_SCORING_CONTRACT.md` в ветке roadmap:
- Разделены preflight профиля, health-check активного туннеля и ручной DeepProbe-диагностики.
- Зафиксированы обязательные метаданные измерений: полный ключ профиля, network bucket/epoch, timestamp, метод, endpoint set, sample counts и доступность каждой метрики.
- Запрещено сравнивать результаты разных сетей, поколений сканирования и разных методов как единый score.
- Зафиксировано, что отсутствие throughput-измерения — это unknown, а не нулевая скорость.
- Описан безопасный путь recovery через свежие preflight-кандидаты текущей сети; если данные устарели, запрещено переключение наугад.
- Объявленные в `AutoSwitcherConfig` параметры URLTest не имеют production call sites; `SingboxConfigBuilder.buildVpn()` пока не создаёт urltest group/standby outbounds. Их внедрение выделено в отдельное архитектурное решение.

Дополнительно подтверждены риски текущей реализации:
- `ScoringEngine.rankAll()` тестирует throughput только у top-5 latency-кандидатов, но включает в итоговый score все latency-кандидаты; непроверенные по скорости получают speed=0. Такой score не является честным сравнением при частичных данных.
- История `ScoringEngine` привязана к полному ключу профиля, но не к network bucket; качество на Wi-Fi может влиять на оценку того же профиля в cellular.
- `rankInBackground()` после Connected блокируется, пока активен основной core; это не должно маскироваться фиктивным запуском isolated probes.

P2.2 завершён как проектирование контракта. Кодовая реализация начинается в P2.3/P2.4 после проверки существующих моделей и совместимости с текущими call sites. Никакие runtime свойства не заявляются.

## Текущий статус

План зафиксирован отдельной веткой и опубликован в Draft PR #183. P0.2: PR #180 — CI 7/7 успешно. P0.3: PR #169 — CI 7/7 успешно, включая CodeQL. P1.1 и P1.2 выполнены; P1.3 начат в Draft PR #184 (подписки + прямой JSON-импорт, privacy log cleanup), CI не подтверждён. P2.1 и P2.2 завершены на уровне статического аудита и проектного контракта; создан `docs/SMART_AUTOPILOT_SCORING_CONTRACT.md`. PR #185 исправляет active health-check, CI 7/7 успешно. P2.3 реализован в Draft PR #186 (network-scoped scoring history), CI 7/7 успешно. P2.5 начат в Draft PR #187 (исторический успех больше не отображается как 1 ms), CI выполняется; первая попытка CodeQL потребовала повторного запуска из-за недоступности Gradle plugin artifact. P2.4 typed measurement contract и полная freshness/network epoch логика ещё впереди. Никакие PR не слиты автоматически.
