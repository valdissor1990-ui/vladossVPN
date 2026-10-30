# Stage C — Telemetry Foundation

**Status:** PARTIAL IMPLEMENTATION — Rust admission/buffer, passive Android collector, bounded reverse Binder transport, passive TrafficValidator integration, and passive VPN service lifecycle integration are implemented; runtime validation and additional source integration remain open  
**Branch:** `feat/ace-foundation`  
**Scope:** observation and evidence ingestion only

## Implemented in Rust

- Versioned JSON telemetry input API exposed through UniFFI:
  - `ace_ingest_telemetry(record_json)`
  - `ace_telemetry_snapshot()`
  - `ace_telemetry_count()`
- Strict schema validation; unknown JSON fields are rejected.
- Finite numeric values only.
- Missing measurements remain `value: null` and require an explicit `missing_reason`; missing is never converted to zero.
- `observation_failure` is retained as its own outcome and is not admitted as primary evidence.
- `restricted_context=true` records are retained for diagnostics but excluded from primary evidence.
- Independence groups are assigned by a fixed source map inside Rust. Unknown sources become `unclassified` and `admitted_to_primary=false`.
- Bounded in-memory buffer (4,096 records); oldest records are evicted when full.
- No SQLite, no Digital Twin persistence, no prediction, and no NetworkExecutor.

## Implemented on Android

- `AceAndroidTelemetryCollector` is started only after successful ACE Rust initialization in `:ace`.
- Passive `ConnectivityManager.registerDefaultNetworkCallback` observations:
  - network transport category;
  - validated capability;
  - metered state.
- Passive `PowerManager` observations:
  - battery saver state;
  - device idle / Doze state.
- Callback work is dispatched on a dedicated HandlerThread; ingestion uses a bounded in-memory Kotlin Channel (256 records) and an IO worker.
- Collector shutdown unregisters callbacks/receiver, closes the queue, and safely stops its HandlerThread.
- No active probes, sockets, VPN commands, Smart Connect calls, or collection of SSID/IP/server identifiers.
- Android observations are ingested directly into Rust in the ACE process.
- Reverse Binder transport contract is implemented: main process registers an `IAceTelemetryReceiver` with `:ace`; telemetry delivery is one-way, bounded (128 records on each process boundary), non-blocking for producers, and payload-limited to 16 KiB.
- `AceTelemetryBridge.submitMetric(...)` builds schema-v1 records only when ACE is connected; it does not start ACE and drops observations when ACE is unavailable or the queue is full.
- The existing `ConnectionCoordinator` submits the duration and available HTTP status codes from its already-running `TrafficValidator` report. This is passive observation after the existing validation completes: it does not launch probes, alter the validation result, or feed Smart Connect.
- TrafficValidator duration is recorded for both successful and failed reports. HTTP status is submitted only when an actual response status exists; DNS, timeout, and other failures do not receive fabricated status values.
- `VpnProxyService.transitionServiceState(...)` submits the actual service lifecycle state as a numeric code (`Idle=0`, `Starting=1`, `Active=2`, `Verifying=3`, `Stopping=4`, `Failed=5`) with source `vpn_service`. Failure text and server/user identifiers are not exported. The observation is emitted only on an actual state transition and does not affect service behavior.
- HealthMonitor has not been connected to ACE. Its current behavior and any Smart Connect dependencies remain untouched.

## Telemetry record contract (schema_version=1)

Required fields:
- `event_id`: non-empty event identifier supplied by the collector.
- `elapsed_realtime_ms`: monotonic Android elapsed time; wall-clock time is not used for ordering.
- `metric`: allow-listed metric name.
- `value`: finite number or null.
- `unit`: optional unit.
- `outcome`: observed / success / failure / unavailable / observation_failure / unknown_outcome.
- `missing_reason`: mandatory when value is null.
- `restricted_context`: whether Doze/restricted context applies.
- `source`: source identifier; Rust assigns independence_group.

Allowed metrics currently include RTT, jitter, packet loss, traffic validation status/duration, network transport/validation/metered state, battery saver, Doze, and VPN lifecycle state. A metric is not present merely because it is allow-listed: only a real source measurement may submit it.

## Independence map

| Source | independence_group |
|---|---|
| android_network_callback | android_connectivity |
| health_monitor | health_monitor |
| traffic_validator | traffic_validator |
| power_manager | power_manager |
| vpn_service | vpn_lifecycle |
| unknown | unclassified (not primary-admissible) |

This map is a versioned code artifact and must be reviewed when a new source is introduced. Separate API calls backed by the same physical subsystem must share one group.

## Not yet implemented / not yet verified

1. Decide whether HealthMonitor can be instrumented as a strictly passive source without changing its measurement or Smart Connect decision behavior; do not connect it until that review is complete.
2. Verification that all collector, TrafficValidator, and VPN lifecycle metrics and units match the schema contract through runtime ingestion tests.
3. Lifecycle and process-isolation validation in the installed application, including ACE process termination while VPN remains active.
4. Tests proving no measurable effect on VPN startup, reconnect, traffic validation, or Smart Connect.
5. Device-level verification of actual process IDs and telemetry behavior under network changes, Doze, and battery saver.

## Safety boundaries

- The collector must not run active probes of its own.
- Do not infer packet loss, jitter, or RTT from connectivity callbacks.
- No SSID, BSSID, IP address, hostname, subscription URL, server credentials, or user traffic content is part of this schema.
- No ACE code sends commands to VpnService, Libbox, NetworkExecutor, or Smart Connect.
- No active Self-Healing.
- ACE service is not auto-started by this collector change; its existing explicit start path and foreground notification behavior remain unchanged.
- Stage C is not accepted until transport, tests, and runtime overhead are verified.
