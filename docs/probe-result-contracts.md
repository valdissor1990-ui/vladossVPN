# Probe result contracts (PR1)

This document records the domain contract for diagnostic observations and derived decisions.

The applicability identity is `(serverId, configFingerprint, networkFingerprint, method, stage)`. Subscription version and engine instance ID are diagnostic metadata and do not invalidate observations.

`configFingerprint` must be a SHA-256 digest of canonical outbound fields that affect connection behavior. Display labels, provider attribution, order, and color are excluded. Secrets must never be logged. The existing `ParsedConfig.connectionKey()` is a raw delimiter-joined representation containing credentials; do not log or persist it as a fingerprint. Verify canonical fingerprint fields against `SingboxConfigBuilder.buildOutbound()` before production use.

`ProbeResult` records facts. Derivation is per server/method/stage/context group; cross-method aggregation and UI mapping are separate layers. TCP outcomes remain `UNVERIFIED/tcp_only`. Unknown outcomes map to `UNVERIFIED/unknown_outcome`.

Attempt history is separate from probe history. Cancellation is not a failed attempt. Backoff is derived from attempt history and has no separately persisted state. Retention must preserve every event inside the configured backoff window plus a safety margin.

A persistent store must enforce three bounds: records per server/method/stage/context; distinct contexts per server/method/stage; and a global serialized-byte budget. Eviction removes old context groups first while preserving the newly written current context if it fits the budget. If the current context cannot fit, the store must fail explicitly or apply a documented truncation policy.

Known integration gap: the current scanner uses TCP connect for all protocols and current UI/selection consumers treat `successRate` as VPN success. This document does not claim that behavior is fixed until scanner, AutoSwitcher, UI, and persistence are migrated with tests.
