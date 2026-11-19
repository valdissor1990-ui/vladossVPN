# Product strategy: novice-first Smart Advisor

## Product direction

Build vladossVPN as a novice-friendly Android VPN client with advanced capabilities kept behind clear, optional controls. The default experience should explain what the app recommends and let the user make the final decision.

The product is a client for user-supplied subscriptions. It does not own or operate a global server network, so product quality must be measured by compatibility, connection success, stability, privacy, and recovery—not by claims about the number of countries or servers.

## Decisions from the product survey

- Audience: novice users.
- Differentiation: stability, smart server choice, restricted-network resilience, security, speed/battery efficiency, and broad subscription compatibility.
- Networks: Wi-Fi, LTE, and 5G, with network-specific measurements.
- Selection: recommendation-first. The app may rank and explain candidates, but must not silently replace the active server. The user confirms a suggested server.
- Routing: Clash-level capability (apps, domains, IP ranges, countries, and policy groups) with safe presets and a simple default UI.
- Security: DNS/IP/IPv6 leak checks, Kill Switch/Always-on guidance, local secret protection, and optional ad/tracker filtering.
- UI: one clear primary connection flow; expert controls remain in a separate advanced area.
- Additional capabilities: network profiles, adaptive MTU, diagnostics, backup/restore, subscription comparison, more protocols, and multi-route policies.

## Delivery principles

1. Preserve working VPN connectivity before adding features.
2. Keep each change isolated and reversible; use feature branches and pull requests.
3. Do not change the pinned libbox artifact, checksum, branding, or license notices without a demonstrated need and explicit review.
4. Never add bundled subscriptions or hard-coded public server profiles. Use user-provided subscriptions only.
5. Never log subscription URLs, UUIDs, passwords, Reality keys, or traffic content.
6. Explain failures in plain language and retain a technical details view for troubleshooting.
7. Do not silently switch the active server in recommendation mode.
8. Do not claim a security property is active merely because a setting or Android manifest flag exists; verify runtime behavior on supported Android versions and networks.
9. Do not mark a feature complete until implementation, build, and the relevant manual/device checks are recorded.
10. Current project instruction: do not add or run tests manually until the user changes that instruction; existing GitHub Actions may continue to run their automated checks.

## Prioritized roadmap

### P0 — Reliability and compatibility

- Review and complete the existing focused PRs for connection lifecycle, network identity, network scan cache, privacy-safe logging, and URI import normalization.
- Validate Wi-Fi ↔ LTE/5G handover, VPN start/stop, background lifecycle, and reconnect behavior.
- Improve subscription import reporting: recognized format, imported count, skipped count, and safe rejection reasons. Never include credential-bearing source text in diagnostics.
- Preserve all supported transport and TLS/Reality/XHTTP/gRPC parameters during import and update.

### P1 — Smart Server Advisor (recommendation-only)

- Rank candidates using successful proxy probes, latency, jitter, packet loss, measured throughput, and historical stability.
- Keep separate quality history per physical network bucket (Wi-Fi vs cellular).
- Display one recommended candidate with a short reason and confidence/measurement freshness.
- Require explicit user confirmation before changing the selected server.
- Distinguish “not measured”, “unreachable”, and “measurement stale”; do not treat missing measurements as zero.

### P1 — Security and diagnostics

- Add a guided connection safety check for public IP, DNS resolver, IPv6 reachability/leaks, and Kill Switch state.
- Report what was actually checked, when, and what could not be verified.
- Provide plain-language diagnosis for DNS failure, handshake failure, no traffic, network handover, and Android background restrictions.
- Keep sensitive values masked in all logs and exported diagnostics.

### P2 — Guided routing

- Add a rule model for application, domain, IP/CIDR, and country/GeoIP matching.
- Provide conservative presets and a preview of which rule will match before applying.
- Preserve current split-tunneling behavior and avoid silently changing allow-list/deny-list semantics.
- Consider per-app proxy groups and multiple routes only after rule evaluation is deterministic and understandable.

### P2 — Network profiles and performance

- Store independent measurements and optional policies for Wi-Fi and cellular.
- Introduce adaptive MTU only with bounded values, rollback, and a clear explanation; do not silently mutate user settings.
- Avoid expensive repeated probes; respect battery policy and cache freshness.

### P3 — Optional privacy and quality-of-life features

- Optional DNS-based ad/tracker filtering with clear allow-list controls and no promise to block all advertising.
- Encrypted backup/restore of user settings and subscriptions, with explicit user action and safe handling of secrets.
- Subscription comparison that reports added/removed/changed profiles without exposing credentials.
- Add protocols only when import, core generation, and real-device behavior can be verified.

## Release gates

- No change is merged directly into main without review.
- No claim of successful build, tests, or device validation without an actual run.
- Every network-sensitive change must state which Wi-Fi/cellular scenarios remain unverified.
- Security features must fail visibly and safely; a failed check must not be shown as “protected”.
- The first implementation slice should remain narrow: finish and review the existing reliability/import/privacy PRs before introducing new protocol or UI complexity.
