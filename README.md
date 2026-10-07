# Exodia Drone Battery Alert System (B-Helth)

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

**Exodia Battery Alert** delivers an instrument-cluster style monitoring interface that parses drone telemetry, continuously computes dynamic return-to-launch (RTL) battery requirements, monitors cell balance and voltage sag, and issues multi-tier alerts prior to critical power exhaustion.

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

### Flight Telemetry Simulator
Integrated 5 Hz telemetry simulation pipeline with non-linear Li-ion discharge curves, configurable speed multipliers ($1\times, 5\times, 20\times$), battery profiles (e.g., DJI Agras T55 / DB1580 class), and selectable test scenarios (*Fast Discharge, Dynamic RTL Trigger, Cell Sag, Link Loss*).

---

## 3. Package Layout & Boundaries

The codebase enforces strict separation of concerns where `core.*` packages contain **zero Android or UI dependencies**, enabling complete JVM unit testability.

```
com.exodia.batteryalert
├── core/
│   ├── alert/          # AlertEngine state machine, AlertLevel, AlertOutput
│   ├── analysis/       # BatteryAnalyzer, RtlCalculator, ChemistryDetector, GeoMath
│   ├── config/         # AppConfig (Single source of truth for all thresholds)
│   ├── model/          # Pure data classes (BatteryFrame, PositionFrame, BatteryAnalysis)
│   ├── telemetry/      # TelemetryRepository, link watchdog, snapshot aggregation
│   └── transport/      # TelemetryTransport interface, SimulatorTransport
├── ui/
│   ├── debug/          # SimulatorControlSheet composables
│   ├── monitor/        # BatteryMonitorScreen, BatteryMonitorViewModel, BatteryUiState
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

# Run unit tests
./gradlew testDebugUnitTest
```

---

## 5. Technical Documentation

- **[Specification (SPEC.json)](docs/SPEC.json):** Authoritative technical requirements, domain formulas, and design system contracts.
- **[Progress Tracker (PROGRESS.md)](docs/PROGRESS.md):** Current status, build logs, and engineering handoff history.
- **[Day 2 Transport Specifications (TRANSPORTS_DAY2.md)](docs/TRANSPORTS_DAY2.md):** Target connection specifications for UDP MAVLink, internal serial (`/dev/ttyS1`), and USB-Serial.
- **[Day 2 Architecture Plan (DAY2_PLAN.md)](docs/DAY2_PLAN.md):** Integration roadmap for foreground services, text-to-speech, siren audio, and flight logging.

---

## 6. License
Internal proprietary project for **Exodia Drone Systems**. All rights reserved.
