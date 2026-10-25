# GOLDEN CONNECT — рабочий идеал

**Назначение:** эталон стабильного подключения. При регрессии («Сеть заблокирована», ложный Connected, падение libbox) восстанавливать поведение **по этому документу**, а не наугад.

| Поле | Значение |
|------|----------|
| Эталонная версия | **1.0.764** |
| Commit (доказанный лог) | `d2a49aa26ac305d524ef8f28119763a2143581ad` |
| Дата эталона | 2026-10-25 |
| Доказательство | `Connected` + Traffic probe `status=204` на VK #36 и Germany #87 |
| Лог-сессия | `vpnproxy_debug_20261025_132142` |

---

## 1. Незыблемые правила

1. **Connected только после успешной traffic validation** (HTTP 200/204 через туннель). Не возвращать постоянный `SKIP_TRAFFIC_VALIDATION=true`.
2. **DNS резолв для пользовательского трафика и probe — через proxy**, не «голый» UDP direct под strict TUN.
3. **Запрещён** `"detour": "direct"` на DNS, если libbox отвечает `detour to an empty direct outbound makes no sense`.
4. **Один failed endpoint ≠ «все серверы мертвы».** Скип этого endpoint (и дубликатов), fallback на следующий. Fatal — только когда кандидаты кончились.
5. **TCP reachability ≠ VPN works.** Сканер только сортирует; решение о Connected — только traffic probe.

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
| type | `tls` (DoT) | только `udp` 8.8.8.8 без пути через proxy под strict_route |
| server | `1.1.1.1` | — |
| detour | **`proxy`** | `direct` (ломает libbox 1.14.3) |
| final | `proxy-dot` | `direct-dns` как единственный путь без проверки |

Альтернатива (если DoT недоступен): DNS через тот же outbound `proxy` другим безопасным способом, согласованным с sing-box 1.12+ и `protect()`. Не возвращать «direct UDP only» без теста на device.

---

## 3. Эталонный TUN / route

Из DIAG-EFFECTIVE рабочей сессии:

```json
"inbounds": [{
  "type": "tun",
  "tag": "tun-in",
  "interface_name": "vpnproxy0",
  "address": ["172.19.0.1/30", "fdfe:dcba:9876::1/126"],
  "mtu": 1400,
  "auto_route": true,
  "strict_route": true,
  "stack": "system"
}, {
  "type": "mixed",
  "tag": "local-proxy-in",
  "listen": "127.0.0.1",
  "listen_port": 10809
}],
"route": {
  "auto_detect_interface": true,
  "rules": [
    {"action": "sniff"},
    {"protocol": "dns", "action": "hijack-dns"},
    {"inbound": ["local-proxy-in"], "outbound": "proxy"}
  ],
  "final": "proxy"
}
```

| Параметр | Эталон |
|----------|--------|
| tun.stack | `system` |
| tun.strict_route | `true` (в effective рабочего коннекта) |
| tun.auto_route | `true` |
| tun.mtu | `1400` |
| route.final | `proxy` |
| route.auto_detect_interface | `true` |
| mixed listen | `127.0.0.1:10809` |

Outbound приложения к remote server — через `protect(fd)` (`result=true` в логах).

---

## 4. Эталонный TrafficValidator

### Поведение

1. Ждать готовности `127.0.0.1:10809`.
2. HTTP CONNECT (или эквивалент) через local mixed proxy — **не** «голый» HttpURLConnection в обход туннеля.
3. **Несколько target** (минимум два):
   - `https://cp.cloudflare.com/generate_204`
   - `https://connectivitycheck.gstatic.com/generate_204` (или актуальный аналог)
4. Успех: **хотя бы один** target вернул **200 или 204**.
5. Логировать: `server`, `target`, `status`/`exception`, `elapsedMs`, `success`.

### Ожидаемые тайминги (ориентир)

| Исход | elapsedMs | Интерпретация |
|-------|-----------|----------------|
| Успех | ~1.5–5 s | Нормальный TLS + ответ |
| Fail 5–50 ms | SSL close / reset | Скорее мёртвый/битый endpoint |
| Fail ~8 s | timeout | Медленный/режется путь |

### Координатор

- `success=true` → `Connected`, сброс failure-счётчиков.
- `success=false` → убрать этот endpoint (+ exact duplicates), взять следующий кандидат.
- Кандидатов нет → fatal + понятное сообщение (подписка/ноды, не всегда «проверьте интернет»).
- **Не** накапливать `trafficFailures` как общий счётчик между **разными** серверами.

---

## 5. DIAG / feature flags (как в рабочем логе)

| Flag | Рабочее значение | Комментарий |
|------|------------------|-------------|
| dns_mode | `proxy_dot_1111` | DoT 1.1.1.1 + detour proxy |
| tun_stack | `system` | |
| route_final | `proxy` | |
| auto_detect_interface | `true` | |
| use_disallow_self_package | `false` | В 764 так; при loop на прошивке — пересмотреть |
| passive_probe_enabled | `true` | Не заменяет active validation перед Connected |
| freeze_autoswitcher | `false` | |
| SKIP_TRAFFIC_VALIDATION | **false** | Только временная диагностика |

---

## 6. Минимальный успешный сценарий (acceptance)

На device с живой подпиской:

1. Connect (userInitiated).
2. Sing-box start без crash.
3. Хотя бы один сервер: `Traffic probe completed` + `status=204` (или 200).
4. State: `Verifying → Connected` с `reason=TrafficValidated`.
5. В логе: `dns.servers[0].detour=proxy` (или эквивалент эталона), `route.final=proxy`.
6. После Connected — `protect(fd=...) result=true` на outbound.

Если п.3–4 нет при заведомо живой ноде в Hiddify/v2rayNG — регрессия относительно GOLDEN, чинить по §7.

---

## 7. Чеклист восстановления при поломке

### Симптом: libbox crash при старте

- [ ] Убрать DNS `detour: "direct"`.
- [ ] Вернуть DNS как в §2 (`tls` + `detour: "proxy"`).
- [ ] Проверить JSON outbound/route на пустые tag.

### Симптом: все probe fail за 5–20 ms (connection closed / reset)

- [ ] Проверить, что probe идёт на `127.0.0.1:10809`, не в обход.
- [ ] DNS = §2 (через proxy).
- [ ] `protect()` вызывается и возвращает true.
- [ ] Сравнить ту же подписку в другом клиенте (нода vs приложение).
- [ ] Multi-target probe включен (§4).

### Симптом: fatal сразу / «Сеть заблокирована» при живых нодах

- [ ] Fallback: один fail → next server, не общий счётчик на всех.
- [ ] Не фильтровать кандидатов только по TCP successRate.
- [ ] Список fallback не пустой после scan.

### Симптом: Connected без интернета

- [ ] Убедиться что `SKIP_TRAFFIC_VALIDATION=false`.
- [ ] Connected только из `TrafficValidationResult(success=true)`.

### Симптом: подписка не обновляется под VPN

- [ ] Refresh подписки при активном VPN не должен валить сессию (cache OK).
- [ ] Fetch подписки — с учётом tunnel/protect, не обязательно «прямой» интернет.

---

## 8. Файлы, которые обычно трогают при регрессе

| Область | Типичный путь |
|---------|----------------|
| DNS / JSON config | `SingboxConfigBuilder.kt` |
| Probe | `TrafficValidator.kt` |
| State / fallback | `ConnectionCoordinator.kt` |
| TUN / protect / disallow | `SingBoxPlatform.kt`, `VpnProxyService.kt` |
| TCP scan vs availability | `ServerScanner.kt` |
| Debug flags | `DebugFlags.kt` |

При сомнении: **сначала** вернуть поведение §2–§4, **потом** экспериментировать с diag-флагами.

---

## 9. Что сознательно не эталон

- Любая версия с `SKIP_TRAFFIC_VALIDATION=true` как «рабочая» (ложный Connected).
- DNS `udp/8.8.8.8` без пути через proxy под `strict_route=true` без device-proof.
- `detour: "direct"` на DNS в libbox 1.14.3.
- Connected по одному TCP ping.

---

## 10. Обновление этого документа

Менять GOLDEN только если:

1. На device воспроизведён **лучший** стабильный connect path, и
2. Есть лог с `TrafficValidated` + `status=204/200`, и
3. Зафиксированы versionName, commit SHA и дата.

Иначе документ не переписывать под временные diag-эксперименты.
