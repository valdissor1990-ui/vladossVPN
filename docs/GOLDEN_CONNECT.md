# GOLDEN CONNECT — рабочий идеал и runbook восстановления

**Назначение:** единственный эталон стабильного VPN-подключения. При любой регрессии («Сеть заблокирована», ложный Connected, crash libbox, probe 5–20 ms) восстанавливать систему **по этому документу**.

| Поле | Значение |
|------|----------|
| Эталонная версия | **1.0.764** |
| Commit (доказанный лог) | `d2a49aa26ac305d524ef8f28119763a2143581ad` |
| Дата эталона | 2026-10-25 |
| Доказательство | `Connected` + Traffic probe `status=204` на 🇷🇺 VK — #36 и 🇩🇪 Germany — #87 |
| Лог-сессия | `vpnproxy_debug_20261025_132142` |
| Ветка документа | `docs/golden-connect-baseline` |

---

## 0. Как пользоваться этим файлом

1. Определи симптом → раздел **§8 Симптомы → действия**.
2. Сверь текущий generated JSON и логи с **§2–§5** (эталонные фрагменты).
3. Внеси только изменения, возвращающие эталон; не смешивай с новыми фичами в одном коммите.
4. После фикса прогони **§7 Acceptance** на device.
5. Обновляй этот файл только по правилам **§12**.

---

## 1. Незыблемые правила

1. **Connected только после успешной traffic validation** (HTTP 200/204 через туннель).  
   В release запрещён постоянный `SKIP_TRAFFIC_VALIDATION = true`.
2. **DNS для пользовательского трафика и probe — через outbound `proxy`**, не «голый» UDP direct под strict TUN.
3. **Запрещён** `"detour": "direct"` на DNS, если libbox отвечает  
   `detour to an empty direct outbound makes no sense`.
4. **Один failed endpoint ≠ «все серверы мертвы».**  
   Скип этого endpoint (и exact duplicates) → fallback на следующий.  
   Fatal — только когда кандидаты закончились.
5. **TCP reachability ≠ VPN works.**  
   `ServerScanner` только сортирует; решение о Connected — только traffic probe.
6. **Probe всегда через local mixed** `127.0.0.1:10809`, не через «системный» HttpURLConnection в обход туннеля.
7. **Timeout / fatal из actor** — только через event Channel, не прямым вызовом `applyFatal` из посторонней корутины (избегаем гонок state machine).

---

## 2. Эталонный DNS (критично)

Рабочая схема из 1.0.764 (`dns_mode=proxy_dot_1111`):

```json
"dns": {
  "servers": [
    {
      "type": "tls",
      "tag": "proxy-dot",
      "server": "1.1.1.1",
      "server_port": 853,
      "detour": "proxy"
    }
  ],
  "final": "proxy-dot",
  "strategy": "prefer_ipv4"
}
```

| Параметр | Эталон | Не делать |
|----------|--------|-----------|
| type | `tls` (DoT) | единственный `udp` 8.8.8.8 без пути через proxy при strict_route |
| server | `1.1.1.1` | — |
| detour | **`proxy`** | `direct` (ломает libbox 1.14.3) |
| final | `proxy-dot` | `direct-dns` как единственный путь без device-proof |

### Почему так

- При `strict_route=true` трафик (включая DNS) должен иметь предсказуемый путь.
- DoT на 1.1.1.1 с `detour: "proxy"` резолвит имена **через тот же outbound**, что и data-plane.
- В логе 764: `DIAG-EFFECTIVE dns.servers[0].type=tls`, `detour=proxy`, `server=1.1.1.1`.

### Восстановление DNS (пошагово)

1. Открыть `SingboxConfigBuilder.kt` (или актуальный builder config).
2. Удалить любой DNS server с `"detour": "direct"`.
3. Выставить servers как в JSON выше (`type=tls`, `detour=proxy`, `final=proxy-dot`).
4. Собрать release / diag build, на device в логе после start проверить:
   - `DIAG-EFFECTIVE dns.servers[0].detour=proxy`
   - нет строки `detour to an empty direct outbound`
5. Прогнать Connect → ожидать probe success на живой ноде.

Альтернатива (только если DoT недоступен на целевых сетях): другой DNS **через тот же `proxy`**, согласованный с sing-box 1.12+ и `protect()`. Не возвращать «direct UDP only» без теста на device с §7.

---

## 3. Эталонный TUN / route / mixed

Из DIAG-EFFECTIVE рабочей сессии:

```json
"inbounds": [
  {
    "type": "tun",
    "tag": "tun-in",
    "interface_name": "vpnproxy0",
    "address": ["172.19.0.1/30", "fdfe:dcba:9876::1/126"],
    "mtu": 1400,
    "auto_route": true,
    "strict_route": true,
    "stack": "system"
  },
  {
    "type": "mixed",
    "tag": "local-proxy-in",
    "listen": "127.0.0.1",
    "listen_port": 10809
  }
],
"route": {
  "auto_detect_interface": true,
  "rules": [
    { "action": "sniff" },
    { "protocol": "dns", "action": "hijack-dns" },
    { "inbound": ["local-proxy-in"], "outbound": "proxy" }
  ],
  "final": "proxy"
}
```

| Параметр | Эталон |
|----------|--------|
| tun.stack | `system` |
| tun.strict_route | `true` (effective в рабочем коннекте) |
| tun.auto_route | `true` |
| tun.mtu | `1400` |
| route.final | `proxy` |
| route.auto_detect_interface | `true` |
| mixed | `127.0.0.1:10809` |

### protect()

Outbound к remote server **обязан** идти через `VpnService.protect(fd)`:

```
SingBoxPlatform | protect(fd=…) result=true
```

Если `result=false` или protect не вызывается — возможен routing loop или обрыв outbound.

### disallow self package

В эталонном логе:

```
USE_DISALLOW_SELF_PACKAGE=false
BUILDER disallowSelf=false
BUILDER allowedApps=0
BUILDER disallowedApps=0
```

Probe при этом всё равно идёт через explicit proxy `10809`.  
Если на конкретной прошивке появится loop — документировать отдельно и не менять GOLDEN без device-proof.

---

## 4. Эталонный TrafficValidator

### Алгоритм

1. Дождаться accept на `127.0.0.1:10809` (poll, таймаут порядка 3 s).
2. Для каждого target из списка (порядок важен — Cloudflare первым):
   - Открыть HTTPS через **HTTP proxy** `127.0.0.1:10809` (HTTP CONNECT; не SOCKS-only путь, ломавший SNI).
   - connectTimeout / readTimeout порядка **8 s** (как в рабочем коде; успех в логе был 2.3–3.4 s).
   - Успех target: response code **200 или 204**.
3. Если **хотя бы один** target успешен → `success=true`.
4. Если все упали → `success=false`, в лог: список `target:exception`.

### Обязательные target (минимум два)

1. `https://cp.cloudflare.com/generate_204`
2. `https://connectivitycheck.gstatic.com/generate_204` (или актуальный аналог)

### Обязательные поля лога

```
Traffic probe completed | {server=…, target=…, proxy=http://127.0.0.1:10809, status=204, elapsedMs=…, success=true}
Traffic probe failed    | {server=…, target=…, proxy=http://127.0.0.1:10809, elapsedMs=…, exception=…, message=…}
All traffic probe targets failed | {server=…, failures=…}
```

### Тайминги (ориентир из 764 и регрессий)

| Исход | elapsedMs | Интерпретация |
|-------|-----------|----------------|
| Успех | ~1.5–5 s | Нормальный TLS + ответ (эталон: 2356 ms, 3364 ms) |
| Fail 5–50 ms | SSL close / reset | Обычно мёртвый/битый endpoint или обрыв до handshake |
| Fail ~8 s | timeout | Медленный путь / DPI / фильтрация |
| Port not ready | — | mixed inbound не поднялся — чинить start core, не fallback-логику |

### Координатор после результата probe

| Результат | Действие |
|-----------|----------|
| success=true | `Connected`, reason=`TrafficValidated`; сброс retry/fallback failure counters |
| success=false | Удалить endpoint (+ exact duplicates: address+port+transport+security); `ServerSelected` на next |
| candidates empty | fatal lock + сообщение; не крутить бесконечный reconnect |

**Запрещено:** общий `trafficFailures++` across different servers с порогом «3 разных сервера = skip текущего».

---

## 5. DIAG / feature flags (эталон 764)

| Flag | Рабочее значение | Комментарий |
|------|------------------|-----------|
| dns_mode | `proxy_dot_1111` | DoT 1.1.1.1 + detour proxy |
| tun_stack | `system` | |
| route_final | `proxy` | |
| auto_detect_interface | `true` | |
| use_disallow_self_package | `false` | См. §3 |
| passive_probe_enabled | `true` | Не заменяет active validation перед Connected |
| freeze_autoswitcher | `false` | |
| SKIP_TRAFFIC_VALIDATION | **false** | Только разовая диагностика |

В логе после старта VPN должны быть строки вида:

```
DIAG-SETTINGS DNS_MODE=proxy_dot_1111 …
DIAG-EFFECTIVE dns.servers[0].type=tls
DIAG-EFFECTIVE dns.servers[0].detour=proxy
DIAG-EFFECTIVE dns.servers[0].server=1.1.1.1
DIAG-EFFECTIVE route.final=proxy
DIAG-EFFECTIVE inbounds[tun].strict_route=true
```

---

## 6. Эталонный сценарий из лога 764 (timeline)

| Шаг | Событие |
|-----|---------|
| 1 | ConnectRequested (userInitiated) → Connecting |
| 2 | Scan 81 profiles, TCP 81/81 |
| 3 | Select 🇷🇺 Selectel #64 (xhttp) |
| 4 | Probe fail оба target (~185–236 ms, SSLHandshakeException) |
| 5 | Fallback → 🇷🇺 VK #36 |
| 6 | Probe OK `cp.cloudflare.com` status=204 elapsedMs=2356 → **Connected** |
| 7 | Позже switch → 🇩🇪 Germany #87 (ws/tls) |
| 8 | Probe OK status=204 elapsedMs=3364 → **Connected** |

Вывод для восстановления: fallback после одного fail + multi-target + DNS through proxy = рабочий path.

---

## 7. Acceptance (обязательный прогон после фикса)

На физическом device с **заведомо живой** подпиской (проверенной в Hiddify/v2rayNG):

- [ ] Cold start приложения, refresh подписки (или cache).
- [ ] Connect (ручной).
- [ ] Sing-box start **без** crash / без `empty direct outbound`.
- [ ] В логе: `DIAG-EFFECTIVE dns.servers[0].detour=proxy` (или актуальный эталон §2).
- [ ] Хотя бы один сервер: `Traffic probe completed` + `status=204` или `200`.
- [ ] State: `Verifying → Connected` reason=`TrafficValidated`.
- [ ] После Connected: `protect(fd=…) result=true`.
- [ ] Браузер/клиент открывает внешний сайт через VPN.
- [ ] Отключить VPN → Disconnected без зависания service.

Если probe fail на ноде, которая работает в другом клиенте — это **регрессия клиента**, не «плохая подписка».

---

## 8. Симптомы → действия (runbook)

### 8.1 libbox / startCore crash

**Сигнатуры лога:**

```
detour to an empty direct outbound makes no sense
startCore failed
Sing-box … error
```

**Действия:**

1. `SingboxConfigBuilder` — убрать все DNS `"detour": "direct"`.
2. Вернуть DNS §2 (`tls` + `detour: "proxy"`).
3. Проверить, что outbound tag `proxy` существует до ссылок на него.
4. Пересобрать → Connect → нет crash.

### 8.2 Все probe fail за 5–50 ms (connection closed / reset)

**Сигнатуры:**

```
Traffic probe failed | … elapsedMs=7 … SSLHandshakeException … connection closed
Traffic probe failed | … elapsedMs=10 … SocketException … Connection reset
```

**Действия:**

1. Убедиться, что URLConnection идёт через `Proxy(HTTP, 127.0.0.1:10809)`.
2. DNS = §2.
3. В логе есть `protect … result=true` сразу после start.
4. Multi-target включен; смотреть `All traffic probe targets failed`.
5. Тот же URI в Hiddify: если там OK — баг клиента; если там тоже fail — подписка/нода.
6. Не лечить это `SKIP_TRAFFIC_VALIDATION=true` в release.

### 8.3 Fatal «Сеть заблокирована» при живых нодах

**Сигнатуры:**

```
Traffic validation exhausted all fallback endpoints; fatal lock enabled
Error(message=Сеть заблокирована или недоступна
```

**Действия:**

1. `ConnectionCoordinator`: один fail → skip endpoint → next; **нет** global counter across servers.
2. `ServerScanner.availableServers()` **не** отбрасывает всех с TCP fail (UDP-протоколы / hint only).
3. `SetFallbackCandidates` не пустой после scan.
4. Проверить, что не срабатывает stale `TrafficValidationResult` не на тот serverName.

### 8.4 Connected, но интернета нет

**Действия:**

1. `DebugFlags.SKIP_TRAFFIC_VALIDATION` должен быть `false`.
2. В state machine путь в Connected **только** из `TrafficValidationResult(success=true)`.
3. Проверить route.final=proxy и что system traffic реально в TUN (не только local probe).

### 8.5 Port 10809 not ready

**Сигнатуры:**

```
Port 10809 not ready after …ms
```

**Действия:**

1. Core реально стартовал (`Sing-box VPN started successfully`).
2. mixed inbound в JSON на `127.0.0.1:10809`.
3. Нет гонки: ServiceReady не раньше bind mixed.
4. Увеличивать wait только после проверки start order.

### 8.6 Подписка не обновляется / All sources failed под VPN

**Сигнатуры:**

```
SubRepo refresh failed while VPN active
Source failed: https://… Failed to connect
Using cached configs
```

**Действия:**

1. Не рвать VPN-сессию из-за failed refresh — cache допустим.
2. Fetch при активном VPN должен учитывать tunnel/protect (или идти до поднятия TUN).
3. Не трактовать failed refresh как fatal connect.

### 8.7 Бесконечный reconnect / timeout в Error

**Действия:**

1. Timeout в Connecting/Verifying → событие в Channel, не прямой `applyFatal` из timer без синхронизации.
2. fatal lock сбрасывается только manual ConnectRequested(userInitiated=true).
3. Watchdog не должен слать Timeout в уже Error без игнора.

---

## 9. Карта файлов для восстановления

| Область | Путь (типичный) | Что вернуть к эталону |
|---------|------------------|------------------------|
| DNS / JSON | `…/singbox/SingboxConfigBuilder.kt` | §2 DNS, §3 route/tun/mixed |
| Probe | `…/connection/TrafficValidator.kt` | multi-target, proxy 10809, 200/204 |
| State / fallback | `…/connection/ConnectionCoordinator.kt` | per-endpoint skip, Connected only after validate |
| TUN / protect | `…/singbox/SingBoxPlatform.kt` | protect=true, builder flags |
| Service / diag | `…/service/VpnProxyService.kt` | DIAG-SETTINGS/EFFECTIVE, start order |
| TCP scan | `…/scanner/ServerScanner.kt` | TCP = sort hint, not hard filter |
| Flags | `…/util/DebugFlags.kt` | SKIP_TRAFFIC_VALIDATION=false |

Порядок отката при «всё сломалось»:

1. DNS (§2)  
2. TrafficValidator (§4)  
3. ConnectionCoordinator fallback (§4 / §8.3)  
4. ServerScanner filter  
5. Platform protect / disallow  

---

## 10. Шаблоны «плохих» vs «хороших» логов

### Хороший (эталон)

```
DIAG-EFFECTIVE dns.servers[0].detour=proxy
Sing-box VPN started successfully
Traffic probe completed | {… target=cp.cloudflare.com … status=204 … success=true}
Traffic validation passed for …
STATE: Verifying(…) -> Connected(…) | reason=TrafficValidated
protect(fd=…) result=true
```

### Плохой (регрессия DNS)

```
detour to an empty direct outbound makes no sense
```

### Плохой (регрессия probe / нода)

```
Traffic probe failed | {… elapsedMs=8 … connection closed}
Traffic validation exhausted all fallback endpoints
STATE: … -> Error(message=Сеть заблокирована…
```

### Плохой (ложный Connected)

```
DEBUG: SKIP_TRAFFIC_VALIDATION=true
STATE: … -> Connected(…)   // без Traffic probe completed success=true
```

---

## 11. Что сознательно не эталон

- Любая сборка с постоянным `SKIP_TRAFFIC_VALIDATION=true`.
- DNS `udp/8.8.8.8` без path через proxy при `strict_route=true` без device-proof.
- `"detour": "direct"` на DNS в libbox 1.14.3.
- Connected по одному TCP ping / successRate.
- Global `trafficFailures` на разные endpoint’ы.
- Hard-filter кандидатов только по TCP.

---

## 12. Обновление этого документа

Менять GOLDEN только если **все** условия выполнены:

1. На device воспроизведён стабильный connect path **не хуже** текущего эталона.  
2. Есть полный лог с `TrafficValidated` и `status=204` или `200`.  
3. Зафиксированы: versionName, versionCode, commit SHA, дата, device/API.  
4. Изменения описаны diff’ом к §2–§5 (DNS/route/probe/flags).  

Временные diag-эксперименты (перебор strict_route, stack, disallow) **не** переписывают GOLDEN, пока не пройден §7 на устойчивой серии подключений.

---

## 13. Быстрый checklist «вернуть как 764»

Скопировать в issue/PR при регрессе:

- [ ] DNS: type=tls, server=1.1.1.1:853, **detour=proxy**, final=proxy-dot  
- [ ] Нет DNS detour=direct  
- [ ] route.final=proxy, auto_detect_interface=true  
- [ ] tun: system, auto_route=true, strict_route=true, mtu=1400  
- [ ] mixed 127.0.0.1:10809  
- [ ] TrafficValidator: proxy 10809, ≥2 targets, success на 200/204  
- [ ] SKIP_TRAFFIC_VALIDATION=false  
- [ ] Fallback: 1 endpoint fail → next; fatal только при пустом списке  
- [ ] Scanner не режет весь список по TCP  
- [ ] protect() result=true  
- [ ] Acceptance §7 пройден на device  
