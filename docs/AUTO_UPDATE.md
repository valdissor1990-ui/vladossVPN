# Автопубликация и автообновление — Инструкция для AI-разработчика

## Цель

Каждый `git push` в `main` → автоматически новая версия в GitHub Releases → все
установленные приложения получают уведомление и обновляются **без удаления**.

## Шаг 1. Первичная настройка репозитория (делается ОДИН раз)

1. Создать keystore:
   ```bash
   keytool -genkeypair -v -keystore release.keystore -alias vpnproxy \
     -keyalg RSA -keysize 2048 -validity 10000 \
     -storepass "XXX" -keypass "XXX" \
     -dname "CN=VPNProxy, O=VPNProxy, C=RU"
   base64 -w0 release.keystore > release.keystore.b64
   ```

2. В GitHub → Settings → Secrets and variables → Actions добавить:
   · KEYSTORE_BASE64 = содержимое release.keystore.b64
   · KEYSTORE_PASSWORD = пароль хранилища
   · KEY_ALIAS = vpnproxy
   · KEY_PASSWORD = пароль ключа

3. Сохранить keystore локально вне репозитория (.gitignore) — никогда не терять.

4. В app/build.gradle.kts убедиться, что GITHUB_OWNER и GITHUB_REPO подставляются
   (уже сделано через System.getenv("GITHUB_REPOSITORY")).

5. В VpnProxyApp.onCreate() запланировать периодическую проверку обновлений:
   ```kotlin
   UpdateCheckWorker.schedule(this, intervalHours = 6)
   ```

## Шаг 2. Ежедневный рабочий цикл

```bash
# Изменяем код
git add .
git commit -m "feat: улучшен парсинг Reality-ссылок"
git push origin main
```

Дальше всё автоматически:

1. GitHub Actions запускается
2. versionCode = 10000 + GITHUB_RUN_NUMBER (уникальный, растёт монотонно)
3. versionName = 1.0.<run_number>
4. APK подписывается тем же keystore
5. Создаётся Release v1.0.<run_number> со всеми APK/AAB
6. В течение 6 часов приложение само:
   · запросит https://api.github.com/repos/OWNER/REPO/releases/latest
   · сравнит versionCode из tag с BuildConfig.VERSION_CODE
   · покажет UpdateDialog с changelog
   · скачает APK → предложит установку
   · установит поверх (данные сохраняются)

## Шаг 3. Bump мажорной версии (вручную)

При breaking changes:

```bash
# version.properties: увеличить minor или major
echo "major=1" > version.properties
echo "minor=1" >> version.properties
git commit -am "chore: bump version to 1.1.x"
git push
```

Итоговая версия будет 1.1.<run_number>.

## Шаг 4. Что делать при проблемах

**Обновление не приходит**

· Проверить: https://github.com/OWNER/REPO/releases/latest — есть ли Release
· В приложении: DebugScreen → искать UpdateChecker / UpdateDownloader ошибки
· Проверить BuildConfig.GITHUB_OWNER и GITHUB_REPO (могут быть placeholder'ами)

**Установка падает с INSTALL_FAILED_UPDATE_INCOMPATIBLE**

· Keystore изменён между релизами → нельзя восстановить, только удалять приложение
· Убедиться, что KEYSTORE_BASE64 в secrets не менялся

**canRequestPackageInstalls() = false**

· Пользователь должен разрешить установку из «неизвестных источников»
· UpdateInstaller.openInstallPermissionSettings() открывает нужный экран
· После выдачи разрешения — повторить installReady()

## Шаг 5. Правила для AI-разработчика

1. Никогда не менять applicationId (com.vpnproxy + суффикс flavor) — иначе
   обновление не установится поверх.
2. Никогда не менять подпись APK.
3. Всегда инкрементировать versionCode (это делает CI автоматически через
   github.run_number — не переопределять вручную).
4. Всегда писать changelog в коммитах (feat:, fix:, perf:) — они попадут в Release.
5. При добавлении новых разрешений — проверять, не конфликтуют ли с политикой Play
   (для flavor play).
6. Для тестирования обновления: сделать git commit --allow-empty -m "test: update" и
   git push — новая версия появится через ~3-5 минут.

## Схема версионирования

```
v1.0.15       → major=1 minor=0 patch=15 (авто из run_number)
v1.0.16       → следующий push
v1.1.17       → после ручного bump minor
```

versionCode (int):

· CI: 10000 + run_number = 10015, 10016, ...
· Локально: System.currentTimeMillis() / 60_000 (уникальный в пределах минуты)

Монотонность гарантирована: GitHub run_number всегда растёт.
