# Stage B Report

**Status:** FIX IN PROGRESS (FFI real path) — await green CI then owner logcat

**Branch:** `feat/ace-foundation`

## Fixes vs REJECT review

| Review item | Status |
|-------------|--------|
| MSRV / UniFFI host | `rust-toolchain.toml` channel 1.82.0; uniffi 0.28.3 in ace-core |
| Bindgen mandatory | CI fails if no Kotlin bindings / no arm64 .so |
| AceNativeBridge stub | **Deleted** |
| AceProcessService | `uniffi.ace_core.*` only; requires `(rust, target=` marker |
| Version marker | `ace-core 0.1.0 (rust, target=...)` |
| debug applicationIdSuffix | `.debug` → id `com.vpnproxy.direct.debug` |
| Debug UI gate | `if (BuildConfig.DEBUG)` around ACE card |
| release.yml | `on.push.branches: [main]` + job `if: github.ref == 'refs/heads/main'` |

## release.yml (FACT excerpt)

```yaml
on:
  push:
    branches: [main]
  workflow_dispatch:
...
jobs:
  build-and-release:
    if: github.ref == 'refs/heads/main'
```

feat/* does not publish production.

## Owner test (after green Ace Build)

1. Download `vladoss-ace-stage-b-debug-apk`
2. `adb install -r app-direct-*-debug.apk` (package `com.vpnproxy.direct.debug`)
3. Debug screen → **Start ACE (Stage B test)**
4. Logcat must contain **`(rust, target=`** in ACE version / ping lines

```bash
adb logcat -d | grep -E "AceProcessService|AceIpcServer|AceControlPlane" > logcat-ace.txt
```

Expected:

```
ACE version: ace-core 0.1.0 (rust, target=aarch64)
ACE ping: pong from ace-core 0.1.0 (rust, target=aarch64)
AceIpcServer: event from :ace: hello from :ace
```

Without `(rust, target=` → REJECT (not real FFI).
