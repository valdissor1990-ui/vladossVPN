# GOLDEN CONNECT — рабочий идеал и runbook восстановления

**Назначение:** единственный эталон стабильного VPN-подключения. При любой регрессии («Сеть заблокирована», ложный Connected, crash libbox, probe 5–20 ms) восстанавливать систему **по этому документу**.

| Поле | Значение |
|------|----------|
| Эталонная версия | **1.0.764** |
| Commit (доказанный лог) | `d2a49aa26ac305d524ef8f28119763a2143581ad` |
| Дата эталона | 2026-10-25 |
| Доказательство | `Connected` + Traffic probe `status=204` на 🇷🇺 VK — #36 и 🇩🇪 Germany — #87 |
| Лог-сессия | `vpnproxy_debug_20261025_132142` |
| Документ | `main` после merge phase 0 |

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
| tun.strict_route | `true` |
| tun.auto_route | `true` |
| tun.mtu | `1400` |
| route.final | `proxy` |
| route.auto_detect_interface | `true` |
| mixed | `127.0.0.1:10809` |

### protect()

Outbound к remote server **обязан** идти через `VpnService.protect(fd)` (`result=true` в логах).

### disallow self package

В эталонном логе: `USE_DISALLOW_SELF_PACKAGE=false`. Probe идёт через explicit proxy `10809`.

---

## 4. Эталонный TrafficValidator

1. Ждать `127.0.0.1:10809`.
2. HTTPS через **HTTP proxy** `127.0.0.1:10809` (CONNECT).
3. Targets: `cp.cloudflare.com/generate_204`, `connectivitycheck.gstatic.com/generate_204`.
4. Успех: **хотя бы один** target → 200/204.
5. Координатор: success → Connected; fail → skip endpoint + next; empty → fatal.
6. **Запрещён** global `trafficFailures` across разных серверов.

Тайминги: успех ~1.5–5 s; fail 5–50 ms = мёртвый endpoint; ~8 s = timeout.

---

## 5. DIAG / feature flags (эталон 764)

| Flag | Рабочее значение |
|------|------------------|
| dns_mode | `proxy_dot_1111` |
| tun_stack | `system` |
| route_final | `proxy` |
| auto_detect_interface | `true` |
| use_disallow_self_package | `false` |
| SKIP_TRAFFIC_VALIDATION | **false** |

---

## 6. Эталонный timeline 764

Selectel #64 fail → VK #36 status=204 (2356 ms) → Connected → Germany #87 status=204 (3364 ms).

---

## 7. Acceptance

- [ ] Connect без crash / empty direct outbound
- [ ] `DIAG-EFFECTIVE dns.servers[0].detour=proxy`
- [ ] `Traffic probe completed` + status 204/200
- [ ] `Verifying → Connected` reason=TrafficValidated
- [ ] `protect(fd=…) result=true`
- [ ] Внешний сайт через VPN; Disconnect без зависания

---

## 8. Симптомы → действия

### 8.1 Crash `empty direct outbound`
Убрать DNS detour=direct; вернуть §2.

### 8.2 Probe fail 5–50 ms
Probe через 10809; DNS §2; protect=true; multi-target; сравнить с Hiddify.

### 8.3 Fatal при живых нодах
Per-endpoint fallback; scanner не hard-filter; не stale TrafficValidationResult.

### 8.4 Connected без интернета
SKIP=false; Connected только после TrafficValidationResult(success=true).

### 8.5 Port 10809 not ready
Core start order; mixed inbound bind.

### 8.6 Sub refresh fail под VPN
Cache OK; не рвать сессию.

### 8.7 Бесконечный reconnect
Timeout через Channel; fatal lock только manual user Connect.

---

## 9. Карта файлов

| Область | Путь |
|---------|------|
| DNS / JSON | SingboxConfigBuilder.kt |
| Probe | TrafficValidator.kt |
| State | ConnectionCoordinator.kt |
| protect | SingBoxPlatform.kt |
| Service | VpnProxyService.kt |
| Scan | ServerScanner.kt |
| Flags | DebugFlags.kt |

Откат: DNS → Validator → Coordinator → Scanner → Platform.

---

## 10–13. Не эталон / обновление GOLDEN / checklist

Не эталон: SKIP=true; DNS detour=direct; Connected по TCP; global trafficFailures.

Обновлять GOLDEN только с логом TrafficValidated + version + commit.

Checklist «как 764»: DNS detour=proxy; route.final=proxy; mixed 10809; multi-target probe; SKIP=false; per-endpoint fallback; protect=true; acceptance §7.
