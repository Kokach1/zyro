# PROGRESS

## Current status
- Last updated: 2026-10-07, by Codex
- Build: `assembleDebug` pass; Tests: 14 passed / 0 failed
- Overall: Day 2 recovery and deterministic CRITICAL_RTL fix are complete; hardware integrations remain unimplemented and unverified.

## Step checklist (from build_order)
- [x] 1. Project, Gradle, theme, manifest, handoff files
- [x] 2. Core model and config
- [x] 3. Core analysis and unit tests
- [x] 4. Core alert engine and unit tests
- [x] 5. Transport and telemetry repository
- [x] 6. ViewModel and UI state
- [x] 7. Main monitor UI
- [x] 8. Alerts, simulator sheet and previews
- [x] 9. Documentation and final verification

## Next steps (do these next, in order)
1. Day 2 step 03: harden nullable/live-data contracts and alert freshness before adding a MAVLink dependency.
2. Add a resolved MAVLink decoder with encoded fixtures; do not claim physical telemetry until a G20 path is confirmed.
3. Do not add real aircraft commands without a confirmed mapping and bench validation.

## Known issues / unfinished
- Inter font files are not bundled; the app deliberately falls back to `FontFamily.SansSerif`.
- Build verification requires Android Studio's JDK and Android SDK to be available in the environment.
- Day 2 serial, MAVLink, audio, logging, service and pump requirements are not implemented yet; see `docs/FRD_TRACEABILITY.md`.

## Key decisions and assumptions
- The supplied pasted JSON is copied unchanged to `docs/SPEC.json` as the source of truth - 2026-10-06.
- The source uses the active battery profile as the simulator and analysis authority; values remain Agras-class approximations - 2026-10-06.

## File map
- `docs/SPEC.json`: verbatim user specification.
- `docs/PROGRESS.md`: continuation handoff log.
- `AGENTS.md`: repository continuation rules.
- `app/src/main/java/com/exodia/batteryalert/core`: models, config, analysis, alerts, simulator transport, telemetry repository.
- `app/src/main/java/com/exodia/batteryalert/ui`: custom Compose theme, monitor screen, view model, simulator controls.
- `app/src/test/java/com/exodia/batteryalert/CoreLogicTest.kt`: pure-logic test suite.
- `docs/TRANSPORTS_DAY2.md`: unverified Day 2 connection paths.
- `docs/DAY2_PLAN.md`: Day 2 boundary and validation plan.
- `docs/DAY2_SPEC.json`: verbatim Day 2 implementation brief.
- `docs/FRD_TRACEABILITY.md`: truthful Day 2 requirement status table.

## Session log
- 2026-10-06 Codex: initialized required handoff files and began a new Android project.
- 2026-10-06 Codex: implemented Day 1 project, then verified `assembleDebug` and `testDebugUnitTest` (10/10 passing).
- 2026-10-07 Codex: made cell tiles static; constrained the hero percent using `HeroNumberLayout`; corrected ring-zone geometry with a single clockwise scale. `assembleDebug` and 13 unit tests pass. CRITICAL_RTL scenario issue remains open.
- 2026-10-07 Codex: removed the normal-state weakest-cell outline that appeared to move with simulator noise; healthy tiles are visually static and the cell index is centred. Orange remains reserved for warning-voltage and cell-delta-fault tiles.
- 2026-10-07 Codex: saved Day 2 spec/traceability, fixed CRITICAL_RTL by emitting simulator home before the 900 m position and holding its test fixture deterministic; changed dynamic RTL comparison to inclusive `<=`. `assembleDebug` and 14 unit tests pass.
- 2026-10-07 Codex: Added comprehensive production README with architecture diagrams, badges, and documentation links. Set up git remote and branch for origin main push.
