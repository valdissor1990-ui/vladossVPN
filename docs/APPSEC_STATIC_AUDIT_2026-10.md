# Static AppSec audit — 2026-10-28

## Scope and limitations

- Target: Android direct-APK distribution, repository source, Gradle build, GitHub Actions, scripts, and bundled native dependencies.
- Method: read-only static review of repository files and public upstream advisories.
- Initial audit was static-only. Follow-up CI runs CodeQL and a dedicated Android compile/unit-test workflow. The latest completed security validation on commit 5eb3ffde passed wrapper validation, Android compile tasks, and unit tests. CodeQL runs on later commits failed during Android SDK preparation before source extraction (first due to requesting the removed SDK package `tools`; then due to `yes` receiving a broken pipe under `pipefail`). Both workflow defects have been corrected in the branch; validation of the latest commit remains in progress.
- Not performed: a successful build/test run, completed CodeQL/SCA results, APK reverse engineering, dynamic testing, network/VPN leak tests, server/infrastructure review, or validation against a physical device.
- No code or behavior finding below should be interpreted as proof of exploitation unless explicitly stated.
- The repository's current source was reviewed on 2026-10-28. Dependency versions below are declarations, not a resolved transitive dependency inventory.

## Executive summary

The highest-confidence issues are in the build supply chain and dependency lifecycle. The Gradle wrapper was upgraded to 8.14.6 and now pins the official distribution SHA-256. Release CI verifies the official Wrapper JAR SHA-256 and runs Gradle's wrapper-validation action. OkHttp was updated to 4.12.1 and AndroidX Security Crypto to stable 1.1.0. The updater now compares the downloaded APK's signing identity against the installed app before launching the platform installer. These changes are committed to the audit branch. A dedicated CI validation workflow has been added. Initial runs exposed bootstrap errors (wrapper validation ordering and an invalid standalone JAR URL); both were corrected. The latest completed security validation on commit 5eb3ffde passed wrapper validation, compilation of Direct/Play debug Kotlin variants, and unit tests. CodeQL Android SDK preparation fixes and a new dependency-report workflow have since been committed; their results are pending. The dependency-report workflow exports resolved runtime dependency graphs but does not itself constitute a vulnerability scan or SBOM.

The updater checks HTTP success, size, APK ZIP structure, package name, monotonically increasing versionCode, and now checks APK signer certificate fingerprints against the installed app's signing identity/declared single-signer lineage before launching the platform installer. Android's package manager remains the final enforcement layer. The new signing comparison has not yet been exercised in a build/device test.

This is a preliminary static audit, not an OWASP MASVS certification or a complete SCA result.

## Findings

### APPSEC-001 — Gradle Wrapper version is affected by published security advisories

- **Severity:** High (build-environment risk; exploitability depends on the build host and attack preconditions).
- **Evidence:** `gradle/wrapper/gradle-wrapper.properties` previously declared Gradle 8.11.1 and now declares 8.14.6 with the official binary distribution SHA-256 pinned. Gradle advisories GHSA-xwqc-3h47-hg64 and GHSA-mvvg-497x-hmj8, published 2026-10-07, list versions below 8.14.6 as affected and 8.14.6 as patched. The advisories describe local loopback client/daemon or worker/daemon deserialization paths; they explicitly do not describe remote-network exploitation.
- **Impact:** A local attacker able to win the documented IPC race during an active build (or connect to an active daemon for the client/daemon issue) may execute code with build-user privileges or cause denial of service.
- **Recommendation:** **Changed in branch:** upgraded wrapper to Gradle 8.14.6 and pinned official distribution SHA-256 in `gradle-wrapper.properties`. CI build compatibility with AGP 8.7.3, Kotlin 2.1.0, native AAR download, and release packaging still requires a successful workflow run.
- **SDLC:** CI / build infrastructure.
- **Status:** Remediated in branch; build/CI compatibility validation pending.

### APPSEC-002 — Gradle Wrapper JAR bootstrap lacks checksum verification

- **Severity:** High (supply-chain integrity risk).
- **Evidence:** `.github/workflows/release.yml` previously downloaded `gradle-wrapper.jar` from a GitHub URL when the file was missing without verifying a pinned digest. The branch now downloads from `services.gradle.org`, checks the official SHA-256 whether the JAR was pre-existing or freshly downloaded, and invokes the Gradle wrapper-validation action.
- **Impact:** If the downloaded artifact or source is compromised, malicious wrapper code could execute in the release workflow with access to build-time credentials and signing material.
- **Recommendation:** **Changed in branch:** the bootstrap downloads the official Gradle 8.14.6 Wrapper JAR from `services.gradle.org`, verifies its pinned official SHA-256 on every run (including when a JAR is already present), and the release workflow invokes `gradle/actions/wrapper-validation@v4`. `distributionSha256Sum` is set. Consider pinning third-party GitHub Actions to full commit SHAs as a separate supply-chain hardening task.
- **SDLC:** CI / release engineering.
- **Status:** Mitigation added in branch; first release workflow run not yet verified; no compromise observed.

### APPSEC-003 — Updater does not independently verify the expected APK signing identity

- **Severity:** Medium (defense-in-depth and update-channel trust).
- **Evidence:** `UpdateDownloader.kt` checks HTTP response, declared/actual size and ZIP manifest entry. `UpdateInstaller.kt` checks APK parsing, package name, and versionCode, and now compares SHA-256 signer fingerprints with the installed app before launching `ACTION_INSTALL_PACKAGE`.
- **Impact:** These checks do not establish publisher identity. Android's package installer still checks signing compatibility for an update over an installed app, but that check occurs after the app has handed the APK to the system installer.
- **Recommendation:** **Changed in branch:** the installer now compares SHA-256 signer fingerprints for the installed package and downloaded APK before opening the installer, including a declared single-signer signing lineage. This is an independent consistency check against the installed publisher, not a separately provisioned pinned publisher key. Do not trust a checksum delivered only alongside the APK from the same release channel. Signing-key rotation still requires explicit testing.
- **SDLC:** CI / runtime.
- **Status:** Mitigation added in branch; compile/runtime tests pending; no exploit or signature bypass demonstrated.

### APPSEC-004 — Security Crypto dependency is an old alpha release

- **Severity:** Medium (maintenance / lifecycle; no specific CVE asserted).
- **Evidence:** `app/build.gradle.kts` previously declared `androidx.security:security-crypto:1.1.0-alpha06` (released 2023-04-19). Android's release notes show stable `1.1.0` released 2025-07-30 and note that the APIs are deprecated in favor of platform APIs and direct Android Keystore use.
- **Impact:** Continued use of a pre-release dependency increases maintenance risk and complicates future platform compatibility.
- **Recommendation:** **Changed in branch:** dependency upgraded to stable `1.1.0` without changing storage implementation, to preserve existing encrypted preferences/HWID. Migration away from the deprecated API remains a separate change requiring a tested data-preserving migration.
- **SDLC:** Development / staging.
- **Status:** Alpha dependency replaced by stable release in branch; migration away from deprecated APIs remains open; no specific security vulnerability asserted.

### APPSEC-005 — OkHttp version is behind current upstream release

- **Severity:** Medium (dependency freshness; security impact requires advisory matching).
- **Evidence:** `app/build.gradle.kts` previously declared OkHttp 4.12.0 for both `okhttp` and `logging-interceptor`. Upstream's 4.x changelog lists 4.12.1 dated 2026-10-22, which blocks specified compromised eMudhra intermediate certificates on JVM/AOSP environments without automatic Google Play certificate updates. Both dependencies are now declared as 4.12.1 in this branch.
- **Impact:** Devices whose platform trust store has not received the corresponding certificate update may not receive this additional mitigation from OkHttp 4.12.1.
- **Recommendation:** **Changed in branch:** both artifacts upgraded together to 4.12.1. Re-run connection, subscription, and update tests in CI/device testing.
- **SDLC:** Dependency management / CI.
- **Status:** Dependency updated in branch; regression tests and device trust-store exposure review pending.

### APPSEC-006 — Automated security-analysis coverage was not configured in reviewed files

- **Severity:** Medium (process gap).
- **Evidence:** The reviewed repository did not contain a Dependabot configuration or CodeQL workflow before this PR. This branch adds both. Existing release CI generates license reports, but license inventory is not equivalent to vulnerability scanning or SAST.
- **Impact:** Vulnerable dependency updates and code-level security regressions may not be detected automatically.
- **Recommendation:** Dependabot for Gradle and GitHub Actions, scheduled/push/PR CodeQL for Java/Kotlin, and a dedicated Android compile/unit-test workflow are configured in this branch. CodeQL and existing build workflows started for the current PR head. Initial dedicated validation attempts failed due to wrapper-validation ordering and a nonexistent standalone Wrapper JAR URL; both issues have been corrected by extracting the JAR from the checksum-verified Gradle distribution. The rerun must be checked. Repository settings must permit SARIF upload. Add a resolved dependency SBOM and OSV/SCA scan once the build/lockfile strategy is established.
- **SDLC:** Pre-merge / CI.
- **Status:** Configuration present; baseline compile/unit-test workflow passed on commit 5eb3ffde. CodeQL SDK setup fixes and Gradle dependency-report workflow committed; latest runs pending. SBOM and vulnerability scanning remain open.

### APPSEC-007 — Compliance audit document contradicts the current manifest on cleartext traffic

- **Severity:** Low (documentation accuracy).
- **Evidence:** Current `app/src/main/AndroidManifest.xml` sets `android:usesCleartextTraffic="false"`; `docs/PRODUCT_COMPLIANCE_AUDIT.md` previously stated `true` and is corrected in this branch.
- **Impact:** Security decisions may be made against stale documentation.
- **Recommendation:** **Changed in branch:** documentation now matches the source manifest. Verify merged direct-release manifest and network paths in CI/device testing; the source attribute alone does not prove every network path is secure.
- **SDLC:** Pre-commit / CI.
- **Status:** Documentation corrected in branch; merged-manifest verification pending.

## Dependency inventory — declared direct dependencies

The following versions are explicitly declared in `app/build.gradle.kts` or the root `build.gradle.kts`. This is not a resolved transitive inventory and is not a vulnerability scan.

| Component | Declared version | Static-review note |
|---|---:|---|
| Gradle Wrapper | 8.14.6 | Upgraded to the patched version and distribution checksum pinned; see APPSEC-001. |
| Android Gradle Plugin | 8.7.3 | CI compatibility validation pending with Gradle 8.14.6. |
| Kotlin | 2.1.0 | Review compatibility and upstream maintenance. |
| KSP | 2.1.0-1.0.29 | Version coupled to Kotlin; verify together. |
| Hilt | 2.53.1 | Requires SCA against resolved graph. |
| OkHttp + logging-interceptor | 4.12.1 | Updated to include the upstream mitigation; regression tests pending. |
| kotlinx.serialization-json | 1.7.3 | Requires SCA against resolved graph. |
| kotlinx.coroutines-android | 1.9.0 | Requires SCA against resolved graph. |
| SnakeYAML | 2.3 | No vulnerability conclusion made without full advisory matching. |
| AndroidX Security Crypto | 1.1.0 | Stable release; APIs remain deprecated and migration is open. |
| Compose BOM | 2025.01.01 | Requires resolved transitive inventory. |
| Navigation Compose | 2.8.5 | Requires SCA against resolved graph. |
| Room | 2.6.1 | Requires SCA against resolved graph. |
| WorkManager | 2.10.0 | Requires SCA against resolved graph. |
| sing-box-lx native AAR | 1.14.3-lx.23 | Download script pins SHA-256; native binary/source audit and CVE mapping remain outstanding. |
| Cronet/NaiveProxy native archives | pinned upstream commits in `scripts/audit_libbox_licenses.sh` | License inventory exists; binary SCA and upstream security review remain outstanding. |

## Positive controls observed

- Release workflow requires signing secrets and fails if any are missing before production signing.
- `scripts/download_libbox.sh` verifies the downloaded AAR against a pinned SHA-256 and checks archive/class contents.
- Manifest sets `android:allowBackup="false"` and `android:usesCleartextTraffic="false"`.
- Update downloader validates the HTTP response, byte count, APK archive structure and uses a temporary file before publishing it to the update cache.
- Update installer now checks SHA-256 signing-certificate fingerprints and rejects APKs whose signer does not match the installed app's signer or declared single-signer lineage.
- Installer validates package name and versionCode before invoking Android's package installer.
- A license-audit script resolves Go dependency/license information for multiple Android architectures.

These are positive controls, not proof that all threat scenarios are covered.

## Static checks still required

1. Run Gradle dependency reports for `directReleaseRuntimeClasspath` and generate an SPDX or CycloneDX SBOM from the resolved graph.
2. Run OSV-Scanner or another SCA tool against resolved Gradle dependencies and native components.
3. Review all logging call sites and export paths for secrets, including native libbox logs and crash reports.
4. Review every exported Android component, FileProvider paths, network configuration, and flavor-specific merged manifest.
5. Review parsers and URI/config import paths for denial-of-service, path traversal, unsafe deserialization, and malformed input.
6. Review all shell scripts for unsafe temporary-file handling, command injection, unchecked downloads, and mutable upstream references.
7. Review third-party GitHub Actions references and pin to full commit SHAs after mapping each tag to its trusted commit.
8. Inspect native AAR contents and map the exact embedded library versions to OSV/NVD/upstream advisories.
9. Verify source-to-release correspondence, signing certificate continuity, and artifact hashes.

## Out of scope / requires access

- Server configuration, VPN endpoint hardening, server-side authentication and rate limiting: server access not provided.
- Dynamic IPv4/IPv6/DNS leak tests, kill-switch behavior, MITM tests and runtime permissions: explicitly outside this static-only scope.
- Runtime exploitability, device-specific behavior, and physical-device installer behavior: not tested.

## References

- Gradle advisory: https://github.com/gradle/gradle/security/advisories/GHSA-xwqc-3h47-hg64
- Gradle advisory: https://github.com/gradle/gradle/security/advisories/GHSA-mvvg-497x-hmj8
- Gradle security subscription / version guidance: https://blog.gradle.org/gradle-security-subscription
- AndroidX Security release notes: https://developer.android.com/jetpack/androidx/releases/security
- OkHttp 4.x changelog: https://lysine.dev/okhttp/changelogs/changelog_4x/
