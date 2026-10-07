# Zyro — Agricultural Drone Smart Battery Alert System

[![Platform: Android](https://img.shields.io/badge/Platform-Android%2013%20%7C%20API%2033-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x%20%7C%20JVM%2017-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Custom%20Instrument%20Theme-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Specification](https://img.shields.io/badge/Specification-CURRENT__FIX__SPEC.json%20(v2)-00C853?style=for-the-badge)](docs/CURRENT_FIX_SPEC.json)
[![Unit Tests](https://img.shields.io/badge/Unit%20Tests-89%2F89%20PASS-brightgreen?style=for-the-badge)](docs/TEST_REPORT.md)
[![Target](https://img.shields.io/badge/Hardware-Skydroid%20G20%20(7''%201080p)-FF6D00?style=for-the-badge)](docs/HARDWARE_SETUP.md)

> **High-Reliability Smart Battery Monitoring & Return-To-Launch (RTL) Alert System for Agricultural Spraying Drones.**  
> Target Platform: **Skydroid G20 Smart Controller** running Android 13 (7-inch landscape touchscreen, 1920x1080).  
> Operative Specification: **`docs/CURRENT_FIX_SPEC.json`** (`2026-10-07-v2-in-place-alerts`). All legacy Day 2 planning documents are superseded and retained for historical reference only.

---

## 1. Operational Overview

Agricultural spraying drone operations require immediate, unambiguous situational awareness under direct sunlight where operators observe telemetry in brief intervals.

**Zyro** provides an instrument-cluster monitoring interface that connects to real drone telemetry (UDP MAVLink, USB-Serial, Internal Serial) and integrated replay simulation. The system continuously evaluates dynamic return-to-launch (RTL) energy requirements, monitors cell balance and loaded voltage sag, logs continuous blackbox flight records, and issues prioritized in-place alerts before critical power exhaustion.

```
+-----------------------------------------------------------------------------------------------+
| [LIVE UDP]  14:28                    (Home) 850 m   (RTL ETA) 2 min 50 s   [Imbalance Chip]   |
+------------------------------------+----------------------------------------------------------+
|                                    | CELLS (14S)                      Avg: 3.82V  Delta: 0.04V|
|                78%                 | +----+ +----+ +----+ +----+ +----+ +----+ +----+         |
|             REMAINING              | | C1 | | C2 | | C3 | | C4 | | C5 | | C6 | | C7 | ...     |
|         (Dynamic RTL Arc)          | +----+ +----+ +----+ +----+ +----+ +----+ +----+         |
|                                    +----------------------------------------------------------+
|   TIME LEFT        RETURN NEEDS    | PACK VOLTAGE           CURRENT            TEMPERATURE    |
|    16 min              28%         |    53.4 V              110.0 A               38.5 C      |
+------------------------------------+----------------------------------------------------------+
```

---

## 2. Core Architecture & Capabilities

### In-Place Alert Presentation (User Visual Override)
Per operative specification, Critical and Emergency alerts use an **in-place red dashboard theme** rather than full-screen covering dialogs or flashing screens:
- **Critical Alert (`RETURN NOW`):** Static red dashboard theme with centered hero percentage and warning label. Triggered when remaining capacity falls below dynamic RTL requirement or any measured cell $\le 3.50\,\text{V}$. Non-dismissible while condition persists.
- **Emergency Alert (`LAND NOW`):** Static red dashboard theme with prominent emergency action prompt. Triggered when any cell $\le 3.40\,\text{V}$ or rapid voltage sag is detected. Non-dismissible while condition persists.
- **Warning Alert:** In-place amber accent and perimeter outline when capacity $\le 20\%$ or min cell $\le 3.65\,\text{V}$.
- **Notice Alert:** Compact yellow notice bar when capacity $\le 30\%$.
- **Cell Fault Alert:** Independent in-place orange badge showing exact cell delta and affected cell indices whenever voltage imbalance $> 0.08\,\text{V}$, coexisting with all battery tiers.

### Real Telemetry Ingestion & Transport Layer
- **Unified Streaming Codec (`MavlinkCodec`):** Pure-Kotlin MAVLink 1 and 2 streaming decoder supporting CRC_EXTRA verification, automatic garbage/noise resynchronization, fragmented reads, concatenated packets, and multi-pack isolation.
- **Lossless Field Mapping:** Preserves cell slot positions across 6S, 12S, and 14S without index compression. Supports $1\,\text{mV}$ ($0.001\,\text{V}$) sentinel encoding for near-zero cells.
- **Bounded SYS_STATUS Fallback (`BatteryStateAccumulator`):** Accumulates primary `BATTERY_STATUS` pack metrics and incorporates `SYS_STATUS` only for defined aggregate fallback fields without overwriting richer primary cell timestamps.
- **Transports:**
  - **Live UDP (`UdpTransport`):** Datagram socket binding with source filters and 250ms monotonic watchdog.
  - **Live USB Serial (`UsbSerialTransport`):** Platform-injected `ByteStreamSource` via Android `UsbManager` with explicit permission handling, connection lifecycle, and device detach cleanup.
  - **Live Internal Serial (`InternalSerialTransport`):** POSIX termios / JNI adapter (`AndroidInternalSerialStream`) with raw baud configuration and non-blocking reads.
  - **Deterministic Simulator (`SimulatorTransport`):** Isolated scenarios with zero noise, virtual simulation timeline, geodesic distance fixtures, and simulated pump load reduction.
  - **Replay Transport (`ReplayTransport`):** Structured JSONL and timestamp-framed `.tlog` reader with virtual replay clock and strict hardware write suppression.
- **Cold Launch Flow:** Directly presents connection setup. No synthetic or simulator values are displayed without explicit operator action.

### Physics, Analytics & State Estimation
- **Loaded Voltage Sag Compensation:** Evaluates chemical resting state $V_{rest} = V_{meas} + I \times R_i$ only when valid current is present. Loaded voltage is preserved for threshold evaluation.
- **Dynamic RTL Estimation (`RtlCalculator`):** Continuously calculates $Req\% = \left(\frac{\text{Distance}}{\text{Speed}}\right) \times \text{DischargeRate} + 15\%$, retaining unrounded $>100\%$ return requirements in core analytics.
- **Consumption Rate & Current Fallback (`ConsumptionHistory`):** Uses cumulative consumed-mAh counter slope over a rolling window, supplemented by trapezoidal current integration fallback and provisional instantaneous load estimation.
- **Evidence-Ranked Chemistry Detection (`ChemistryDetector`):** Detects battery chemistry (LiPo, Li-Ion, LiHV) and series count from stable low-load frames, reporting ambiguous states for operator confirmation.
- **Vehicle Arming State (`FlightClock`):** Tracks flight elapsed time keyed to verified autopilot heartbeat arm status.

### Hardware Output, Logging & Interlocks
- **Priority Audio & Haptic Scheduler (`AlertOutputScheduler`):** Schedules emergency speech, critical siren (AudioTrack PCM tone), warning repeats, single-entry notice chimes, and Android vibrator patterns without speech collisions.
- **Periodic Voice Status:** 60-second status announcement cycle speaking fresh battery percentage and cell averages.
- **Continuous Blackbox Logger (`TelemetryLogger`):** Streams versioned JSONL records to private storage with bounded memory queues, GPS association, and SAF document export.
- **Automatic Pump Cutoff Interlock (`PumpInterlockController`):** Opt-in $\le 20\%$ spraying pump cutoff controller requiring verified hardware mapping before command transmission. Zero commands in Replay or Simulator.
- **Foreground Monitoring Service (`TelemetryService`):** Retains single-session lifecycle across activity recreation, screen rotation, and background states.

---

## 3. Package Structure

```
com.exodia.batteryalert
├── core/
│   ├── alert/          # AlertEngine, AlertOutputScheduler, ActiveAlert, AlertReason
│   ├── analysis/       # BatteryAnalyzer, RtlCalculator, ChemistryDetector, ConsumptionHistory, FlightClock, GeoMath
│   ├── config/         # AppConfig, TransportConfig, RealConnectionConfig, BatteryProfiles
│   ├── control/        # PumpInterlockController, CommandPort
│   ├── logging/        # TelemetryLogger, LogRecord
│   ├── model/          # Pure models (BatteryFrame, PositionFrame, HomeFrame, VehicleStateFrame, SessionSource)
│   ├── telemetry/      # TelemetryRepository, BatteryStateAccumulator, watchdog loop
│   └── transport/      # TelemetryTransport, MavlinkCodec, UdpTransport, UsbSerialTransport, InternalSerialTransport, ReplayTransport, SimulatorTransport
├── platform/
│   ├── audio/          # TonePlayer, HapticController, SpeechCoordinator, AndroidAlertOutput
│   ├── service/        # TelemetryService (Foreground session owner)
│   └── transport/      # AndroidUsbSerialStream, AndroidInternalSerialStream, AndroidStreamSourceProvider
├── ui/
│   ├── debug/          # SimulatorControlSheet
│   ├── monitor/        # BatteryMonitorScreen, BatteryMonitorViewModel, BatteryUiState, AlertPresentation
│   ├── setup/          # ConnectionSetupSheet, ConnectionSetupContent
│   └── theme/          # AlertColors, Semantic Theme, Typography, Shapes
└── BatteryAlertApp.kt  # Application entry & AppContainer session owner
```

---

## 4. Verification & Testing

The repository maintains an automated test suite verifying core contracts, codecs, analytics, transports, scheduler, and logger without Android dependencies.

```bash
# Build the debug APK
./gradlew --no-daemon assembleDebug

# Execute all 89 unit tests across 13 test suites
./gradlew --no-daemon testDebugUnitTest
```

### Test Suite Summary (89 Tests — 100% Passing)
- `AnalysisDetectionConsumptionRtlTest`: Chemistry detection, sag compensation, current integration, dynamic RTL math (11 tests).
- `CodecAndMergeTest`: MAVLink 1/2 packets, CRC, sentinels, 14S extensions, SYS_STATUS fallback (9 tests).
- `CoreLogicTest`: Domain logic, alert thresholds, cell delta boundary (14 tests).
- `FreshnessAndRecoveryTest`: Central watchdog, per-field expiry, hysteresis downgrade, link loss latching (8 tests).
- `InterlockAndLoggerTest`: Pump cutoff gates, blackbox JSONL streaming, round-trip serialization (7 tests).
- `MavlinkCodecTest`: Decoder frame boundaries, resynchronization, packet decoding (10 tests).
- `OutputAndServiceTest`: Output priority scheduler, audio/haptic dispatch, periodic speech loop (8 tests).
- `ReplayTransportTest`: File not found error, JSONL replay emission (2 tests).
- `SerialPlatformWiringTest`: USB driver enumeration, permission grant/deny, stream contracts (8 tests).
- `SetupAndSessionSourceTest`: Cold launch setup, typed configuration validation, session source derivation (7 tests).
- `SimulatorReplayIntegrationTest`: Golden dynamic RTL scenario, speed consistency, scenario isolation, replay parsing (7 tests).
- `UdpTransportTest`: Datagram receive loop, watchdog lifecycle (1 test).
- `UsbSerialTransportTest`: Byte stream adapter, buffer reads (2 tests).

---

## 5. Repository Documentation

- **[Authoritative Fix Specification (`CURRENT_FIX_SPEC.json`)](docs/CURRENT_FIX_SPEC.json):** Sole operative specification (`2026-10-07-v2-in-place-alerts`).
- **[Progress Log (`PROGRESS.md`)](docs/PROGRESS.md):** Session progression, implementation checklists, and test records.
- **[Requirements Traceability (`FRD_TRACEABILITY.md`)](docs/FRD_TRACEABILITY.md):** Matrix mapping company functional requirements FR-1.1 through FR-5.3 to implementation and tests.
- **[Hardware Setup Guide (`HARDWARE_SETUP.md`)](docs/HARDWARE_SETUP.md):** Skydroid G20 / GR01 physical wiring, USB OTG, internal serial node, and UDP network topology.
- **[Known Hardware Limitations (`KNOWN_LIMITATIONS.md`)](docs/KNOWN_LIMITATIONS.md):** Documentation of unconfirmed vendor parameters (G20 port nodes, pump actuator mappings).
- **[Operator Demo Script (`DEMO_SCRIPT.md`)](docs/DEMO_SCRIPT.md):** Procedures for demonstrating simulation scenarios and replay verification.
- **[Test Execution Report (`TEST_REPORT.md`)](docs/TEST_REPORT.md):** Full test run outputs, execution environments, and verification evidence.

---

## 6. License
Internal proprietary project for **Exodia Drone Systems**. All rights reserved.

