THIRD-PARTY SOFTWARE NOTICES

VLADOSS VPN is declared under GPL-3.0-or-later. The full license text is
provided as LICENSE in the source repository and in the app's licenses assets.
The software is provided without warranty to the extent permitted by applicable
law. The exact source commit used for each build is embedded in
licenses/SOURCE_COMMIT.txt.

1. VPN core: sing-box-lx / libbox
- Pinned release: Leadaxe/sing-box-lx v1.14.3-lx.21
- Source: https://github.com/Leadaxe/sing-box-lx/tree/v1.14.3-lx.21
- Downloaded artifact:
  https://github.com/Leadaxe/sing-box-lx/releases/download/v1.14.3-lx.21/libbox-1.14.3-lx.21.aar
- SHA-256:
  5a6c782b834d53aa8031e32a953eb9517e78b742bbc10d3474154fc05aa00e0e
- License: GPL-3.0-or-later, with the additional upstream naming/association
  condition reproduced in sing-box-lx-NOTICE.txt.
- The full GPL-3.0-or-later text is bundled as GPL-3.0-or-later.txt.

2. Android Gradle dependencies
- The release build generates a dependency-license report for
  directReleaseRuntimeClasspath and playReleaseRuntimeClasspath.
- The report is embedded in the app as assets/index.html.
- License/notice files extracted from dependency artifacts are bundled under
  assets/licenses/gradle.
- Standard license texts identified by the report are bundled in the
  assets/licenses directory.

3. Native Go dependencies included in libbox
- The audit uses the exact sing-box-lx source tag, Android build tags, and
  Android GOOS/GOARCH variants arm64, arm, 386, and amd64.
- The merged inventory is bundled as licenses/libbox-go-licenses.csv.
- License and notice files collected by the audit are bundled under
  licenses/libbox.
- Unknown license types and unresolved source URLs block the audit.

4. Prebuilt Cronet / Chromium native libraries
- The with_naive_outbound build tag includes libcronet.a archives from the
  SagerNet/cronet-go Android packages.
- Pinned cronet-go wrapper source:
  https://github.com/SagerNet/cronet-go/tree/0d28acc44093df24b2526dea3d6ffefd6b0a54f0
- Pinned per-ABI libcronet.a package source:
  https://github.com/SagerNet/cronet-go/tree/c10c03c318db6d47bec8705c8290bad34bc32b64
- The pinned NaiveProxy / Chromium source revision:
  https://github.com/SagerNet/naiveproxy/tree/72a06c9fca0e2d228588c7f3074bf7efff3ff686
- The cronet-go Go wrapper has its own GPL-3.0-or-later license. The native
  archives have a BSD-style three-clause base license plus third-party
  components with separate license and notice requirements.
- The native archive inventory is bundled as
  assets/licenses/cronet-native-licenses.csv. The pinned source's LICENSE,
  src/LICENSE, and third-party LICENSE/NOTICE/COPYING/CREDITS/AUTHORS/PATENTS/
  README.chromium files are bundled under
  assets/licenses/libbox/cronet-naiveproxy.

The CI/release workflow runs these inventories before packaging and fails if
the native Go audit cannot classify a license or resolve a source URL. The
reports and included upstream notices are evidence to review for each release;
this notice alone is not a legal certification.
