# Обновление зависимостей и тулчейна — 2026-11-28

**Ветка:** `task/deps-2026-11`
**База:** `task/hardening` @ `f9cd6a65` (включает волну 2 полевой стабилизации)
**Причина:** проверка «соответствует ли проект сегодняшнему дню» — 28.11.2026.

## Главный вывод

Проект уже стоял на **потолке, который позволяет AGP 8.13**. Вся свежая линейка androidx/okhttp (ноябрь 2026)
требует **AGP 9.1.0+ и `compileSdk 37`** и без миграции на AGP 9 просто не подключается.
Поэтому волна поднимает то, что реально совместимо, и фиксирует точные границы — чтобы следующая
попытка «просто обновить» не начиналась с нуля.

Проверено по `META-INF/com/android/build/gradle/aar-metadata.properties` внутри AAR:

| Артефакт | `minCompileSdk` | `minAndroidGradlePluginVersion` | Вердикт |
|---|---|---|---|
| `compose.ui:ui-android:1.11.4` | 35 | 8.6.0 | максимум для AGP 8 |
| `compose.ui:ui-android:1.12.0` | 37 | 9.1.0 | заблокировано |
| `compose.material3:material3-android:1.4.0` | 35 | 8.6.0 | максимум для AGP 8 |
| `navigation-compose-android:2.10.0` | 37 | 9.1.0 | заблокировано |
| `lifecycle-runtime-compose-android:2.11.0` | 37 | 9.1.0 | заблокировано |
| `androidx.hilt:hilt-navigation-compose:1.4.0` | 37 | 9.1.0 | заблокировано |
| `activity-compose:1.13.0` | 36 | 8.9.1 | **совместимо** |
| `core-ktx:1.18.0` | 36 | 8.9.1 | **совместимо** |
| `okhttp-android:5.4.0` | 36 | 1.0.0 | **совместимо** |
| `okhttp-android:5.5.1` | 37 | — | заблокировано |

Дополнительно: **Hilt 2.59 и 2.60.1** (и плагин, и `hilt-android`) отказываются применяться на AGP 8 —
«The Hilt Android Gradle plugin is only compatible with AGP 9.0.0 or higher». Последняя рабочая — **2.58**.

## Что поднято

| Компонент | Было | Стало |
|---|---|---|
| `compileSdk` | 35 | **36** |
| `targetSdk` | 35 | **36** |
| activity-compose | 1.10.1 | **1.13.0** |
| core-ktx | 1.16.0 | **1.18.0** |
| okhttp | 5.3.2 | **5.4.0** |
| `org.json` (test) | 20260719 | **20260814** |
| sing-box-lx / libbox | 1.14.4-lx.35 | **1.14.4-lx.37** |

SHA-256 нового AAR: `bf4d0a8f60201315c5bd8b6835c66d895cf5a4c932d5d6853f84aa919b5aa687`
(обновлены `scripts/download_libbox.sh`, `scripts/audit_libbox_licenses.sh`, ссылки в документации).

### Про CI и `platforms;android-36`

В workflow по-прежнему перечислены `platforms;android-35` и `build-tools;35.0.0`. Это не мешает сборке:
AGP сам скачивает отсутствующую платформу, если лицензии SDK приняты (проверено локально — `platforms;android-36`
появился автоматически при первом билде с `compileSdk 36`).

Правка списков пакетов во всех 7 workflow лежит в отдельной ветке `task/ci-sdk36` и требует
push с токеном, у которого есть scope `workflow` (текущий OAuth-токен `gh` его не имеет и не может
обновлять файлы `.github/workflows/*`).

`targetSdk 36` — внешнее требование: с 31.08.2026 Google Play принимает обновления только с API 36.

## Что осталось намеренно без изменений

| Компонент | Осталось | Причина |
|---|---|---|
| AGP | 8.13.2 | 9.5.3 требует Gradle 9.6.0 и Build Tools 36; отдельная миграция |
| Gradle | 8.14.8 | без AGP 9 нельзя (AGP 8.13 требует Gradle 8.13+) |
| Kotlin | 2.3.21 | 2.4.21 требует проверки связки с KSP 2.3.14 |
| KSP | 2.3.14 | актуальная |
| Compose BOM | 2026.06.01 (Compose 1.11.4, M3 1.4.0) | 2026.08.00 и новее требуют AGP 9.1 |
| navigation-compose | 2.9.8 | 2.10.x требует AGP 9.1 |
| lifecycle | 2.10.0 | 2.11.x требует AGP 9.1 |
| androidx.hilt | 1.3.0 | 1.4.0 требует AGP 9.1 |
| Hilt | 2.58 | 2.59+ требует AGP 9.0 |
| Room / DataStore / Work / coroutines / security-crypto / Timber / SnakeYAML / ZXing / JUnit | без изменений | уже актуальны |
| kotlinx-serialization-json | 1.11.0 | 1.12.0 и 1.13.0 собраны Kotlin 2.4.10 → метаданные `2.4.0`, а Hilt 2.58 читает максимум `2.3.0`. Блокирует Hilt, а не AGP |

## Проверено отдельно

- **16 КБ страницы памяти** (требование Google для Android 15+): все `PT_LOAD` сегменты `libbox.so` (arm64)
  выровнены на `0x4000` и в lx.35, и в lx.37. Совместимо.
- **Edge-to-edge**: `MainActivity` уже вызывает `WindowCompat.setDecorFitsSystemWindows(window, false)`,
  поведение при `targetSdk 36` не меняется.
- **Android 16 `ACCESS_LOCAL_NETWORK`**: понадобится будущему локальному шлюзу
  (`docs/strategy/2026-11-25-local-gateway-rfc.md`), к текущим функциям не относится.

## Следующий шаг (отдельная задача)

Миграция на AGP 9.x + Gradle 9.6+ + `compileSdk 37` разблокирует Compose 1.13, Material3 1.5,
navigation 2.10, lifecycle 2.11, androidx.hilt 1.4 и Hilt 2.60. Это не «обновление галочки»:
AGP 9 меняет применение Kotlin-плагина (встроенная поддержка Kotlin) и требует прогона всего CI.
Делать отдельной веткой с полным прогоном `testDirectReleaseUnitTest`, `assembleDirectRelease` и релизного workflow.

## Проверка

```
./gradlew :app:testDirectReleaseUnitTest
```
