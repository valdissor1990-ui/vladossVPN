# Stage B Report

**Status:** implementation corrections pushed; CI/device acceptance pending  
**Branch:** `feat/ace-foundation`  
**Current HEAD:** `6292c08b4811c3d2c81d7c7f1de1b827385dae0e`

## 1. Implemented foundation

- Rust `ace-core` crate with UniFFI UDL.
- Android service in process `:ace`.
- Signature-protected Binder/AIDL event path to the main process.
- Debug-only Stage B controls.
- Android CI workflow for Rust tests, Android native libraries, generated Kotlin bindings, and Direct Debug APK.

## 2. Corrective review findings and fixes

| Finding | Fix / status |
|---|---|
| Callback interface ownership | Rust now uses `Box<dyn AceListener>`, matching UniFFI 0.28 callback-interface mapping. |
| Flat UDL error enum mismatch | Rust `AceError` now uses unit variants; detailed panic/callback diagnostics go to logs. |
| Shared global Rust state in tests | State-mutating tests are serialized. |
| Blocking Binder wait on service main thread | Initialization and `connectBlocking()` now run on `Dispatchers.IO`; the service main thread remains free to deliver `ServiceConnection` callbacks. |
| Duplicate service start intents | Initialization job and initialized state guard duplicate starts. |
| Rust toolchain | Workflow and `rust-toolchain.toml` use stable Rust; crate declares MSRV 1.82. |

## 3. Required invariants

- `:ace` does not issue VPN control commands.
- No call from Shadow Mode to the real NetworkExecutor.
- Smart Connect remains unchanged.
- No active Self-Healing.
- No production release from this feature branch.
- `catch_unwind` only contains unwind panics; it does not contain abort, SIGSEGV, or OOM.

## 4. Current verification

- Earlier host Rust test run: 8 passed, 0 failed, **before the latest corrective changes**.
- Latest Rust test result: pending.
- Latest Android native build / UniFFI binding generation / APK artifact: pending independent confirmation.
- Physical device smoke and process-isolation tests: not run.
- ARM64 benchmark: not run.

A green build alone does not prove process isolation or that the VPN remains active if the ACE process is killed.

## 5. Owner device test after latest Ace Build succeeds

1. Open [GitHub Actions — Ace Build](https://github.com/valdissor1990-ui/vladossVPN/actions/workflows/ace-build.yml).
2. Open the latest successful run for branch `feat/ace-foundation`.
3. Download artifact `vladoss-ace-stage-b-debug-apk`.
4. Install the Direct Debug APK. Its package is `com.vpnproxy.direct.debug`; it is separate from production package `com.vpnproxy.direct`.
5. Open the debug screen and press **Start ACE (Stage B test)**.
6. Check logcat for:
   - `ACE version: ace-core 0.1.0 (rust, target=aarch64)`
   - `ACE ping: pong from ace-core 0.1.0 (rust, target=aarch64)`
   - `AceIpcServer: event from :ace: hello from :ace`
7. Confirm main and `:ace` have different PIDs.
8. With VPN already connected, stop/kill only `:ace`; confirm the main process and VPN session remain alive.

Example log capture:

```bash
adb logcat -d | grep -E "AceProcessService|AceIpcServer|AceControlPlane" > logcat-ace.txt
```

Without the Rust target marker, real FFI has not been proven. Without the device process-kill test, ADR-006 process isolation has not been accepted.

## 6. Decision

**Stage B remains IN PROGRESS.** Do not start Stage C until latest CI and required device checks are green and reviewed. This report does not approve the provisional architecture synthesis and does not authorize active Self-Healing or production release.
