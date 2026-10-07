# AGENTS

1. Read `docs/CURRENT_FIX_SPEC.json` first, then `docs/PROGRESS.md`. All older specs (`docs/DAY2_SPEC.json`, `docs/DAY2_SPEC_v1.json`, `docs/SPEC.json`) are SUPERSEDED and historical only.
2. Keep the app as a plain, single-module Gradle Android project.
3. `core.*` must never import `ui.*`, `android.*`, Compose, or Android lifecycle APIs.
4. All incoming telemetry must enter through `TelemetryTransport`.
5. Put every threshold and tunable value in `AppConfig`; do not add magic values to logic.
6. Do not add dependencies or features outside `docs/CURRENT_FIX_SPEC.json`.
7. Preserve the package names and architecture already in the source.
8. Update `docs/PROGRESS.md` at the end of every work session/stage.
9. Before handing over, run `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` when the environment permits it.
10. Record exact build/test results and any blocker in `docs/PROGRESS.md`.
11. Follow `docs/CURRENT_FIX_SPEC.json` (v2-in-place-alerts) and maintain `docs/FRD_TRACEABILITY.md`.
12. Historical specs archived: `docs/SPEC.json`, `docs/DAY2_SPEC_v1.json`, `docs/DAY2_SPEC.json`.
13. Cold launch shows real Connection/Setup — no automatic simulator start. Simulator button is explicit, clean, centered, and labeled "Simulator".
14. IN_PLACE red dashboard theme with live metrics and warning/action labels is required for Critical/Emergency (user visual override). No full-screen modals, flashing screens, or covering overlays.
15. Strictly professional UI: No emojis or decorative Unicode pictograms in app UI, notifications, errors, or simulator. Remove NOT_TESTED suffixes from user strings.

