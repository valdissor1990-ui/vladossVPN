# ROADMAP — полное обновление и внедрение фич

**Статус:** активный план  
**Дата фиксации:** 2026-10-25  
**Эталон connect:** [GOLDEN_CONNECT.md](./GOLDEN_CONNECT.md) (v1.0.764 / `d2a49aa`)  
**Rollback tag:** `golden-1.0.764` → commit `d2a49aa26ac305d524ef8f28119763a2143581ad`

**Метрика успеха (0.4):** доля сессий с реальным интернетом через VPN за <15 с после Connect (цель: ≥90% на рабочей подписке).

---

## Правила выполнения

1. Идём **строго по фазам**. Не начинать фазу N+1, пока DoD фазы N не закрыт (кроме явно параллельной фазы 7).
2. Connect path не отходит от [GOLDEN_CONNECT.md](./GOLDEN_CONNECT.md).
3. Ветки: короткие `feat/*`, `fix/*`, `chore/*` от `main`; долгоживущих параллельных «истин» нет.
4. Релиз после фазы 1 и после фазы 2 — отдельные patch/minor; не смешивать bump ядра с UX-редизайном.

---

## Фаза 0 — Зафиксировать базу

| # | Задача | Статус |
|---|--------|--------|
| 0.1 | `docs/GOLDEN_CONNECT.md` в `main` | in progress |
| 0.2 | Tag `golden-1.0.764` на `d2a49aa` | pending (создать на GitHub) |
| 0.3 | Политика веток: только `main` + короткие topic-ветки | in progress (этот ROADMAP) |
| 0.4 | Метрика успеха зафиксирована | done (см. выше) |

**DoD фазы 0:** GOLDEN и ROADMAP в `main`; известен rollback commit/tag.

---

## Фаза 1 — Обновление ядра (3–5 дней)

| # | Задача | Статус |
|---|--------|--------|
| 1.1 | Bump sing-box-lx → **1.14.4-lx.35** (`download_libbox.sh` + SHA + CI) | done (ветка `chore/deps-upgrade-latest-stable`) |
| 1.2 | Оценить base **1.14.4** (когда lx догонит) | done (база 1.14.4 включена в lx.35) |
| 1.3 | Регресс по GOLDEN (DNS detour=proxy, multi-target probe) | pending |
| 1.4 | Матрица протоколов на device: Reality/Vision, XHTTP, WS, H2, TUIC | pending |
| 1.5 | Soft-restart / urltest после lx.23 | pending |
| 1.6 | Patch-релиз только с bump ядра | pending |

**Не делать:** 1.15 alpha в production.

**DoD:** acceptance §7 GOLDEN зелёный; XHTTP не хуже 764.

---

## Фаза 2 — Стабильность connect (1–2 недели)

| # | Задача | P | Статус |
|---|--------|---|--------|
| 2.1 | Структурированный результат probe | P0 | pending |
| 2.2 | Fallback per-endpoint (без global trafficFailures) | P0 | pending |
| 2.3 | Понятные fatal-сообщения | P0 | pending |
| 2.4 | Scanner: TCP = sort hint | P0 | pending |
| 2.5 | HealthChecker только через 10809 при VPN | P0 | pending |
| 2.6 | Actor: timeout/fatal только через Channel | P0 | pending |
| 2.7 | Persist «реально работавших» нод | P0 | pending |
| 2.8 | Unit/instrument на coordinator + validator | P1 | pending |
| 2.9 | Multi-source sub + cache при fail под VPN | P0 | pending |

**DoD:** ≥90% успешных connect; 0 ложных Connected.

---

## Фаза 3 — Продуктовый клиент (2–3 недели)

| # | Фича | Статус |
|---|------|--------|
| 3.1 | Home UX (кнопка, сервер, latency, статус) | pending |
| 3.2 | Онбординг (sub / QR / буфер → Connect) | pending |
| 3.3 | Multi-subscription UI | pending |
| 3.4 | QR import / share | pending |
| 3.5 | Split tunneling UI | pending |
| 3.6 | Always-on + network reconnect | pending |
| 3.7 | QS Tile / виджет | done (QS-плитка, PR #288) |
| 3.8 | Честные статусы нод | pending |
| 3.9 | Трафик / сессия в UI | pending |

**DoD:** новый пользователь подключается без logcat.

---

## Фаза 4 — Routing и DNS (≈2 недели)

| # | Фича | Статус |
|---|------|--------|
| 4.1 | Правила маршрутизации UI | pending |
| 4.2 | Пресеты (bypass RU / всё VPN / свои) | done (пресеты «всё через VPN», «обход локальной сети», «обход РФ»; см. Settings → Маршрутизация) |
| 4.3 | DNS modes в UI (эталон DoT via proxy) | pending |
| 4.4 | Remote rule-set (опционально) | pending |
| 4.5 | Per-app + domain приоритеты | pending |

---

## Фаза 5 — Умный connect 2.0 (1–2 недели)

| # | Задача | Статус |
|---|--------|--------|
| 5.1 | Протокольный pre-check топ-N | pending |
| 5.2 | Scoring: latency + traffic-success + fail streak | pending |
| 5.3 | Мягкий auto-switch при деградации | pending |
| 5.4 | DeepProbe не ломает активный VPN | pending |

---

## Фаза 6 — Протоколы и ядро (ongoing)

| # | Задача | Статус |
|---|--------|--------|
| 6.1 | AmneziaWG по спросу | pending |
| 6.2 | XHTTP modes / extra parity после каждого bump lx | pending |
| 6.3 | WireGuard при необходимости | pending |
| 6.4 | Следить 1.14.4+ / lx на новом base | pending |
| 6.5 | Не 1.15 alpha в release | policy |

---

## Фаза 7 — Quality / security / distro (параллельно с 3–5)

| # | Задача | Статус |
|---|--------|--------|
| 7.1 | Не логировать UUID/ключи | pending |
| 7.2 | Аудит EncryptedSharedPreferences / Keystore | pending |
| 7.3 | E2E: живой / мёртвый / TCP-only | pending |
| 7.4 | 50 циклов Connect/Disconnect без crash | pending |
| 7.5 | Бренд (имя, иконка, канал) | pending |
| 7.6 | GitHub Releases + Obtainium; Play отдельно | pending |
| 7.7 | Кураторский список нод (опционально) | pending |

---

## Фаза 8 — Монетизация (после 3–5)

| # | Вариант | Статус |
|---|---------|--------|
| 8.1 | Free client + свои/партнёрские ноды | later |
| 8.2 | Freemium advanced features | later |
| 8.3 | White-label | later |

---

## Зависимости

```
0 База + GOLDEN
  → 1 Bump ядра
    → 2 Стабильность connect  ⇄  7 Quality
      → 3 UX / multi-sub / split / always-on
        → 4 Routing UI + DNS
          → 5 Smart connect 2.0
            → 6 Доп. протоколы
              → 8 Монетизация
```

---

## Definition of Done (весь план)

1. Ядро ≥ 1.14.4-lx.35 (stable lx на base 1.14.4).
2. GOLDEN acceptance стабильно зелёный.
3. Multi-sub + QR + split UI + always-on.
4. Базовые routing-правила без правки JSON.
5. Smart-select с протокольной проверкой топ-N.
6. Предсказуемый релиз; rollback на golden-tag <1 час.

---

## Changelog плана

| Дата | Изменение |
|------|-----------|
| 2026-10-25 | Первая фиксация плана; старт фазы 0 |
| 2026-11-21 | Обновлён тулчейн и зависимости (AGP 8.13.2, Gradle 8.14.8, Kotlin 2.3.21, Compose BOM 2026.06.01) и ядро до 1.14.4-lx.35; фаза 1.1/1.2 закрыты. См. `docs/DEPENDENCY_UPGRADE_2026-11.md` |
| 2026-11-22 | Гигиена и производительность: горячий путь логирования переведён на O(1) append + батч-запись файла, release-сборка больше не пишет `log.level=debug`, один OkHttp-клиент на все пробы, backoff фонового скана, удалён мёртвый `core/protocols/` и `NeonConnectButton`. Полный импорт Clash/JSON для vmess/trojan/ss/socks5/hysteria2/tuic. См. `docs/PROTOCOLS_WIRING.md` |
| 2026-11-22 | Маршрутизация: локальная сеть больше не уходит в туннель (`ip_is_private → direct`), пресеты «всё через VPN» / «обход локальной сети» (по умолчанию) / «обход РФ». Правила РФ — локальные `.srs` (`geosite-category-ru`, `geoip-ru`), скачиваются только при выборе пресета; при их отсутствии пресет деградирует до обхода локальной сети. QS-плитка добавлена (PR #288). |
