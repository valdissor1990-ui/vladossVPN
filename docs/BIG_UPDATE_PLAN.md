# Big Update Plan — Reliability First

Branch: `feat/dual-path-ai-foreign`

## Self-prompt (working charter)

You are hardening vladossVPN for production use on Android (Xiaomi / Android 13+).
Priorities in order:

1. **Do not break working VPN path** (libbox 1.14.3, TUN, protect, subscription parse).
2. **Reliability**: boot reconnect, network change, less flapping.
3. **Battery**: longer intervals under saver/economy; no useless probes.
4. **Smart Connect**: hysteresis, cooldown, ranking only when gain is real.
5. **Features already on this branch**: dual-path foreign apps, background update download.
6. Separate logical commits; prefer tests for pure policy functions.
7. Never claim device-verified without a real device log.

## Ship scope on this branch

| Area | Status |
|------|--------|
| Dual-path (user-selected apps → foreign outbound) | UI + builder; service wire |
| Background APK prefetch + install notification | Done |
| Network change reconnect (Wi‑Fi ↔ LTE) | Hardened |
| Smart Connect anti-flap thresholds | Tightened |
| Boot / Direct Boot auto-start | Already device-protected prefs |
| Security PR #106 dependency bumps | Track separately; do not silent-bump without CI |

## Explicit non-goals this cycle

- Protocol changes / Reality / XHTTP rewrite
- Aggressive R8 that strips Go bridge
- Auto-install APK without user confirmation

## Verification checklist

- [ ] Cold connect < previous baseline on same network
- [ ] Wi‑Fi → LTE keeps VPN without manual toggle
- [ ] Boot with auto-start restores last server
- [ ] Economy mode reduces health-check frequency in logs
- [ ] Dual-path log line only when user selected packages
- [ ] Update worker downloads then notifies install
