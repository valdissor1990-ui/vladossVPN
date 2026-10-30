# Stage C — Telemetry Foundation

**Status:** PARTIAL IMPLEMENTATION — Rust admission/buffer added; Android sources and transport are not yet connected  
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

## Telemetry record contract (schema_version=1)

Required fields:
- `event_id`: non-empty unique event identifier supplied by the collector.
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

## Not yet implemented

1. Android collector for ConnectivityManager and PowerManager observations.
2. Explicit Doze / restricted_context tagging at collection time.
3. Capture of actual HealthMonitor RTT/jitter and TrafficValidator outcomes.
4. Main-process → `:ace` telemetry transport and bounded backpressure policy.
5. Lifecycle and process-isolation validation in the installed production application.
6. Tests proving no measurable effect on VPN startup, reconnect, traffic validation, or Smart Connect.

## Safety boundaries

- The collector must not run active probes of its own.
- Do not infer packet loss, jitter, or RTT from connectivity callbacks.
- No SSID, BSSID, IP address, hostname, subscription URL, server credentials, or user traffic content is part of this schema.
- No ACE code sends commands to VpnService, Libbox, NetworkExecutor, or Smart Connect.
- No active Self-Healing.
- Stage C is not accepted until Android sources, transport, tests, and runtime overhead are verified.
