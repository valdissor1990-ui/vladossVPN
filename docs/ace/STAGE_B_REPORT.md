# Stage B Report

**Status:** IN PROGRESS — CI + owner device smoke required for ACCEPT

**Branch:** `feat/ace-foundation`  
**Base:** `1dde84b`  
**Model:** CI builds APK → owner installs → logcat back

---

## CI

- Workflow: `.github/workflows/ace-build.yml`
- Trigger: push to `feat/ace-foundation` or `workflow_dispatch`
- Artifact name: `vladoss-ace-stage-b-debug-apk`
- Also: `stage-b-logs` (cargo-test.log + .so if built)

After first green run, fill:

- Workflow run URL: _pending_
- cargo test: host 8/8 previously; CI re-runs on each push
- .so sizes: _from CI log_

---

## release.yml guard (FACT)

`on.push.branches: [main]` and job `if: github.ref == 'refs/heads/main'`.  
`feat/*` does **not** publish production releases.

---

## Blockers for Stage B closure

| Item | Owner |
|------|--------|
| Device smoke (criteria 4–8) | Owner logcat |
| ARM64 benchmark (criterion 9) | Owner optional |
| docs/ace synthesis approval | Owner / architect review |

---

## Owner instructions

### 1. Get APK

1. GitHub → **Actions** → **Ace Build** → latest successful run on `feat/ace-foundation`.
2. Download artifact **`vladoss-ace-stage-b-debug-apk`**.
3. Install (flavor `direct` + debug):

```bash
adb install -r app-direct-*-debug.apk
```

Package id is `com.vpnproxy.direct` (does not overwrite Play/release id if different suffix).

### 2. Start ACE smoke

1. Open the debug APK.
2. Open **Журнал отладки** (Debug screen) — same path as before.
3. Tap **Start ACE (Stage B test)**.
4. Wait ~5 seconds.
5. Optionally **Trigger ACE panic (Stage B)**.
6. **Export log** (existing button) or collect logcat.

### 3. Collect logcat (preferred if adb available)

```bash
adb logcat -d | grep -E "AceProcessService|AceListener|AceIpcServer|AceControlPlane|AceNativeBridge" > logcat-ace.txt
```

Send `logcat-ace.txt` (or exported log file) back in chat.

### 4. Expected lines (when native + IPC work)

```
AceProcessService: AceProcessService.onCreate pid=<ace_pid>
AceProcessService: ACE version: ...
AceProcessService: ACE ping: pong...
AceIpcServer: event from :ace: hello from :ace
```

Main process pid must differ from `:ace` pid.

### 5. Isolation check

```bash
# find :ace pid from logcat, then:
adb shell kill <ace_pid>
# confirm main app still running; if VPN was up, it should stay up
```

---

## What is NOT in Stage B

- Telemetry Pipeline / Digital Twin / Prediction / Decision / NetworkExecutor
- Self-Healing
- Changes to Smart Connect logic
- Production release

---

## Executor notes

- Host `cargo test`: 8/8 passed (rustc 1.75) before UniFFI host dep was removed.
- `AceNativeBridge` remains a **safe stub** until CI successfully packages `libace_core.so` and (optionally) UniFFI Kotlin is generated; service still starts and emits test events through the Kotlin listener path for IPC smoke.
- Full native ping/panic require successful cargo-ndk step in CI.
