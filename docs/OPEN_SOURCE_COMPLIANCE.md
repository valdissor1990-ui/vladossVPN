# Open-source license compliance

## Scope and source pins

VLADOSS VPN is declared under GPL-3.0-or-later. The Android app packages
`libbox.aar` from `Leadaxe/sing-box-lx` release `v1.14.3-lx.23` and has
Android Gradle runtime dependencies. The `with_naive_outbound` tag also brings
in prebuilt Cronet/Chromium static archives through `cronet-go`.

## Implemented in this branch

- Added the full GPL-3.0-or-later text to the source repository and Android
  assets.
- Preserved the sing-box-lx copyright and additional naming/association
  condition in an explicit notice file.
- Added an in-app Open Source Licenses entry with the core notice, source
  revision, license texts, Gradle inventory, native Go inventory, and Cronet
  native-binary inventory.
- Added a Gradle dependency-license report for the Direct and Play release
  runtime classpaths. The generated report and available license/notice files
  are packaged into the APK/AAB.
- Added a native Go dependency audit for the exact sing-box-lx tag, Android
  build tags, and all Android ABIs `arm64`, `arm`, `386`, and `amd64`.
  The audit fails on unknown license types or unresolved source URLs.
- Added a separate inventory for the prebuilt `libcronet.a` archives. The
  exact `cronet-go` and NaiveProxy/Chromium revisions are pinned, and the
  upstream LICENSE/NOTICE/COPYING/README.chromium files are collected into the
  app assets.
- Added a build-generated `SOURCE_COMMIT.txt` that points to the exact app
  commit and pinned native source revisions.
- Added CI checks that build the Direct release APK and Play AAB and verify
  the source pointer, reports, license texts, and Cronet/Chromium notices are
  physically present in both artifacts.
- Added a release workflow step that packages the notices and reports into
  `license-notices.zip` and attaches it to the release.

## Latest automated verification

The successful CI run for the implementation commit is available at
https://github.com/valdissor1990-ui/vladossVPN/actions/runs/40075992534.

- 156 resolved Gradle release dependency entries in the HTML report.
- 125 native Go package/license rows merged across Android ABIs.
- Zero unknown native license types and zero unresolved native source URLs.
- Four `libcronet.a` ABI entries in a separate native-binary inventory.
- 83 Cronet/Chromium license and notice files collected from the pinned
  NaiveProxy/Chromium source revision.
- Unit tests passed, and the release APK/AAB asset-presence checks passed.

These counts are specific to the pinned dependency graph and must be
regenerated if the core, build tags, or dependencies change.

## Remaining verification before declaring full legal compliance

1. When publishing, run the release workflow for the exact release tag and
   retain the embedded source commit and corresponding source tree publicly.
2. Have qualified legal counsel review the upstream custom/additional license
   terms and the intended distribution channel (direct APK vs. Play AAB).

The generated reports and included upstream notices are evidence to review for
each release; a passing build is not itself a legal certification.
