# VLADOSS ACE — Master Specification (provisional)

**Status:** provisional synthesis (P3=C), pending owner/architect review
**Date:** 2026-10-30

## 1. Purpose

Vladoss ACE (Autonomous Connection Engine) is an observation-first control
plane that may later support self-healing of VPN connectivity. It must never
reduce the reliability of the existing VladossVPN / Smart Connect path.

## 2. Architecture layers

1. **Rust Control Plane** — ACE core (`ace-core` crate).
2. **Kotlin/Android** — UI, lifecycle, VpnService, IPC, system APIs.
3. **Sing-box / Libbox (Go)** — data plane (unchanged ownership).
4. **NetworkExecutor** — command execution contract (Fake until Stage H).
5. **Verification** — evidence-based result verification.
6. **ShadowObserver** — observe only; no real control commands.
7. **Digital Twin** — local history (Stage E).
8. **Prediction Engine** — statistical forecasts (Stage F).
9. **Decision Engine** — decisions + explanations only in Shadow Mode (Stage G).
10. **Self-Healing** — forbidden until explicit owner Go/No-Go (Stage I).

## 3. Process isolation (ADR-006)

ACE Control Plane runs in a **separate Android process** `:ace`.
VpnService and libbox remain in the main process.
Failure of `:ace` must not terminate the main process or the active VPN.

## 4. Stage map

| Stage | Name | Allowed |
|-------|------|---------|
| A | Repository Intelligence | done |
| B | Foundation (smoke) | Rust + FFI + panic containment + IPC + empty events |
| C | Telemetry | real metrics only; missing ≠ 0 |
| D | ShadowObserver | observe + record; no commands |
| E | Digital Twin | local history, matching rules |
| F | Prediction | EWMA / change-point first |
| G | Decision Engine | decisions recorded only |
| H | Fake NetworkExecutor + Verification | fake only |
| I | Self-Healing | **forbidden** until Go/No-Go |
| J | Field validation | after owner permission |

## 5. Hard prohibitions

- No active Self-Healing without owner permission.
- No real VPN control by ACE before Stage H acceptance + owner permission.
- No removal or replacement of Smart Connect.
- No production release from `feat/ace-foundation`.
- No Telemetry / Digital Twin skeletons inside Stage B.
- No invented Libbox metrics; missing stays Missing.

## 6. Shadow Mode

Until explicitly disabled by owner decision, ACE operates in Shadow Mode:
events are observed and recorded; predicted actions are logged; no command
is sent to a real NetworkExecutor.
