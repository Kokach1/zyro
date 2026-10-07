# PROGRESS

## Current status
- Last updated: 2026-10-07, by Antigravity
- Build: `assembleDebug` pass; Tests: 29 passed / 0 failed (CoreLogicTest: 14, MavlinkCodecTest: 10, ReplayTransportTest: 2, UdpTransportTest: 1, UsbSerialTransportTest: 2)
- Overall: Implemented Day 2 Steps 01–07: pure-core MAVLink 1 & 2 streaming codec with 14S voltagesExt, sentinel cell=1 handling, aggregate-only detection, CRC_EXTRA validation, and resynchronization; implemented real receive-only UDP transport with watchdog; implemented USB serial transport with ByteStreamSource abstraction and Android UsbManager adapter; implemented Internal serial and Replay transports; wired cold launch connection setup with explicit Simulator button.

## Step checklist (from build_order)
- [x] 01. Baseline and source precedence
- [x] 02. Connectivity data/config/setup prerequisites
- [x] 03. Shared MAVLink codec and golden bytes (10 unit tests)
- [x] 04. Actual receive-only UDP end-to-end (1 integration test)
- [x] 05. Actual USB serial path (2 unit tests)
- [x] 06. Actual internal serial bridge path
- [x] 07. Replay and connectivity milestone (2 unit tests)
- [ ] 08. Live analysis/alerts/home/arm/per-pack fixes
- [ ] 09. Deterministic simulator defects
- [ ] 10. Foreground real session/lifecycle
- [ ] 11. Actual alarm/TTS/haptic outputs
- [ ] 12. Blackbox logging/export
- [ ] 13. Spray interlock
- [ ] 14. Company-exact UI and visual regression
- [ ] 15. Full regression/submission handoff

## Next steps (do these next, in order)
1. Step 08: Live analysis/alerts/home/arm/per-pack fixes (DATA-01, DATA-02, TIME-01, RTL-01, FLIGHT-01, FRESH-01, FRESH-02, ALERT-01, PACK-01).
2. Step 09: Deterministic simulator defects (SIM-01, SIM-02, SIM-03).
3. Step 10: Foreground service and lifecycle session owner.

## Known issues / unfinished
- Inter font files are not bundled; the app deliberately falls back to `FontFamily.SansSerif`.
- Physical bench testing on Skydroid G20 / GR01 hardware remains blocked until physical hardware is available; software adapters and pure-logic contracts are verified.
- Steps 08 through 15 remain to be completed per `DAY2_SPEC.json`.

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
- 2026-10-07 Antigravity: Changed app label from "Battery Alert" to "Zyro" in AndroidManifest.xml. Added adaptive icon: solid black background layer (ic_launcher_background.xml) + white vector foreground layer (ic_launcher_foreground.xml) reproducing the Zyro wordmark with propeller-o glyph. Added ic_launcher.xml and ic_launcher_round.xml in mipmap-anydpi-v26. Verified with assembleDebug and testDebugUnitTest (14/14 passed).
- 2026-10-07 Antigravity: Implemented Day 2 Steps 01–07: (1) Added pure Kotlin MavlinkCodec supporting MAVLink 1 and 2, CRC_EXTRA validation, garbage resynchronization, fragmented reads, concatenated packets, 14S voltagesExt, sentinel cell=1 (0.001V) handling, and aggregate pack detection; (2) Implemented production UdpTransport with real DatagramSocket, MavlinkCodec, and watchdog; (3) Created ByteStreamSource abstraction and AndroidUsbSerialStream adapter using UsbManager & usb-serial-for-android; implemented UsbSerialTransport and InternalSerialTransport; (4) Implemented ReplayTransport for JSONL and .tlog replay; (5) Added 15 new deterministic unit and integration tests across MavlinkCodecTest, UdpTransportTest, UsbSerialTransportTest, and ReplayTransportTest. All 29 unit tests pass and assembleDebug builds cleanly.

