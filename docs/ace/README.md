# Vladoss ACE — Documentation

**Status:** provisional synthesis (P3=C)
**Branch:** `feat/ace-foundation`
**Base:** `1dde84b`
**Date:** 2026-10-30

This directory is the in-repo source of truth for ACE architecture decisions.
Content was synthesized by the executor from Stage A analysis and agreed
constraints (7E / ADR rules discussed in review). **Owner/architect review
of this synthesis is required before treating it as final.**

## Files

| File | Purpose |
|------|---------|
| MASTER_SPECIFICATION.md | High-level ACE scope, stages, hard limits |
| ARCHITECTURE_DECISIONS.md | ADR-001…010 summary + Stage B decisions |
| VALIDATION_AND_ACCEPTANCE.md | Acceptance criteria per stage |
| ADDENDUM_7E.md | Matching, evidence, snapshot rules |
| BENCHMARK_ARM64.md | UniFFI ARM64 benchmark protocol |
| STAGE_B_REPORT_TEMPLATE.md | Executor report template for Stage B |
| EXECUTOR_CONTEXT.md | What the executor may / may not do |
| DEEPSEEK_CONTEXT.md | Placeholder for external context notes |

## Hard limits (always)

- Smart Connect is not replaced or disabled.
- Active Self-Healing is forbidden until explicit owner Go/No-Go.
- ACE does not send real control commands to VPN in Shadow Mode.
- Production releases from `feat/*` are forbidden.
- Stage B = smoke test only (no Telemetry / Digital Twin skeletons).
