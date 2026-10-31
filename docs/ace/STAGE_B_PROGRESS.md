# Stage B Progress Report

**Status:** Stage B acceptance pending; Stage C Rust telemetry foundation partially implemented  
**Branch:** `feat/ace-foundation`  
**Base:** `1dde84b`  
**Current HEAD:** `6292c08b4811c3d2c81d7c7f1de1b827385dae0e`  
**Date:** 2026-10-30

## 1. Implemented

- Provisional `docs/ace/` architecture synthesis (P3=C); not owner-approved.
- `ace-core` Rust crate with UniFFI UDL, version marker, ping, initialize/shutdown, test event, and panic containment.
- Kotlin service in process `:ace`.
- One-way Binder/AIDL event path from `:ace` to the main process.
- Main-process `AceIpcServer` and `AceControlPlane` facade.
- Debug-only controls in the debug screen.
- CI workflow to build Rust Android libraries, generate Kotlin bindings, assemble a debug APK, and upload artifacts.
- Debug application ID suffix `.debug`, so the Stage B test build does not replace the production Direct install.

## 2. Corrective work completed on this branch

1. UniFFI 0.28 callback interfaces use `Box<dyn Trait>`; the Rust listener storage and initialization signature now follow that ownership model.
2. The UDL error declaration is a flat enum. Rust error variants now match it; detailed panic and callback messages are retained in logs rather than incorrectly placed in flat error variants.
3. Tests touching the process-global Rust state are serialized to avoid test-order races.
4. `AceProcessService` now runs the blocking Binder wait and Rust initialization on `Dispatchers.IO`. Previously, `connectBlocking()` was called from `onStartCommand()` on the service main thread, while `ServiceConnection` callbacks are dispatched to that same main thread; that could make the Binder wait time out without receiving its callback.
5. Service initialization is guarded against duplicate start intents and cleans up a failed Binder/native initialization.

## 3. Scope boundaries

- No Telemetry Pipeline.
- No Digital Twin.
- No Prediction Engine.
- No real NetworkExecutor.
- No VPN control commands from ACE.
- No active Self-Healing.
- Smart Connect and the existing VPN execution path are not modified by this ACE branch.

## 4. Verification status

| Check | Current evidence |
|---|---|
| Earlier host Rust tests | 8/8 passed on an earlier implementation commit; **not rerun after the corrective changes above** |
| Latest host Rust tests | Pending CI confirmation |
| Android ARM64 `.so` build | Workflow configured; latest result not independently confirmed |
| UniFFI Kotlin binding generation | Workflow configured; latest result not independently confirmed |
| Direct Debug APK | Workflow configured; latest artifact not independently confirmed |
| Device smoke / Binder event delivery | Not run |
| Separate process PID verification | Not run |
| Kill `:ace` while main process and VPN remain alive | Not run |
| ARM64 FFI benchmark | Not run |

## 5. Acceptance blockers

Stage B is **not ACCEPTED** until all applicable checks are evidenced:

1. Latest Ace Build completes successfully, including Rust tests, Android native builds, UniFFI generation, and Direct Debug APK assembly.
2. Debug APK is installed alongside the production app (package `com.vpnproxy.direct.debug`).
3. Logcat proves the version and ping came from Rust (`(rust, target=aarch64)` on ARM64).
4. Binder event from `:ace` is received by the main process.
5. Device process IDs confirm `:ace` and main are separate.
6. Killing `:ace` does not kill the main process or interrupt an already active VPN session.
7. Owner/architect reviews the provisional documentation synthesis.

## 6. Next safe step

Verify the latest Ace Build artifact, install the debug APK on a physical Android device, and perform the Stage B smoke/isolation checks. **Do not begin Stage C until Stage B acceptance is supported by evidence.**

## 7. Evidence discipline

- **FACT:** branch contains the Rust/Kotlin/IPC foundation and the corrective commits listed above.
- **FACT:** the earlier host test run reported 8 passed, 0 failed; it predates the latest corrections.
- **OPEN:** latest CI result and generated APK artifact.
- **OPEN:** physical-device process isolation and VPN continuity.
- **LIMITATION:** `catch_unwind` does not protect against abort, SIGSEGV, or OOM (ADR-006).
- **OPEN:** ARM64 benchmark.
