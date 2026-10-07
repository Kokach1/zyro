# FRD Traceability — Zyro Battery Alert System

> Baseline snapshot: `08d7b0b` · Last updated: 2026-10-07 · Agent: Antigravity

## Legend

| Status | Meaning |
|--------|---------|
| ✅ PASS | Implemented, tested, verified in software |
| 🔨 IN_PROGRESS | Implementation started |
| ⬜ NOT_STARTED | No implementation yet |
| 🚫 BLOCKED | Cannot proceed without external input |
| 🔧 NOT_TESTED | Code exists, hardware/physical bench not done |

---

## Company FRD Requirements

| ID | Requirement | Status | Implementation | Test | Notes |
|----|-------------|--------|----------------|------|-------|
| FR-1.1 | USB-UART or internal /dev/ttySx serial connection | 🔧 NOT_TESTED (Physical) / ✅ PASS (Software) | `UsbSerialTransport.kt`, `AndroidUsbSerialStream.kt`, `InternalSerialTransport.kt` | `UsbSerialTransportTest` | Pure core ByteStreamSource, real Android UsbManager adapter, unit tests pass. Physical hardware unverified. |
| FR-1.2 | Parse BATTERY_STATUS, SYS_STATUS, GLOBAL_POSITION_INT | ✅ PASS | `MavlinkCodec.kt` | `MavlinkCodecTest` (10 tests) | Full MAVLink 1/2 streaming codec, 14S voltagesExt, sentinel cell=1, aggregate-only detection, CRC_EXTRA validation, resync. |
| FR-1.3 | Auto-detect chemistry/cell config (6S/12S/14S) | 🔨 IN_PROGRESS | `ChemistryDetector.kt` | `CoreLogicTest::detectorUsesCellsOrSafePackEstimate` | Immediate heuristic, no stable 5-frame baseline, packV/3.7 rounding |
| FR-2.1 | Vrest = Vmeasured + I * Ri | ✅ PASS | `BatteryAnalyzer.kt:13` | `CoreLogicTest::sagCompensationUsesPerCellResistance` | Per-cell Ri correct; units verified |
| FR-2.2 | Cell delta > 0.08V immediate fault | ✅ PASS | `AlertEngine.kt:21` | `CoreLogicTest::measuredCellDeltaIsStrictlyGreaterThanLimit` | Strictly > 0.08; delta from measured cells |
| FR-2.3 | mAh/min and remaining time | 🔨 IN_PROGRESS | `ConsumptionHistory.kt`, `BatteryAnalyzer.kt:21-22` | `CoreLogicTest::consumptionNeedsTenSecondsAndCalculatesRate` | No current integration fallback; 99min cap in core not UI |
| FR-3.1 | Dynamic RTL required% formula | ✅ PASS | `RtlCalculator.kt:11` | `CoreLogicTest::rtlFormulaAndReturnRounding`, `criticalRtlGoldenFixtureUsesTheExactBoundary` | Formula correct; golden 900m/110A/30Ah=33.33% verified |
| FR-3.2 | Un-dismissable critical RTL at <= required% | ✅ PASS | `AlertEngine.kt:25,46` | `CoreLogicTest::criticalRtlGoldenFixtureUsesTheExactBoundary` | Uses <=; dismissible=false |
| FR-5.1 | Auto pump OFF at 20% | ⬜ NOT_STARTED | `AppConfig.sprayInterlockPercent=20` only | — | Config exists, no controller/command/mapping |
| FR-5.2 | Periodic TTS every 60s | ⬜ NOT_STARTED | `AppConfig.ttsIntervalSeconds=60` only | — | NoOpAlertOutput; no TTS implementation |
| FR-5.3 | Continuous battery/GPS logs | ⬜ NOT_STARTED | — | — | No logger, no CSV writer |

---

## Alert Matrix

| Level | Trigger | Status | Visual | Audio/Haptic | Notes |
|-------|---------|--------|--------|-------------|-------|
| NOTICE | remaining <= 30% | ✅ PASS | In-place label only (no FRD yellow banner) | ⬜ No chime/vibration | AlertEngine correct; visual is non-literal |
| WARNING | remaining <= 20% OR cell <= 3.65V | ✅ PASS | In-place label only (no FRD amber overlay/pulsing) | ⬜ No voice alert | AlertEngine correct; visual non-literal |
| CRITICAL | remaining <= RTL required% OR cell <= 3.50V | ✅ PASS | In-place label (no FRD full-screen red modal) | ⬜ No siren/vibration | Undismissable; visual non-literal |
| EMERGENCY | cell <= 3.40V OR rapid sag | ✅ PASS | In-place label (no FRD flashing red) | ⬜ No voice command | Undismissable; visual non-literal |
| CELL_FAULT | delta > 0.08V | ✅ PASS | Orange chip (non-literal) | ⬜ No voice alert | Independent flag; immediate |

---

## Review Defect Ledger

| ID | Defect Summary | Status | Fix Location | Notes |
|----|---------------|--------|-------------|-------|
| CON-01 | Factory always simulator; no real transports | ✅ PASS | `TransportFactory.kt`, `TransportConfig.kt` | Exhaustive factory supports SIMULATOR, UDP, USB_SERIAL, INTERNAL_SERIAL, REPLAY. Cold launch setup UI. |
| CON-02 | No decoder, USB permission, baud/IP setup | ✅ PASS | `MavlinkCodec.kt`, `UdpTransport.kt`, `AndroidUsbSerialStream.kt` | Shared MAVLink 1/2 decoder, UDP loopback, Android USB host permission & prober. |
| DATA-01 | Missing cells fabricated from packV; no ages/quality | ⬜ NOT_STARTED | `BatteryAnalyzer.kt:12` | Step 08 |
| DATA-02 | Immediate heuristic chemistry; no stable baseline | ⬜ NOT_STARTED | `ChemistryDetector.kt` | Step 08 |
| TIME-01 | No current integration/bootstrap; 99min cap in core | ⬜ NOT_STARTED | `ConsumptionHistory.kt` | Step 08 |
| RTL-01 | First position becomes home; no NaN/invalid guard | ⬜ NOT_STARTED | `TelemetryRepository.kt:13` | Step 08 |
| FLIGHT-01 | current>=5A only; never disarms/freezes | ⬜ NOT_STARTED | `FlightClock.kt` | Step 08 |
| FRESH-01 | Timeout unused; no watchdog; any frame sets Connected | ⬜ NOT_STARTED | `TelemetryRepository.kt` | Step 08 |
| FRESH-02 | LinkLost overwrites Emergency; stale not in UI | ⬜ NOT_STARTED | `AlertEngine.kt:19` | Step 08 |
| ALERT-01 | Hysteresis unused; delta delayed by tier debounce | ⬜ NOT_STARTED | `AlertEngine.kt` | Step 08 |
| SIM-01 | Random cells mask criticalRTL; fixture not isolated | ⬜ NOT_STARTED | `SimulatorTransport.kt` | Step 09 |
| SIM-02 | Clock double-count; longitude gives ~886m | ⬜ NOT_STARTED | `SimulatorTransport.kt` | Step 09 |
| SIM-03 | FAST_DISCHARGE no distinct behavior; old history survives | ⬜ NOT_STARTED | `SimulatorTransport.kt` | Step 09 |
| OUT-01 | Actual output defaults NoOp; periodic unused | ⬜ NOT_STARTED | `AlertEngine.kt` | Step 11 |
| LIFE-01 | No service owner; stop in dying scope | ⬜ NOT_STARTED | `MainActivity.kt`, VM | Step 10 |
| LOG-01 | No continuous GPS logger | ⬜ NOT_STARTED | — | Step 12 |
| PUMP-01 | No cutoff even simulator; no mapping | ⬜ NOT_STARTED | — | Step 13 |
| PACK-01 | One battery despite ID; no per-pack history | ⬜ NOT_STARTED | — | Step 08 |
| UI-01 | Missing FRD-literal visuals; fixed 420dp ring | ⬜ NOT_STARTED | `BatteryMonitorScreen.kt` | Step 14 |
| PROOF-01 | 14 narrow tests; no transport/output/service tests | 🔨 IN_PROGRESS | `CoreLogicTest.kt` | Step 15 |

---

## Physical / Hardware Status

| Item | Status | Notes |
|------|--------|-------|
| Skydroid G20 serial node/baud | 🚫 UNKNOWN | Not supplied; requires bench test |
| Skydroid GR01 MAVLink dialect | 🚫 UNKNOWN | Assumed common v2 |
| USB VID/PID for G20 | 🚫 UNKNOWN | Not supplied |
| Pump command mapping | 🚫 UNKNOWN | Vendor/autopilot specific |
| Battery pack model/topology | 🚫 UNKNOWN | Profiles are approximations |
| G20 vibration motor | 🚫 UNKNOWN | May not have haptic motor |
