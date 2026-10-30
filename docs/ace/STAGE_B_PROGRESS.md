# Stage B Progress Report

**Branch:** `feat/ace-foundation`  
**Base:** `1dde84b`  
**Latest:** see branch HEAD  
**Date:** 2026-10-30

## 1. What was implemented

- `docs/ace/` provisional synthesis (P3=C) — pending owner/architect review.
- `ace-core` Rust crate: ping, init/shutdown, test event, panic containment (`catch_unwind`).
- Kotlin `:ace` process service, Binder AIDL IPC, main-side `AceIpcServer`, `AceControlPlane`.
- `AceNativeBridge` stub until `libace_core.so` is packaged.
- Manifest: process `:ace`, signature IPC permission.
- `buildFeatures.aidl = true`.

## 2. Files created / changed

**Created:** `docs/ace/*`, `ace-core/*`, `app/src/main/java/com/vpnproxy/ace/*`, `app/src/main/aidl/...`  
**Changed:** `AndroidManifest.xml`, `app/build.gradle.kts`

## 3. Decisions

- P4=A → separate process `:ace`.
- Host crate without UniFFI 0.28 (requires newer rustc); UniFFI deferred to NDK device build.
- No Telemetry / Digital Twin / NetworkExecutor.
- Smart Connect not modified.

## 4. Tests run

| Test | Environment | Result |
|------|-------------|--------|
| `cargo test` in `ace-core` | host, rustc 1.75.0 | **8 passed, 0 failed** |
| Android instrumented | device | **not run** |
| aarch64 `.so` + UniFFI | NDK | **not built** |
| Kill `:ace`, main lives | device | **not run** |

### Host cargo test detail (FACT)

```
running 8 tests
test panic_guard::tests::guard_passes_err ... ok
test panic_guard::tests::guard_passes_ok ... ok
test panic_guard::tests::guard_catches_panic ... ok
test tests::init_emit_shutdown ... ok
test tests::emit_without_init_fails ... ok
test tests::ping_works ... ok
test tests::trigger_panic_is_caught ... ok
test tests::version_not_empty ... ok
test result: ok. 8 passed; 0 failed
```

## 5. Errors and limitations

- **LIMITATION:** `AceNativeBridge` is stub until cargo-ndk produces `libace_core.so`.
- **LIMITATION:** `catch_unwind` does not protect against abort/SIGSEGV/OOM (ADR-006).
- **LIMITATION:** UniFFI not linked on host (toolchain); planned for device NDK.
- **OPEN:** ARM64 benchmark not run.
- **OPEN:** `docs/ace` synthesis needs owner/architect approval.

## 6. Incomplete (before Stage B ACCEPT)

1. NDK build of `libace_core.so` for arm64-v8a + package into `jniLibs`.
2. UniFFI (or chosen FFI) Kotlin bindings wired into `AceNativeBridge`.
3. Device smoke: start `:ace`, logcat event in main, different PIDs, kill `:ace` while checking main/VPN.
4. Optional: ARM64 benchmark (P7).

## 7. Required from owner / architect

1. Review/approve `docs/ace/` synthesis.
2. Device (or CI runner) for process isolation + optional benchmark.
3. PR review path before merge to `main`.

## 8. Next safe step

- On machine with Android NDK: build `.so`, wire bindings, run B1–B7 on device.
- **Do not** start Stage C until Stage B acceptance is green.

## Separation

- **FACT:** host unit tests 8/8; branch commits present; release gated to `main`.
- **LIMITATION:** no device proof of process isolation yet.
- **OPEN:** UniFFI vs alternative after NDK/benchmark.
