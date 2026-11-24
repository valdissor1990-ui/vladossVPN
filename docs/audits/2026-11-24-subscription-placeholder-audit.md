# Аудит: «1 сервер на мобильной сети вместо 20» (subscription placeholder)

- Дата: 2026-11-24
- Ветка-источник: feat/subscription-update-diff (e1e253cd)
- База исправлений: origin/main (696804e9)
- Ветка исправлений: task/subscription-placeholder-hardening
- Вердикт по исходной ветке: REQUEST CHANGES
| Severity | Место | Находка | Статус |
| --- | --- | --- | --- |
| High | ветка | База отставала от origin/main на 143 коммита | Исправлено: ветка создана от origin/main |
| High | ProbeEvidence.kt | Удаление generation из sameNetwork ломало per-network инвалидацию | Не переносится: в origin/main корректная модель |
| High | SubscriptionRepository.kt | Удаление legacy-ключей до чтения теряло кэш | Не переносится: в origin/main есть миграция d22e4d19 |
| Medium | SubscriptionParser.kt | isValid принимает loopback/unspecified адреса | Исправлено |
| Medium | SubscriptionParser.kt | IPv6-формы loopback и host/sni не проверялись | Исправлено |
| Medium | SubscriptionRepository.kt | Детектор заглушки не распознавал URL-safe Base64 | Исправлено |
| Medium | SubscriptionRepository.kt | Частичная заглушка перезаписывала кэш | Исправлено: guard по сокращению списка |
| Medium | HomeViewModel.kt | targetAvailableCount отключал ранний стоп | Исправлено: цель 5 профилей |
| Medium | HomeViewModel.kt | Таймаут 80 с давал ошибку вместо частичных результатов | Исправлено: fallback на частичные |
| Medium | HomeViewModel.kt | Хвост непроверенных профилей без ограничения | Исправлено: не более 3 |
| Low | DeepProbeEngine.kt | TCP-fallback как успех расходился с upstream | Не переносится: в origin/main TCP только подсказка |
| Low | тесты | Покрытие только isValid | Расширено: IPv6, stub-детектор, host/sni |

## Причина дефекта

Панель отдаёт короткую заглушку «Лимит устройств» с двумя loopback-профилями для нежелательного User-Agent.
Парсер считал такие профили валидными, refreshAll записывал их в кэш, и рабочий список из 20 серверов заменялся нерабочими записями.
Дополнительные факторы: разные ключи кэша, глобальный generation в evidence и отсутствие TCP/HTTP-fallback на мобильной сети.

## Проверки
- testDirectDebugUnitTest и compileDirectDebugKotlin (журнал CI)
- реальная панель: заглушка 324 байта, 2 loopback-хоста, 20 серверов для v2rayNG

## Риски интеграции
- Ветка исправлений создана от origin/main, исходная ветка feat/subscription-update-diff не мержится напрямую.
- Перед релизом обязательны зелёные build.yml и unit-тесты.
