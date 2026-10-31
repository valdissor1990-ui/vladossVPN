# ACE Architecture Decisions (provisional synthesis)

**Status:** provisional (P3=C). Aligns with Stage A findings and review ADRs.
**Date:** 2026-10-30

## ADR-001 — Probe scenarios and protect()

**Decision:** Direct probes that leave the VPN path must call `VpnService.protect()`
**before** `connect()`. `bindSocket()` is not used in the current codebase.
Four formal scenarios A/B/C/D are not yet named in production code; Stage C
must map existing probes to ADR-001 scenarios explicitly.

**Evidence (Stage A):** `HealthMonitor.probeSocket` protects before connect;
`SingBoxPlatform.autoDetectInterfaceControl` protects libbox outbound fds.

## ADR-002 — FFI choice

**Decision:** Provisional FFI is **UniFFI**. Final choice after ARM64 benchmark
(BENCHMARK_ARM64.md). If UniFFI is unsuitable, Stage B FFI layer is redone.

## ADR-005 — Doze / restricted_context

**Decision:** Probes must not run in Doze / App Standby without marking
`restricted_context`. Current `BatteryPolicy` checks power-save mode only,
not `isDeviceIdleMode()`. Stage C must add explicit Doze checks.

## ADR-006 — Process isolation

**Decision (Stage B default P4=A):** ACE runs in process `:ace`. VpnService
and libbox stay in main. IPC via Binder (AIDL). Abort/OOM/SIGSEGV in `:ace`
must not kill main or active VPN.

**Note:** Variant B (same-process) requires a formal amendment and is not
selected for this branch.

## ADR-007 — Route verification levels

**Decision:** Current TrafficValidator proves **probable** path via local
proxy 10809 only. Exit IP is not collected. `probe_id` is not used.
`verified_tunnel_path` / end-to-end requires server-side support and is
out of scope for Stage B/C client-only work.

## ADR-008 — Evidence provenance

**Decision:** Every measurement carries provenance and independence_group.
`missing ≠ 0`. Observation failure is not automatically a false negative.

## ADR-009 / ADR-010 — Matching

**Decision:** Matching rules from 7E must not be changed without owner
agreement. Weightless matching when ADR-010 requires it. Consistent snapshot
for metrics; `dataset_version` or single-writer serialization.

## Stage B decisions

1. Crate name: `ace-core` (cdylib).
2. Process name: `:ace`.
3. IPC: AIDL `IAceEventReceiver` (signature permission).
4. Events in Stage B: `Test`, `Initialized`, `Shutdown` only.
5. No Telemetry Pipeline, no Digital Twin, no NetworkExecutor in Stage B.
