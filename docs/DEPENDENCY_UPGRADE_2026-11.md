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

## Что не менялось

Правки файлов `.github/workflows/*` не входят в коммит: у `gh` нет scope
`workflow`, и push с изменением workflow будет отклонён. Из-за этого в workflow
остаются косметические хардкоды (`gradle-8.14.6` в bootstrap шаге — он лишь
извлекает wrapper JAR 8.14.6, а фактически скачивается дистрибутив 8.14.8 из
`gradle-wrapper.properties`; строки-метки «libbox 1.14.3» в названиях шагов и
`SOURCE_COMMIT.txt`). Для их приведения к 1.14.4-lx.35 требуется
`gh auth refresh -h github.com -s workflow`.
