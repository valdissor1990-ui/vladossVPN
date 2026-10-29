# Big Update Plan — Reliability First

Branch: `feat/dual-path-ai-foreign` (HEAD includes dual-path service wire)

## Self-prompt (working charter)

You are hardening vladossVPN for production use on Android (Xiaomi / Android 13+).
Priorities in order:

1. **Do not break working VPN path** (libbox 1.14.3, TUN, protect, subscription parse).
2. **Reliability**: boot reconnect, network change, less flapping.
3. **Battery**: longer intervals under saver/economy; no useless probes.
4. **Smart Connect**: hysteresis, cooldown, ranking only when gain is real.
5. **Features on this branch**: dual-path foreign apps, background update download.
6. Separate logical commits; prefer tests for pure policy functions.
7. Never claim device-verified without a real device log.

## Ship scope on this branch

| Area | Status |
|------|--------|
| Dual-path UI (user picks apps, no Telegram default) | **Done** |
| Dual-path `SingboxConfigBuilder` multi-outbound | **Done** |
| Dual-path `VpnProxyService` wire + foreign pick | **Done** (commit d31b39a7) |
| Background APK prefetch + install notification | **Done** |
| Network change reconnect (Wi‑Fi ↔ LTE) | **Done** |
| Smart Connect anti-flap thresholds | **Done** |
| Boot / Direct Boot auto-start | **Already OK** (device-protected) |
| Security PR #106 dependency bumps | Track separately |
| Version bump + release APK | Pending after green CI + device smoke |

## Explicit non-goals this cycle

- Protocol changes / Reality / XHTTP rewrite
- Aggressive R8 that strips Go bridge
- Auto-install APK without user confirmation

## Verification checklist

- [ ] Cold connect on device
- [ ] Wi‑Fi → LTE keeps VPN
- [ ] Boot with auto-start restores last server
- [ ] Economy mode stretches health checks in logs
- [ ] Dual-path: select app → log `DUAL-PATH enabled foreign=…`
- [ ] Update worker downloads then notifies install
