# VPNProxy — одноразовая настройка

После этих шагов каждый `git push` в `main` будет собирать, подписывать и публиковать APK.

---

## 1. Gradle Wrapper JAR (обязательно)

Файл `gradle/wrapper/gradle-wrapper.jar` — бинарный, его нельзя залить текстом через API.

**Вариант A — через Gradle (рекомендуется):**

```bash
# Установить Gradle 8.11.1, затем в корне репозитория:
cd vladossVPN
gradle wrapper --gradle-version 8.11.1
# Создаст gradle/wrapper/gradle-wrapper.jar + обновит скрипты

git add gradle/wrapper/gradle-wrapper.jar gradlew gradlew.bat
git commit -m "chore: add gradle-wrapper.jar"
git push origin main
```

**Вариант B — скачать вручную:**

```bash
cd vladossVPN
mkdir -p gradle/wrapper
curl -L -o gradle/wrapper/gradle-wrapper.jar \
  https://github.com/gradle/gradle/raw/v8.11.1/gradle/wrapper/gradle-wrapper.jar

chmod +x gradlew
./gradlew --version   # должно показать Gradle 8.11.1

git add gradle/wrapper/gradle-wrapper.jar
git commit -m "chore: add gradle-wrapper.jar"
git push origin main
```

**Вариант C — Android Studio:**  
File → Open → проект → Sync Project with Gradle Files.

---

## 2. Keystore + GitHub Secrets (обязательно для подписанных релизов)

```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias vpnproxy \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "ВАШ_ПАРОЛЬ" \
  -keypass "ВАШ_ПАРОЛЬ" \
  -dname "CN=VPNProxy, OU=Dev, O=VPNProxy, L=Moscow, C=RU"

base64 -w0 release.keystore > release.keystore.b64
# macOS: base64 -i release.keystore -o release.keystore.b64
```

Откройте: https://github.com/valdissor1990-ui/vladossVPN/settings/secrets/actions

| Secret | Значение |
|--------|----------|
| `KEYSTORE_BASE64` | содержимое `release.keystore.b64` |
| `KEYSTORE_PASSWORD` | пароль хранилища |
| `KEY_ALIAS` | `vpnproxy` |
| `KEY_PASSWORD` | пароль ключа |

**Сохраните keystore и пароли** (1Password / Bitwarden). Потеря keystore = нельзя обновлять приложение поверх старой подписи.

---

## 3. Нативные библиотеки (для реального VPN)

Без них UI соберётся, но VPN не подключится (`Failed to load libxray.so`).

```bash
chmod +x scripts/download_xray.sh
./scripts/download_xray.sh
```

Для production Xray используйте [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite):

```bash
mkdir -p app/libs
# скачайте AAR из Releases и положите как app/libs/AndroidLibXrayLite.aar
```

В `app/build.gradle.kts`:

```kotlin
implementation(files("libs/AndroidLibXrayLite.aar"))
```

Потребуется адаптировать `XrayCore.kt` под API библиотеки (`Xray.run` и т.д.) — см. `docs/ARCHITECTURE.md`.

---

## 4. Проверка пайплайна

```bash
git push origin main
# или пустой коммит:
git commit --allow-empty -m "chore: trigger release"
git push origin main
```

Смотрите: https://github.com/valdissor1990-ui/vladossVPN/actions  
Успех → вкладка Releases → APK `v1.0.XXXX`.

---

## Локальная сборка

```bash
./gradlew assembleDirectDebug
adb install -r app/build/outputs/apk/direct/debug/app-direct-debug.apk
```

Release (нужен keystore локально или secrets в CI):

```bash
./gradlew assembleDirectRelease
```

---

## Типовые ошибки

См. [TROUBLESHOOTING.md](TROUBLESHOOTING.md).
