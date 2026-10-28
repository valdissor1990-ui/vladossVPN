# Product compliance baseline — VLADOSS VPN

This document tracks product-level obligations beyond third-party license notices. It is an engineering audit, not a legal opinion or a claim of certification.

## Scope

Initial baseline covers:
- Android APK distributed directly from GitHub.
- Google Play AAB / Play Store distribution.
- Open-source license and source-correspondence obligations.
- Security, privacy, diagnostics, Android permissions, release integrity, and user-facing legal information.

The applicable laws still depend on the operator, users, data processing, and target markets. Before public release, the project owner must confirm the legal entity/contact, target jurisdictions, and whether direct APK and Google Play are both supported.

## Findings and status

| Area | Evidence / current state | Required action | Status |
|---|---|---|---|
| Third-party licenses | Gradle, Go/libbox, and Cronet/Chromium inventories and notices are generated; release CI verifies that selected materials are packaged. | Regenerate on every dependency/core change; review the custom sing-box-lx terms and exact source correspondence before release. | Automated checks exist; legal review pending |
| Privacy notice | No public privacy-policy URL or in-repository privacy policy was found in the reviewed tree. | Publish a truthful policy covering on-device configuration, VPN traffic handling, diagnostics, exports, crash records, update checks, and any server-side processing. Do not claim “zero collection” unless verified from code and infrastructure. | Open |
| Terms / legal identity | README has a general disclaimer, but no complete user terms or operator contact details were found in the reviewed tree. | Publish terms, support/security contact, jurisdiction-specific disclosures, and clear statement that the app is a client and does not itself provide VPN exit servers unless that changes. | Open |
| Diagnostic data | App logs are persisted locally; crash reports are stored locally; the debug UI can copy/export diagnostic logs. Native logs are read separately from the app log. | Sanitize all export sources, document fields included in exports, keep sharing user-initiated, and test removal/retention behavior. | Partial; masking hardening added, tests required |
| Cleartext traffic | AndroidManifest.xml sets android:usesCleartextTraffic="true" at application scope. | Determine which destinations truly require cleartext; restrict to loopback or explicit exceptions if possible; test subscriptions, local proxy validation, update checks, and VPN behavior before changing policy. | Documentation corrected; verify merged manifests and runtime paths |
| Package visibility | QUERY_ALL_PACKAGES is declared. Per-app split tunneling may need to enumerate installed apps. | Document the core-feature justification and complete any Google Play declaration; verify behavior if broad visibility is unavailable. | Open |
| APK installation / updates | REQUEST_INSTALL_PACKAGES is declared and an in-app APK installer exists. | Keep external APK installation only in the direct-distribution variant if required. Remove the permission and disable the external installer in the Play variant unless current Play policy explicitly permits the exact use case. Verify the merged Play manifest. | Open; potential Play release blocker |
| VPN and foreground-service declarations | The app uses Android VpnService and declares foreground-service types and special-use metadata. | Verify declarations, user disclosures, permission prompts, notification, and Play Console declarations against actual behavior and current platform/store rules. | Open |
| Secrets and transport | The app handles server URIs/configurations and uses masking for several credential patterns. | Add regression tests for UUIDs, subscription URLs, tokens, Reality keys, native logs, crash stacks, and malformed input; never send logs automatically without explicit user action. | Partial |
| Build supply chain | CI downloads a Gradle Wrapper JAR only when missing and now verifies its pinned SHA-256; Gradle distribution checksum is pinned in wrapper properties. The release workflow still uses third-party actions by major-version tags. | Wrapper JAR and distribution checksums are pinned/verified. Consider pinning third-party actions to full commit SHAs; add resolved-graph SCA/SBOM and retain release provenance. | Open |
| Release integrity | CI records source commit and builds APK/AAB. | Verify signed artifacts, SHA-256 checksums, exact tag/source mapping, reproducibility where feasible, and that public source matches the shipped binary. | Partial |
| Functional privacy/security | VPN routing, DNS, IPv6, split tunneling, reconnect, and kill-switch behavior affect user safety. | Run device-level leak tests for IPv4/IPv6/DNS, network changes, service crash, reboot, always-on VPN, and both split-tunnel modes; document limitations honestly. | Open |
| Accessibility and user information | Not yet reviewed in this baseline. | Review permission rationale, understandable connection states/errors, accessibility, localization, and clear controls for diagnostics and stored data. | Open |

## Changes made in this audit

- Diagnostic exports now re-mask app-log lines, including historical files, and native libbox log lines before sharing.
- Crash reports are masked before persistence, display in the debug log, and Logcat output.

These changes reduce accidental exposure but do not prove that every possible secret format is covered. Regression tests and a review of log call sites remain necessary.

## Release gates

Do not mark the project “fully compliant” until all applicable items above are closed with evidence. At minimum, before a public release:
1. Confirm target markets and distribution channels.
2. Publish privacy and terms pages with real owner/contact details.
3. Resolve Play-specific manifest and permission questions separately from the direct APK build.
4. Complete a source-to-binary license review including custom upstream terms and prebuilt native archives.
5. Run automated tests, security/dependency scans, and device-level VPN leak tests.
6. Review the actual Play Console Data safety / app-content declarations and the public release artifacts.

A green CI run confirms only the checks implemented in CI; it is not a legal certification.
