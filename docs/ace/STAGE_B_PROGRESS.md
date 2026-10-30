# Stage B Progress Report (partial)

**Branch:** `feat/ace-foundation`
**Base:** `1dde84b`
**Date:** 2026-10-30

## 1. What was implemented

- `docs/ace/` provisional synthesis (P3=C) — pending owner/architect review.
- `ace-core` Rust crate skeleton with panic = unwind, catch_unwind guard, ping/init/shutdown/test event/test panic.
- Kotlin `:ace` process service, Binder AIDL IPC, main-side AceIpcServer, AceControlPlane facade.
- `AceNativeBridge` stub so process/IPC path can run before `libace_core.so` is packaged.
- AndroidManifest: `:ace` FGS + AceIpcServer + signature permission.
- `buildFeatures.aidl = true`, JNA dependency reserved for UniFFI.

## 2. Files created / changed

**Created:** docs/ace/*, ace-core/*, app/.../ace/*, app/.../aidl/...
**Changed:** AndroidManifest.xml, app/build.gradle.kts

## 3. Decisions

- P4=A → separate process `:ace`.
- Provisional UniFFI; native calls behind stub until NDK build on device/CI.
- No Telemetry / Digital Twin / NetworkExecutor code.
- Smart Connect not modified.

## 4. Tests run

- Host `cargo test` for ace-core: **pending in this report** (to be attached).
- Android instrumented / device smoke: **not run** (no device in executor environment).
- Full UniFFI .so for aarch64: **not built** (no Android NDK in this environment).

## 5. Results (FACT)

- Branch exists; commits pushed.
- release.yml already gated on `main` only → production from feat/* does not publish.

## 6. Errors and limitations

- LIMITATION: `AceNativeBridge` is stub until cargo-ndk produces `libace_core.so` and UniFFI Kotlin bindings are generated.
- LIMITATION: catch_unwind does not protect against abort/SIGSEGV/OOM (ADR-006).
- OPEN: ARM64 benchmark not run (P7=B → first task when device available).
- OPEN: docs/ace synthesis needs owner/architect approval.

## 7. Incomplete

- Wire real UniFFI generated Kotlin API into AceNativeBridge.
- cargo-ndk arm64 build + jniLibs packaging.
- Device smoke: start :ace, verify PID isolation, Binder event in main logcat, kill :ace while VPN up.
- Benchmark protocol execution.

## 8. Required from owner / architect

1. Review/approve `docs/ace/` synthesis (or provide canonical texts).
2. Device for Stage B smoke + optional ARM64 benchmark.
3. Confirm PR path for this branch (review before merge to main).

## 9. Next safe step

- Local/CI: `cargo test` in ace-core (host).
- On machine with NDK: `cargo ndk -t arm64-v8a build --release` → package .so.
- Generate UniFFI Kotlin bindings; replace stub methods.
- Device smoke tests B1–B7 from VALIDATION_AND_ACCEPTANCE.md.
- **Not** Stage C until Stage B acceptance is green.
