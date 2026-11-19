# Выпуск релиза vladossVPN

## До релиза

1. Убедитесь, что изменения находятся в `main`, PR прошёл CI и рабочее дерево чистое.
2. Проверьте [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md), [OPEN_SOURCE_COMPLIANCE.md](OPEN_SOURCE_COMPLIANCE.md), встроенные лицензии и закреплённые ревизии.
3. Обновите пользовательский changelog/описание релиза. В текущем workflow краткий changelog дополнительно формируется из последних коммитов.
4. Запустите локальные проверки:

```bash
./scripts/download_libbox.sh
./gradlew :app:testDirectDebugUnitTest
./gradlew :app:generateLicenseReport
./gradlew assembleDirectRelease bundlePlayRelease
```

Для локальной release-сборки задайте `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` и `KEY_PASSWORD`. Не коммитьте keystore или файл с паролями.

## Настройка GitHub Secrets

В Settings → Secrets and variables → Actions добавьте:

- `KEYSTORE_BASE64` — keystore в Base64 без переносов строк;
- `KEYSTORE_PASSWORD`;
- `KEY_ALIAS`;
- `KEY_PASSWORD`.

Создайте Base64 локально (пример для Linux/macOS):

```bash
base64 -w 0 release.keystore
```

На macOS, если ключ `-w` не поддерживается:

```bash
base64 < release.keystore | tr -d '\\n'
```

Никогда не вставляйте содержимое keystore в issue, PR, чат или лог CI.

## Публикация

Workflow `.github/workflows/release.yml` запускается при push в `main` и вручную через Actions → Build, Sign & Release. Он:

1. Вычисляет следующую версию и уникальный versionCode;
2. скачивает закреплённый `libbox.aar` и проверяет SHA-256/состав архива;
3. выполняет аудит лицензий Go/Cronet и генерирует Gradle license report;
4. подписывает Direct APK и Play AAB;
5. проверяет наличие лицензионных материалов в пакетах;
6. прикладывает APK, AAB и notices к GitHub Release.

При ручном запуске можно задать `version_name` формата `X.Y.Z`. Повторное использование уже опубликованного тега запрещено. Не запускайте release workflow для проверки обычных изменений: используйте PR workflow.

## После публикации

- Откройте GitHub Releases и проверьте, что APK universal и ABI-варианты, AAB, LICENSE и архив notices приложены.
- Проверьте подпись APK и установку на чистом Android-устройстве. Обновление поверх предыдущей версии должно иметь ту же signing identity.
- Проверьте запуск VPN, VLESS/Reality, Hysteria2, TUIC, TCP и UDP, split tunneling, смену Wi-Fi/мобильной сети и отключение.
- Проверьте, что обновлятор видит опубликованную версию и что ссылка/метаданные релиза доступны.
- Сохраните CI run, source commit и опубликованные отчёты вместе с релизом.

## F-Droid

Публикация в F-Droid не выполняется текущим workflow автоматически. Перед подачей необходимо отдельно проверить соответствие F-Droid metadata/build recipe, доступность всех исходников и воспроизводимость сборки, отсутствие закрытых/непроверяемых бинарных компонентов и требования к подписи F-Droid. Не заявляйте F-Droid-воспроизводимость только на основании успешного GitHub Actions build.

## Google Play VPN policy checklist

Before creating a Play production release, complete these steps in Play Console:

- Submit the VpnService declaration and accurately state that VPN tunneling is the app's core functionality.
- Document VpnService use in the Play listing and ensure traffic is encrypted from the device to the selected VPN tunnel endpoint.
- Complete the Data safety form and provide a public privacy policy that matches the actual app behavior, including subscription-provider requests, diagnostics, device metadata consent and retention.
- If the app accesses or collects personal/sensitive data through VpnService beyond what users reasonably expect, add a separate prominent in-app disclosure in the normal usage flow and obtain affirmative consent before collection. A Settings-only notice or privacy-policy paragraph is not a substitute.
- Prepare the Play Console review video showing the app opening, VPN usage, and (if applicable) the prominent disclosure and both consent/decline paths.
- Confirm that no monetization logic redirects or manipulates other apps' traffic.

Official guidance:
- [Google Play VpnService policy](https://support.google.com/googleplay/android-developer/answer/12564964)
- [Google Play User Data and prominent disclosure requirements](https://support.google.com/googleplay/android-developer/answer/10144311)

Do not publish until the declaration, privacy policy, Data safety form, listing disclosures and review video are complete and mutually consistent.
