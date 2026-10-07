# FRD Traceability — Zyro Battery Alert System

> Baseline snapshot: `3492458` · Last updated: 2026-10-07 · Authority: `docs/CURRENT_FIX_SPEC.json` (v2-in-place-alerts)

## Legend

| Status | Meaning |
|--------|---------|
| ✅ PASS | Implemented, tested, verified in software |
| 🔨 IN_PROGRESS | Implementation started / partial |
| ⬜ NOT_STARTED | No implementation yet |
| 🚫 BLOCKED | Cannot proceed without external vendor/hardware facts |
| 🔧 NOT_TESTED | Executable code exists; physical bench testing pending hardware |

---

## Company FRD Requirements Traceability

| ID | Requirement | Software Status | Physical Status | Implementation | Test Suite | Evidence / Notes |
|----|-------------|-----------------|-----------------|----------------|------------|------------------|
| FR-1.1 | Serial connection to Skydroid G20 / GR01 via USB-UART or internal /dev/ttySx | ✅ PASS | 🔧 Pending Bench | `UsbSerialTransport.kt`, `AndroidUsbSerialStream.kt`, `InternalSerialTransport.kt`, `AndroidInternalSerialStream.kt` | `SerialPlatformWiringTest` (8 tests), `UsbSerialTransportTest` (2 tests) | Pure Kotlin `ByteStreamSource`, Android UsbManager lifecycle/permission, POSIX termios bridge. G20 vendor node/baud unconfirmed. |
| FR-1.2 | Parse BATTERY_STATUS, SYS_STATUS, GLOBAL_POSITION_INT | ✅ PASS | 🔧 Pending Bench | `MavlinkCodec.kt`, `BatteryStateAccumulator.kt` | `MavlinkCodecTest` (10 tests), `CodecAndMergeTest` (9 tests) | Full MAVLink 1/2 streaming codec, 14S voltagesExt, sentinel cell=1, middle slot holes, aggregate-only detection, CRC_EXTRA validation, bounded SYS_STATUS fallback. |
| FR-1.3 | Auto-detect chemistry/cell config (6S/12S/14S) | ✅ PASS | 🔧 Pending Bench | `ChemistryDetector.kt` | `AnalysisDetectionConsumptionRtlTest` (11 tests) | Evidence-ranked detection over stable baseline, handles ambiguous/overlapping curves honestly. |
| FR-2.1 | Vrest = Vmeasured + I * Ri | ✅ PASS | 🔧 Pending Bench | `BatteryAnalyzer.kt` | `AnalysisDetectionConsumptionRtlTest` | Resting voltage compensated using validated Ri per cell only when current is known. |
| FR-2.2 | Cell delta > 0.08V immediate fault | ✅ PASS | 🔧 Pending Bench | `AlertEngine.kt` | `AnalysisDetectionConsumptionRtlTest`, `FreshnessAndRecoveryTest` | Strictly > 0.08V; evaluated immediately and independently of battery tier. |
| FR-2.3 | mAh/min and remaining time tracking | ✅ PASS | 🔧 Pending Bench | `ConsumptionHistory.kt`, `BatteryAnalyzer.kt` | `AnalysisDetectionConsumptionRtlTest` | Primary cumulative counter slope + fallback trapezoidal current integration + provisional instantaneous load. |
| FR-3.1 | Dynamic RTL required% formula | ✅ PASS | 🔧 Pending Bench | `RtlCalculator.kt` | `AnalysisDetectionConsumptionRtlTest`, `SimulatorReplayIntegrationTest` | Formula verified; unrounded core math, golden 900m/110A/30Ah=33.333% dynamic RTL requirement verified. |
| FR-3.2 | Un-dismissable critical RTL alert | ✅ PASS | 🔧 Pending Bench | `AlertEngine.kt`, `BatteryMonitorScreen.kt` | `FreshnessAndRecoveryTest`, `SimulatorReplayIntegrationTest` | In-place persistent red theme alert when remaining <= required%; un-dismissable. |
| FR-5.1 | Auto spray pump OFF at <=20% | ✅ PASS | 🔧 Pending Bench | `PumpInterlockController.kt` | `InterlockAndLoggerTest` (7 tests) | Opt-in gate, verified mapping gate, max 3 retries, ACK validation, no auto-ON, no Replay writes. Actual actuator channel unconfirmed. |
| FR-5.2 | Periodic TTS battery status every 60s | ✅ PASS | 🔧 Pending Bench | `AlertOutputScheduler.kt`, `platform/audio/SpeechCoordinator.kt` | `OutputAndServiceTest` (8 tests) | 60s loop, speaks fresh percent and cell average, omits unavailable values, yields to urgent alerts. |
| FR-5.3 | Continuous battery/GPS blackbox logging | ✅ PASS | 🔧 Pending Bench | `TelemetryLogger.kt` | `InterlockAndLoggerTest` (7 tests) | Streaming versioned JSONL with GPS timestamp association, null for stale GPS, non-blocking bounded channel, round-trip export. |

---

## Alert Matrix & Intentional Visual Presentation Deviation

> **User Visual Override Note:** Critical and Emergency alert presentation intentionally uses an **in-place red dashboard theme** with prominent `RETURN NOW` and `LAND NOW` action labels at the hero percentage. In accordance with user requirements, the literal FRD full-screen covering modal and blinking/flashing screen overlays were removed to preserve continuous metric visibility outdoors.

| Level | Trigger | Software Status | Visual Presentation | Audio / Haptic Output | Notes |
|-------|---------|-----------------|---------------------|----------------------|-------|
| NOTICE | remaining <= 30% | ✅ PASS | Compact yellow top-bar notice | Single entry chime & vibration | Triggered once upon entry; no frame storm. |
| WARNING | remaining <= 20% OR cell <= 3.65V | ✅ PASS | In-place amber border accent | Repeated voice alert | Telemetry remains visible. |
| CRITICAL | remaining <= RTL required% OR cell <= 3.50V | ✅ PASS | Static in-place red dashboard theme + `RETURN NOW` label | Continuous siren & strongest vibration | Visual override: NO full-screen covering modal. |
| EMERGENCY | cell <= 3.40V OR rapid sag | ✅ PASS | Static in-place red dashboard theme + `LAND NOW` label | Continuous voice commands | Visual override: NO flashing screen. |
| CELL_FAULT | delta > 0.08V | ✅ PASS | In-place orange fault chip with delta and cell indices | Spoken action prompt | Immediate and independent of battery tier. |

---

## Hardware Limitations & Unconfirmed Vendor Facts

| Item | Status | Impact / Next Action |
|------|--------|----------------------|
| Skydroid G20 Internal Serial Port Path & Baud | 🚫 UNCONFIRMED | Platform adapter `AndroidInternalSerialStream` is implemented and configurable; exact hardware node (e.g. `/dev/ttyS3` vs vendor JNI) requires bench verification. |
| USB Vendor/Product IDs for Skydroid G20 / GR01 | 🚫 UNCONFIRMED | USB Host subsystem probes all standard USB-UART drivers; explicit VID/PID selection should be confirmed on hardware. |
| Spraying Pump Actuator Channel & Feedback Mapping | 🚫 UNCONFIRMED | `PumpInterlockController` enforces an opt-in safety gate; live command dispatch is inhibited until vendor mapping is confirmed. |
| Controller Audio / Haptics Hardware | 🔧 EXECUTABLE | `AudioTrack` siren generator and Android `Vibrator` / `VibratorManager` implementations are wired; audible/tactile strength to be evaluated on physical controller. |
