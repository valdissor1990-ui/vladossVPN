# Настройка и сборка VLADOSSVPN

## Сборка в GitHub Actions

Основная ветка `main` — единственный источник релизов.

- Pull request в `main` запускает проверочную сборку из workflow **Pull Request Build Validation**.
- Push в `main` запускает **Build, Sign & Release**: сборку APK/AAB, проверку нативного ядра и лицензий, затем публикацию GitHub Release.
- Релизная версия задаётся в `.github/workflows/release.yml` переменными `APP_VERSION_NAME` и `APP_VERSION_CODE`. Перед следующим релизом обновляйте их согласованно с конфигурацией приложения и проверяйте итоговые versionName/versionCode в собранном APK.

## Секреты подписи

В Settings → Secrets and variables → Actions должны быть настроены:

| Secret | Назначение |
|---|---|
| `KEYSTORE_BASE64` | Keystore в Base64 |
| `KEYSTORE_PASSWORD` | Пароль keystore |
| `KEY_ALIAS` | Alias ключа |
| `KEY_PASSWORD` | Пароль ключа |

Храните исходный keystore и пароли в надёжном менеджере паролей. Потеря ключа может помешать выпускать обновления с той же подписью. Не добавляйте keystore, пароли или файлы секретов в Git.

## Локальная сборка

Нужны JDK 17, Android SDK и Gradle Wrapper. CI при необходимости скачивает отсутствующий `gradle-wrapper.jar`; локально его можно создать командой из установленного Gradle:

```bash
gradle wrapper --gradle-version 8.11.1
./gradlew :app:assembleDirectDebug
```

Для production-сборки локально должны быть настроены те же параметры подписи, что и в CI.

## Нативное ядро

Проект использует `sing-box-lx libbox`, а не Xray. Скрипт `scripts/download_libbox.sh` скачивает AAR конкретной версии и проверяет SHA-256, Go bridge и наличие нативной библиотеки:

```bash
./scripts/download_libbox.sh
```

Не добавляйте скачанные `app/libs/*.aar` и нативные `.so` в Git: они загружаются и проверяются скриптами сборки.

## Проверка результата

После push откройте вкладку Actions и дождитесь успешного завершения **Unit Tests** и **Build, Sign & Release**. Затем проверьте GitHub Release: APK для прямой установки, AAB для Google Play и файлы лицензий/уведомлений должны соответствовать одной версии и одному исходному commit SHA.

Реальная проверка VPN-трафика на Android-устройстве остаётся обязательной после изменений сетевого кода.
