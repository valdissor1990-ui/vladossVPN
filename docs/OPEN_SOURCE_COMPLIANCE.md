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
- Added an in-app Open Source Licenses entry with the core notice, license
  texts, Gradle inventory, and native Go inventory.
- Added a Gradle dependency-license report for the Direct and Play release
  runtime classpaths. The generated report and available license/notice files
  are packaged into the APK/AAB.
- Added a native Go dependency audit for the exact sing-box-lx tag and Android
  build tags. The audit collects license/notice files and fails if any
  dependency remains unclassified.
- Added release checks that verify license assets are present in the APK/AAB.

## Latest automated inventory

The CI inventory for the pinned `v1.14.3-lx.21` core produced:

- 156 resolved Gradle release dependency entries in the HTML report.
- 128 native Go package/license rows in the CSV report.
- Zero unknown native license types and zero unresolved native source URLs
  after mapping local submodules to the pinned upstream source tree.

The counts are specific to this dependency graph and must be regenerated for
each release.

## Remaining verification before declaring full compliance

1. Build the final release APK and Play AAB and verify the license report,
   standard license texts, native license texts, and notices are physically
   present in both artifacts.
2. Confirm the source corresponding to the exact APK/AAB is published and the
   pinned libbox source and build instructions are clearly accessible.
3. Review all upstream custom/additional license terms for the exact release
   and distribution channel (direct APK vs. Play AAB) with qualified legal
   counsel.

The release workflow is intended to block packaging if the native license
audit cannot classify a dependency. Do not treat a passing build alone as a
legal opinion; the generated reports and applicable upstream license texts
must be reviewed for each release.
