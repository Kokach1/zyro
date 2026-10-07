# Zyro — Agricultural Drone Smart Battery Alert System

[![Platform: Android](https://img.shields.io/badge/Platform-Android%2013%20%7C%20API%2033-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x%20%7C%20JVM%2017-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Custom%20Instrument%20Theme-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Architecture](https://img.shields.io/badge/Architecture-Clean%20%2F%20Unidirectional%20MVVM-00C853?style=for-the-badge)](docs/SPEC.json)
[![Target](https://img.shields.io/badge/Hardware-Skydroid%20G20%20(7''%201080p)-FF6D00?style=for-the-badge)](docs/SPEC.json)

> **High-Reliability Smart Battery Monitoring & Return-To-Launch (RTL) Alert System for Agricultural Spraying Drones.**  
> Target Hardware: **Skydroid G20 Smart Controller** running Android 13 (7-inch landscape touch screen, 1920x1080).

---

## 1. Overview

Agricultural spraying drone operations require immediate, clear situational awareness under direct sunlight conditions where operators frequently wear gloves and observe ground telemetry in brief intervals.

**Zyro** delivers an instrument-cluster style monitoring interface that connects to real drone telemetry (UDP MAVLink, USB-Serial, Internal Serial) and integrated replay simulation, continuously computes dynamic return-to-launch (RTL) battery requirements, monitors cell balance and voltage sag, and issues multi-tier alerts prior to critical power exhaustion.

```
+-----------------------------------------------------------------------------------------------+
| [LIVE]  14:28                        (Home) 850 m   (RTL ETA) 2 min 50 s   [Imbalance Chip] * |
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

## 2. Key Architecture & Features

### Real Telemetry Ingestion (MAVLink 1 & 2)
- **Shared Streaming Codec (`MavlinkCodec`):** Pure-Kotlin streaming MAVLink 1/2 decoder supporting CRC_EXTRA verification, automatic garbage/noise resynchronization, fragmented reads, and concatenated packets.
- **Full 14S Pack Support:** Parses `BATTERY_STATUS` with 10 standard cell voltages plus `voltagesExt` (cells 11..14), handling sentinel $1\,\text{mV}$ values ($0.001\,\text{V}$) for measured near-zero cells to guarantee cell fault alerts are never masked.
- **Selectable Transports:**
  - **UDP Transport (`UdpTransport`):** Datagram socket binding on port `14550` with connection watchdog (bind = `Connecting`, valid telemetry = `Connected`, inactivity timeout = `LinkLost`).
  - **USB Serial (`UsbSerialTransport`):** Pure-core `ByteStreamSource` abstraction connected to `AndroidUsbSerialStream` (backed by `usb-serial-for-android` and Android `UsbManager`).
  - **Internal Serial (`InternalSerialTransport`):** Candidate path for `/dev/ttySx` with permission diagnostics.
  - **Replay Transport (`ReplayTransport`):** Replays flight recordings from scoped JSONL and `.tlog` files with virtual clock pacing and hardware write suppression.
- **Cold Launch Flow:** Opens directly to real connection setup. Simulator runs strictly via an explicit secondary action.

### Instrument-Cluster UI Design
- **Single-Glance Hero Ring:** Custom 270-degree multi-zoned circular gauge displaying active state, dynamic RTL threshold markers, and warning bands.
- **Adaptive Cell Matrix:** Real-time individual cell telemetry supporting **6S, 12S, and 14S** architectures with outlier/imbalance highlighting.
- **High-Contrast Dark Theme:** Optimized `#0A0E13` background palette meeting WCAG AA contrast standards for outdoor sunlight readability without non-functional visual noise.

### Physics and Analytical Logic
- **Voltage Sag Compensation ($V_{rest} = V_{meas} + I \times R_i$):** Compensates cell voltage under heavy electrical loads to evaluate actual chemical state-of-charge.
- **Dynamic RTL Estimation:** Continuously evaluates $Req\% = \left(\frac{\text{Distance}}{\text{Speed}}\right) \times \text{DischargeRate} + \text{Margin}\%$.
- **Cell Delta Fault Detection:** Flags pack degradation whenever cell voltage imbalance exceeds $80\,\text{mV}$.
- **Rapid Sag Detection:** Identifies abnormal voltage drops ($\ge 0.15\,\text{V}$ within $2\,\text{s}$ under steady current).

### Alert State Machine & Hysteresis
Multi-tier priority alert pipeline with debouncing and anti-chatter hysteresis:
1. **EMERGENCY (`#FF1F44`):** Full-screen flashing modal. Triggered when any cell $\le 3.40\,\text{V}$ or rapid sag is active. Non-dismissible.
2. **CRITICAL (`#FF4D4F`):** Full-screen modal. Triggered when remaining capacity falls below Dynamic RTL requirement or any cell $\le 3.50\,\text{V}$. Non-dismissible.
3. **WARNING (`#FF9F1C`):** Pulsing amber perimeter and notification banner. Capacity $\le 20\%$ or cell $\le 3.65\,\text{V}$.
4. **NOTICE (`#F2D04B`):** Dismissible banner when capacity $\le 30\%$.
5. **CELL FAULT (`#FF7A2F`):** Dedicated delta warning indicator with outlier border highlights.

---

## 3. Package Layout & Boundaries

The codebase enforces strict separation of concerns where `core.*` packages contain **zero Android or UI dependencies**, enabling complete JVM unit testability.

```
com.exodia.batteryalert
├── core/
│   ├── alert/          # AlertEngine state machine, AlertLevel, AlertOutput
│   ├── analysis/       # BatteryAnalyzer, RtlCalculator, ChemistryDetector, GeoMath
│   ├── config/         # AppConfig, TransportConfig, BatteryProfiles
│   ├── model/          # Pure data classes (BatteryFrame, PositionFrame, HomeFrame, VehicleStateFrame)
│   ├── telemetry/      # TelemetryRepository, link watchdog, snapshot aggregation
│   └── transport/      # TelemetryTransport, MavlinkCodec, UdpTransport, UsbSerialTransport, ReplayTransport, ByteStreamSource
├── platform/
│   └── transport/      # AndroidUsbSerialStream (UsbManager + usb-serial-for-android)
├── ui/
│   ├── debug/          # SimulatorControlSheet composables
│   ├── monitor/        # BatteryMonitorScreen, BatteryMonitorViewModel, BatteryUiState
│   ├── setup/          # ConnectionSetupSheet
│   └── theme/          # Color tokens, Typography (Inter + tnum), Spacing, Shapes
└── BatteryAlertApp.kt  # Manual dependency injection (AppContainer)
```

---

## 4. Getting Started

### Prerequisites
- Android Studio Ladybug / Koala or newer
- JDK 17
- Android SDK 34 (Target SDK 33 / Min SDK 26)

### Build and Verification
```bash
# Clone the repository
git clone https://github.com/Kokach1/bhelth.git
cd bhelth

# Build debug APK
./gradlew assembleDebug

# Run all unit tests (29 tests across 5 test suites)
./gradlew testDebugUnitTest
```

---

## 5. Technical Documentation

- **[Company Requirements Traceability (FRD_TRACEABILITY.md)](docs/FRD_TRACEABILITY.md):** Full traceability matrix for company requirements FR-1.1 through FR-5.3 and review defect ledger.
- **[Specification (DAY2_SPEC.json)](docs/DAY2_SPEC.json):** Authoritative technical specification, MAVLink mappings, alert matrix, and architecture.
- **[Day 1 Archived Specification (SPEC.json)](docs/SPEC.json):** Day 1 foundation brief and domain contracts.
- **[Progress Tracker (PROGRESS.md)](docs/PROGRESS.md):** Build status, step checklists, test logs, and engineering handoff logs.
- **[Day 2 Transport Analysis (TRANSPORTS_DAY2.md)](docs/TRANSPORTS_DAY2.md):** Connection specifications for UDP MAVLink, internal serial (`/dev/ttySx`), and USB-Serial.
- **[Day 2 Architecture Plan (DAY2_PLAN.md)](docs/DAY2_PLAN.md):** Multi-phase roadmap and hardware verification boundary.

---

## 6. License
Internal proprietary project for **Exodia Drone Systems**. All rights reserved.
