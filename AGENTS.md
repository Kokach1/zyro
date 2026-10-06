# AGENTS

1. Read `docs/SPEC.json` first, then `docs/PROGRESS.md`.
2. Keep the app as a plain, single-module Gradle Android project.
3. `core.*` must never import `ui.*`.
4. All incoming telemetry must enter through `TelemetryTransport`.
5. Put every threshold and tunable value in `AppConfig`; do not add magic values to logic.
6. Do not add dependencies or features outside `docs/SPEC.json`.
7. Preserve the package names and architecture already in the source.
8. Update `docs/PROGRESS.md` at the end of every work session.
9. Before handing over, run `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` when the environment permits it.
10. Record exact build/test results and any blocker in `docs/PROGRESS.md`.
