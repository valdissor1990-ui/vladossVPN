# Обновление тулчейна и зависимостей (2026-11)

Ветка: `chore/deps-upgrade-latest-stable` (база — `integration/full-vpn-client-final`,
head `0bedb72c`). `main` не затрагивается.

Цель: поднять все Gradle-плагины, библиотеки и нативное ядро до последних
стабильных версий, совместимых с `compileSdk 35` (CI ставит только
`platforms;android-35 build-tools;35.0.0`, поэтому `compileSdk` намеренно
оставлен на 35).

## Итоговые версии

### Плагины (`build.gradle.kts`)

| Компонент | Было | Стало |
|---|---|---|
| Android Gradle Plugin | 8.7.3 | **8.13.2** (последний 8.x) |
| Kotlin (android / compose / serialization) | 2.1.0 | **2.3.21** |
| KSP | 2.1.0-1.0.29 | **2.3.14** |
| Hilt | 2.53.1 | **2.58** |
| dependency-license-report | 3.1.4 | 3.1.5 |
| CycloneDX BOM | 3.5.1 | 3.6.1 |
| Gradle wrapper | 8.14.6 | **8.14.8** (SHA-256 закреплён) |

### Библиотеки (`app/build.gradle.kts`)

| Компонент | Версия |
|---|---|
| Compose BOM | 2026.06.01 (Compose 1.11.4, Material3 1.4.0) |
| navigation-compose | 2.9.8 |
| activity-compose | 1.10.1 |
| lifecycle-viewmodel-compose / lifecycle-runtime-compose | 2.10.0 |
| hilt-android / hilt-compiler | 2.58 |
| androidx.hilt:hilt-work / hilt-compiler / hilt-navigation-compose | 1.3.0 |
| Room | 2.8.5 |
| DataStore Preferences | 1.3.0 |
| work-runtime-ktx | 2.12.0 |
| OkHttp / logging-interceptor | 5.3.2 |
| kotlinx-serialization-json | 1.11.0 |
| kotlinx-coroutines-android / -test | 1.11.0 |
| androidx.security:security-crypto | 1.1.0 |
| Timber | 5.0.1 |
| SnakeYAML | 2.7 |
| core-ktx | 1.16.0 |
| appcompat | 1.8.0 |
| junit / org.json | 4.13.2 / 20260719 |

Остальное без изменений: `compileSdk 35`, `minSdk 26`, `targetSdk 35`,
Java/Kotlin JVM target 17. `kotlinOptions` заменён на top-level
`kotlin { compilerOptions { jvmTarget = JVM_17 } }`.

## Ядро

| | Было | Стало |
|---|---|---|
| sing-box-lx / libbox | 1.14.3-lx.23 | **1.14.4-lx.35** |
| SHA-256 AAR | d7be8b12… | **e5cfd79364316150acd5c9ddbb5d6d8c749f1ce4a590f9a3eaa408f949b8fd7b** |

Обновлён только `scripts/download_libbox.sh` (`LIBBOX_VERSION` / `LIBBOX_SHA256`);
CI (`build.yml` → `release.yml`, `test.yml`) вызывает этот скрипт, поэтому правки
workflow не требуются. Release notes ядра: 22 upstream-коммита, фиксы REALITY
клиента, uTLS 1.9.0-alpha.1, ShadowTLS v2, QUIC-буферы, «No config changes».

Изменения в коде под новый API ядра: убран `override fun useProcFS()`
(`SingBoxPlatform.kt`) — метод удалён из `PlatformInterface` в 1.14.4.

Обновлены связанные пины cronet-go (используются в релизном license-audit):

| Пин | Значение |
|---|---|
| cronet-go wrapper commit | `095cb9e060468c80ca31d977c020dca82d101a8f` |
| cronet-go lib/android_arm64 commit | `3aac4ed44c5cf0af74a6051a7335c49dffd91c37` |
| NaiveProxy / Chromium commit | `7c64cae24cd7bae18d9c603fdddc73b623c04462` |

Обновлены `scripts/audit_libbox_licenses.sh`, `THIRD_PARTY_NOTICES.md`,
`app/src/main/assets/licenses/{sing-box-lx-NOTICE,cronet-native-NOTICE,THIRD_PARTY_NOTICES}.txt`.

## Ограничения совместимости (почему не «самое-самое»)

- **AGP 9.x / compileSdk 36–37 / Compose 1.13 / core-ktx 1.19** — требуют
  Gradle ≥ 9.6, `build-tools 36+` и правок workflow (ставятся только 35.0.0).
  Отложено; AGP 8.13.2 — последний релиз ветки 8.x.
- **Kotlin 2.4 не подходит**: Hilt 2.58 (максимальный для AGP 8.x) читает
  Kotlin-метаданные только до версии 2.3.0, поэтому компилятор ограничен
  **2.3.21**. AGP-9-only Hilt 2.59+ исключён.
- **OkHttp 5.5.x** тянет `okhttp-android` с `minCompileSdk 37`; максимум для
  compileSdk 35 — **5.3.2**.
- **kotlinx-serialization-json 1.12/1.13** требуют Kotlin 2.4 (те же метаданные),
  поэтому **1.11.0**.
- AndroidX-библиотеки выбраны по максимуму, совместимому с compileSdk 35
  (Compose 1.11.x, activity 1.10.x, navigation 2.9.x, lifecycle 2.10.x, Room 2.8.x).

## Локальная валидация

Выполнено локально (JDK 21 / Android SDK 35):

- `:app:compileDirectDebugKotlin`, `:app:compilePlayDebugKotlin` — успешно.
- `:app:assembleDirectDebug`, `:app:assemblePlayDebug` — успешно.
- `:app:testDirectDebugUnitTest`, `:app:testPlayDebugUnitTest` — **149 тестов на
  каждый флейвор, 0 падений**.

Замечание для локальной сборки на Windows с кириллицей в пути профиля: Gradle
передаёт classpath тест-воркера через `@argfile` в домашнем каталоге Gradle, а
JVM-лаунчер декодирует его в ANSI — это известный баг
(`Could not find or load main class worker.org.gradle.process.internal.worker.GradleWorkerMain`).
Обход: вынести проект и `GRADLE_USER_HOME` на ASCII-пути (например через
`subst`) или задать `android.overridePathCheck=true`. В CI (Linux) проблемы нет.

## CI / provenance

Правки workflow вынесены в отдельный PR #281, чтобы не смешивать обновление
зависимостей и ядра с изменениями CI. В #281 исправляются provenance-ссылки
релизной и тестовой сборок на libbox `1.14.4-lx.35`, cronet-go и NaiveProxy,
а также устаревшие подписи версии ядра в PR build workflow.

В PR #281 bootstrap wrapper JAR в PR build и Unit Tests workflows обновлён до
Gradle 8.14.8. Release workflow пока извлекает проверенный wrapper JAR из
архива 8.14.6; фактический Gradle distribution всё равно задаётся и
проверяется через `gradle-wrapper.properties` как 8.14.8.

## Где остановились (handoff, 2026-11-21)

Работа остановлена по запросу пользователя. Текущая точка:

- Ветка: `chore/deps-upgrade-latest-stable`
- Коммит: `d493af6e36850e000d380a82756743c7151b1f10`
- PR: **#280** → база `integration/full-vpn-client-final` (не в `main`)
- CI на момент остановки: workflow `Pull Request Build Validation` в статусе
  **pending** (run `44824197285`, job `136592482049`). Результат нужно забрать
  из GitHub, он не подтверждён.

### Сделано

1. Тулчейн, библиотеки и ядро обновлены до последних стабильных, совместимых с
   `compileSdk 35` (см. таблицы выше).
2. Ядро: libbox `1.14.3-lx.23 → 1.14.4-lx.35`; в `SingBoxPlatform.kt` убран
   `useProcFS`; обновлены пины cronet-go/naiveproxy в license-audit.
3. Документация: актуализированы ссылки на ядро/cronet, закрыты ROADMAP 1.1/1.2,
   добавлен этот документ.
4. Локальная проверка (JDK 21, SDK 35): `assembleDirectDebug` / `assemblePlayDebug`
   успешно; unit-тесты **149 Direct + 149 Play, 0 падений**.

### Не сделано / следующие шаги

1. Забрать и зафиксировать результат CI по PR #280; при падении — исправить в этой
   же ветке и запушить (HOME SHA обновляется).
2. Привести `.github/workflows/*` к 1.14.4-lx.35/8.14.8 — требует scope `workflow`
   (сейчас файлы намеренно не тронуты, чтобы push не отклонялся).
3. Доведение по аудитам: кодовая часть P0 (честный `Connected` + fallback
   эндпоинтов) в интеграционной ветке уже есть (`ConnectionCoordinator`
   переводит в `Connected` только после `TrafficValidationResult(success=true)`,
   fallback через `tryFallback()`); остальное — пункты P1–P5 и runtime/device-гейты.
4. Расширенный поиск багов — начат разбор `ConnectionCoordinator.kt` и
   `TrafficValidator.kt`; выводов пока нет.

### Блокеры / ограничения

- CI/provenance исправления вынесены в отдельный PR #281; его CI ещё должен завершиться.
- AGP 9.x / compileSdk 36–37 / Compose 1.13 / Kotlin 2.4 / OkHttp 5.5 — отложены
  по причинам совместимости (CI ставит только `platforms;android-35 build-tools;35.0.0`).
- Runtime/device-тесты не выполнялись (запрещены без отдельного разрешения).
- Нет signing-env → локальная release-сборка недоступна.

### Открытые решения (нужен ответ пользователя)

1. Мержить ли draft PR #277 (`integration → main`; запустит `release.yml`).
2. Какую версию/`versionCode` использовать для релиза.
3. Нужно ли разрешение на device-тесты.

### Как продолжить локально (Windows, кириллица в пути профиля)

Gradle ломает тест-воркер на не-ASCII пути (`GradleWorkerMain`). Обход — вынести
проект и `GRADLE_USER_HOME` на ASCII-диски:

```powershell
subst G: "$env:USERPROFILE\.gradle"
subst P: "$env:USERPROFILE\vladossVPN"
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:GRADLE_USER_HOME='G:\'
cd P:\ ; P:\gradlew.bat :app:testDirectDebugUnitTest :app:testPlayDebugUnitTest
```

Перед повторной сборкой из исходного пути: `subst G: /D` и `subst P: /D`.
