# Open-source license compliance

## Scope and source pins

VLADOSS VPN is declared under GPL-3.0-or-later. The Android app packages
`libbox.aar` from `Leadaxe/sing-box-lx` release `v1.14.3-lx.21` and has
Android Gradle runtime dependencies.

## Implemented in this branch

- Added the full GPL-3.0-or-later text to the source repository and Android
  assets.
- Preserved the sing-box-lx copyright and additional naming/association
  condition in an explicit notice file.
- Added an in-app Open Source Licenses entry.
- Added a Gradle dependency-license report for the Direct and Play release
  runtime classpaths. The generated report and available license/notice files
  are packaged into the APK/AAB and are available from the in-app screen.
- Added a native Go dependency audit for the exact sing-box-lx tag and Android
  build tags. It generates a CSV inventory and collects license/notice files.
- Added release checks that verify license assets are present in the APK/AAB.

## Remaining verification before declaring full compliance

1. Confirm the native Go audit completes with no unknown license classifications
   and inspect any upstream custom/additional license terms.
2. Confirm the generated native and Gradle inventories are present in the final
   APK and AAB, and inspect the actual release outputs.
3. Confirm the source corresponding to the exact APK/AAB is published and the
   pinned libbox source and build instructions are clearly accessible.
4. Review the final notices and source-distribution method for the exact
   distribution channel (direct APK vs. Play AAB) with qualified legal counsel.

The release workflow is intended to block packaging if the native license audit
cannot classify a dependency. Do not treat a passing build alone as a legal
opinion; the generated reports and the applicable upstream license texts must
be reviewed for each release.
