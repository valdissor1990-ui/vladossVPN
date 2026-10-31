# Validation and Acceptance (provisional)

## Stage B acceptance (smoke test)

| # | Criterion | Evidence |
|---|-----------|----------|
| B1 | `ace-core` builds for `aarch64-linux-android` with `panic = "unwind"` | cargo / NDK build log |
| B2 | `ace_ping()` returns `"pong"` via UniFFI | unit or instrumented log |
| B3 | `ace_initialize` + listener works | log |
| B4 | `ace_emit_test_event` reaches main via Binder | logcat main process |
| B5 | `ace_trigger_test_panic` returns Err; process does not abort | Result + process alive |
| B6 | Main PID ≠ `:ace` PID | `ps` / logcat |
| B7 | Kill `:ace` → main still alive | adb |
| B8 | No Telemetry / Digital Twin / NetworkExecutor code paths | code review |
| B9 | Smart Connect untouched | diff review |
| B10 | release.yml does not publish from feat/* | workflow condition |

**Reject conditions:** any Telemetry/DigitalTwin skeleton; control of VPN;
Self-Healing enabled; production release from feat branch.
