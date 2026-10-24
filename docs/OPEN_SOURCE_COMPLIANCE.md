# Open-source license compliance

## Scope

This Android app packages a local `libbox.aar` from sing-box-lx
(`1.14.3-lx.21`) and uses AndroidX, Kotlin, OkHttp, Hilt, Room, WorkManager,
SnakeYAML, Timber, JUnit and other Gradle dependencies. The repository README
declares GPL-3.0 for the project.

## Findings from the initial audit

- The README declares GPL-3.0, but the repository did not contain a root
  `LICENSE` file.
- The app's resource packaging excludes common `META-INF/LICENSE*` and
  `META-INF/NOTICE*` resources. The build therefore must not rely on those
  resources alone to provide notices.
- The bundled `libbox.aar` is from sing-box-lx, which identifies its license as
  GPL-3.0 inherited from sing-box. The associated upstream notice must be
  preserved.
- A complete inventory of direct and transitive Maven dependencies and the
  native Go dependencies inside the AAR has not yet been verified.

## Required completion criteria

1. Keep the complete GPL-3.0 text in the source repository and in the APK.
2. Preserve the upstream copyright and additional notices applicable to
   sing-box/sing-box-lx.
3. Generate a dependency-license report for the release runtime classpath;
   resolve every missing/unknown license and include required notices and texts.
4. Inspect the pinned AAR's bundled native libraries and corresponding source
   availability. A Maven report cannot fully identify dependencies compiled
   into a local AAR.
5. Provide an accessible in-app open-source licenses screen or equivalent
   discoverable UI entry that opens the notices and full license texts.
6. Make the license audit part of CI and retain its report as a release artifact.
7. Re-run the release build and inspect the produced APK contents before
   calling the project compliant.

**Status: in progress.** The presence of these audit notes and the core GPL
text does not by itself establish complete compliance. This is a technical
checklist, not a legal opinion.
