# Триаж зависимостей и Dependabot-алертов (build-time supply chain)

**Дата:** 2026-11-20
**Ветка снимка:** `integration/full-vpn-client-final` @ `97304149`
**Основание:** при включении Dependabot alerting GitHub сообщил о 66 открытых алертах
на default-ветке `main` (3 critical, 28 high, 33 moderate, 2 low).

> Триаж выполнен **статически** по данным GitHub Dependency Graph (SPDX SBOM API)
> и `app/build.gradle.kts`. Runtime/устройство не проверялись.

## Главный вывод

**Ни одна из уязвимых библиотек critical/high не попадает в APK/AAB.**
Все critical/high-алерты относятся к **classpath Gradle-плагинов** (build-time),
а не к runtime-зависимостям приложения. Единственная app-level зависимость в списке —
`org.json:json` и она объявлена как `testImplementation` (в сборку не входит).

Runtime-классpath приложения (`app/build.gradle.kts`) состоит из:
`androidx.compose.*`, `androidx.navigation`, `androidx.activity`, `androidx.lifecycle`,
`androidx.hilt`, `androidx.room`, `androidx.datastore`, `androidx.work`,
`androidx.security:security-crypto`, `androidx.core:core-ktx`, `androidx.appcompat`,
`com.google.dagger:hilt-android`, `com.squareup.okhttp3:*`, `kotlinx-serialization-json`,
`kotlinx-coroutines`, `com.jakewharton.timber`, `org.yaml:snakeyaml` и локальный
`libs/libbox.aar`.
Ни `io.netty`, ни `org.bouncycastle`, ни `jackson`, ни `guava`, ни `protobuf`,
ни `jdom2`, ни `jose4j`, ни `commons-*` в него не входят.

## Происхождение critical/high-находок (источник — SBOM relationships)

| Пакет | Версия в графе | Версия с фиксом | Кто тянет (родитель) |
|---|---|---|---|
| `io.netty:netty-*` | 4.1.93.Final | ≥ 4.1.132 / 4.1.137 | AGP 8.7.3 → `io.grpc:grpc-netty:1.57.0` |
| `org.bouncycastle:bcprov-jdk18on` | 1.77 | ≥ 1.80.2 / 1.85 | `com.android.tools.build:builder@8.7.3`, `sdk-common@31.7.3`, `apkzlib@8.7.3` |
| `org.bouncycastle:bcpkix-jdk18on` | 1.77 | ≥ 1.80.2 | AGP 8.7.3 (`builder`, `gradle`, `sdk-common`, `apkzlib`) |
| `com.google.protobuf:protobuf-java` | 3.22.3 | ≥ 3.25.5 | AGP 8.7.3 / `bundletool@1.17.1` / grpc 1.57.0 |
| `com.google.guava:guava` | 32.0.1-jre, 33.0.0-jre, 33.7.1-jre | ≥ 33.7.2 | AGP 8.7.3 / grpc 1.57.0 / hilt-compiler 2.53.1 |
| `commons-io:commons-io` | 2.13.0 | ≥ 2.14.0 | AGP 8.7.3 |
| `org.apache.commons:commons-compress` | 1.21 | ≥ 1.26.x | `com.android.tools:sdklib@31.7.3`, `repository@31.7.3` |
| `org.bitbucket.b_c:jose4j` | 0.9.5 | ≥ 0.9.6 | `com.android.tools.build:bundletool@1.17.1` |
| `org.jdom:jdom2` | 2.0.6 | 2.0.6.1 | `com.android.tools.build.jetifier:jetifier-processor@1.0.0-beta10` |
| `com.fasterxml.jackson.core:jackson-*` | 2.22.2 | 2.22.3 | CycloneDX / license-report plugins |
| `org.json:json` | 20240303 | 20260719 | **root project** → `app` `testImplementation` (CVE-2026-59171) |
| `org.jetbrains.kotlin:kotlin-gradle-plugin` | 2.1.0 | — | Kotlin/Compose Gradle plugins |

## Статус remediation

* **Сделано (низкий риск):** `testImplementation("org.json:json")` обновлён до `20260719`
  (единственная app-level находка, test-only, в сборку не попадает).
* **Запланировано (отдельная задача, требуются CI-итерации):** снижение build-classpath
  находок требует поднятия AGP (8.7.3 → актуальный 8.x) и/или принудительных версий
  транзитивных зависимостей classpath плагинов, например:

  ```kotlin
  // build.gradle.kts (root)
  buildscript {
      configurations.classpath {
          resolutionStrategy {
              force(
                  "io.netty:netty-handler:4.1.137.Final",
                  "io.netty:netty-codec-http:4.1.137.Final",
                  "io.netty:netty-codec-http2:4.1.137.Final",
                  "io.netty:netty-codec:4.1.137.Final",
                  "org.bouncycastle:bcprov-jdk18on:1.85",
                  "org.bouncycastle:bcpkix-jdk18on:1.85",
                  "com.google.protobuf:protobuf-java:3.25.5",
                  "com.google.guava:guava:33.7.2-jre",
                  "commons-io:commons-io:2.14.0",
                  "org.bitbucket.b_c:jose4j:0.9.6",
                  "org.jdom:jdom2:2.0.6.1",
              )
          }
      }
  }
  ```

  Такое форсирование меняет версии, с которыми AGP не тестировался: требуется прогон
  `build.yml` + `security-validation.yml` и проверка релизной сборки. Не входит в
  релизный кандидат без отдельной валидации.

## Почему это не блокирует релиз

1. Уязвимые пакеты не упаковываются в APK/AAB (только build-time).
2. Эксплуатация требует компрометации сборочного окружения/CI, а не пользователя.
3. Пользовательского/сетевого риска в runtime эти алерты не создают.

Рекомендация: закрыть build-time находки отдельной задачей supply-chain hardening
(AGP bump + constraints), не смешивая её с релизным кандидатом `integration → main`.

## Проверка после релиза

После попадания изменений в `main` и пересборки dependency graph перезапросить:

```bash
gh api repos/valdissor1990-ui/vladossVPN/dependabot/alerts?state=open
gh api repos/valdissor1990-ui/vladossVPN/dependency-graph/sbom
```
