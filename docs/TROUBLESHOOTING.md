# Troubleshooting

## Сборка

### `UnsatisfiedLinkError: dlopen failed: library "libxray.so" not found`
Причина: не выполнен `scripts/download_xray.sh` или ABI не совпадает.
Решение:
```bash
chmod +x scripts/download_xray.sh
./scripts/download_xray.sh
./gradlew clean assembleDirectDebug
```

Для production используйте AndroidLibXrayLite.aar из официального репозитория.

### Keystore was tampered with, or password was incorrect
Причина: неверный KEYSTORE_PASSWORD или KEY_PASSWORD.
Решение: перепроверьте значения. В CI — Secrets; локально — keystore.properties.

## Runtime

### VPN не подключается, статус «Ошибка»
1. Откройте DebugScreen → фильтр ERROR.
2. Ищите записи с тегами XrayCore, TunnelManager, VpnProxyService.
3. Типичные причины:
   · Неверный UUID / pbk / sid в подписке.
   · Reality-сервер за Cloudflare (несовместимо — только point-to-point).
   · flow: xtls-rprx-vision на XHTTP — критическая ошибка.
   · mode: auto на XHTTP — использовать только stream-one.

### «Установка заблокирована» при обновлении
Причина: не выдано разрешение «Установка неизвестных приложений».
Решение: Настройки → Приложения → VPNProxy → Установка неизвестных приложений → Разрешить.

### INSTALL_FAILED_UPDATE_INCOMPATIBLE
Причина: APK подписан другим keystore, чем установленная версия.
Решение: удалить приложение и установить заново (данные будут потеряны).
Никогда не меняйте keystore между релизами.

### Обновление не приходит автоматически
1. Проверьте BuildConfig.GITHUB_OWNER и GITHUB_REPO.
2. Убедитесь, что релиз на GitHub помечен как latest (не draft, не prerelease).
3. WorkManager может откладывать задачи в Doze — откройте приложение вручную.
4. Проверьте через DebugScreen: ищите UpdateChecker.
