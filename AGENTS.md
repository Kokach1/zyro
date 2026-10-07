# AGENTS

1. Read `docs/SPEC.json` first, then `docs/PROGRESS.md`.
2. Keep the app as a plain, single-module Gradle Android project.
3. `core.*` must never import `ui.*`.
4. All incoming telemetry must enter through `TelemetryTransport`.
5. Put every threshold and tunable value in `AppConfig`; do not add magic values to logic.
6. Do not add dependencies or features outside `docs/SPEC.json` (Day 2: `docs/DAY2_SPEC.json`).
7. Preserve the package names and architecture already in the source.
8. Update `docs/PROGRESS.md` at the end of every work session.
9. Before handing over, run `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` when the environment permits it.
10. Record exact build/test results and any blocker in `docs/PROGRESS.md`.
11. For Day 2 work, read `docs/DAY2_SPEC.json` (v2: frd-first-v2-2026-10-07) and maintain `docs/FRD_TRACEABILITY.md`.
12. Day 1 spec archived at `docs/SPEC.json`. Day 2 v1 spec archived at `docs/DAY2_SPEC_v1.json`.
13. Cold launch shows real Connection/Setup — no automatic simulator start. Simulator button is explicit only.
14. Company-exact FULLSCREEN_FRD is the default alert presentation. IN_PLACE is an explicit non-literal option.
15. core.* must never import android.*, Compose, or Android lifecycle APIs.

