# 7E Addendum (provisional synthesis)

**Status:** provisional summary of constraints used for Stage B.
Full original 7E text was not present in the repository at synthesis time.

## Evidence levels

- `probable` — local proxy / path observations without independent exit proof.
- `end_to_end` — requires server-visible identifier or equivalent; **not**
  achievable by client exit-IP alone (ADR-007).

## Matching (primary links)

- Primary links unique in both directions.
- Atomic replacement of link sets.
- Guard against stale results.
- Single consistent snapshot for metric calculation.
- `dataset_version` or single writer serialization.

## Telemetry rules (Stage C+)

- `missing ≠ 0`.
- Preserve `Measurement::Missing` / `Option::None`.
- Distinguish observation failure from inability to observe.
- Store provenance and independence_group.
- Doze / restricted_context data excluded from Prediction input.

## Stage B note

Stage B does not implement matching, Digital Twin, or Telemetry.
These rules apply from Stage C onward.
