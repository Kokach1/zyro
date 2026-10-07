# ⚡ Exodia Drone Battery Alert System (B-Helth)

[![Platform: Android](https://img.shields.io/badge/Platform-Android%2013%20%7C%20API%2033-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x%20%7C%20JVM%2017-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Custom%20Instrument%20Theme-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Architecture](https://img.shields.io/badge/Architecture-Clean%20%2F%20Unidirectional%20MVVM-00C853?style=for-the-badge)](docs/SPEC.json)
[![Target](https://img.shields.io/badge/Hardware-Skydroid%20G20%20(7''%201080p)-FF6D00?style=for-the-badge)](docs/SPEC.json)

> **High-Reliability Smart Battery Monitoring & Return-To-Launch (RTL) Alert System for Agricultural Spraying Drones.**  
> Built for the **Skydroid G20 Smart Controller** running Android 13 (7" landscape touch screen, 1920×1080).

---

## 🎯 Overview

Pilots flying heavy payload agricultural drones operate outdoors under direct sunlight, often wearing gloves and glancing at ground-station telemetry for only one second at a time.

**Exodia Battery Alert** provides an ultra-clear, high-contrast, instrument-cluster style dashboard that evaluates real-time drone telemetry, calculates dynamic return-to-launch (RTL) power requirements, identifies cell degradation/sag, and delivers multi-tier alerts before a catastrophic power loss can occur.

```
+-----------------------------------------------------------------------------------------------+
| [LIVE]  14:28                        (Home) 850 m   (RTL ETA) 2 min 50 s   [Imbalance Chip] ⚙ |
+------------------------------------+----------------------------------------------------------+
|                                    | CELLS (14S)                      Avg: 3.82V  Delta: 0.04V|
|                78%                 | +----+ +----+ +----+ +----+ +----+ +----+ +----+         |
|             REMAINING              | | C1 | | C2 | | C3 | | C4 | | C5 | | C6 | | C7 | ...     |
|         (Dynamic RTL Arc)          | +----+ +----+ +----+ +----+ +----+ +----+ +----+         |
|                                    +----------------------------------------------------------+
|   TIME LEFT        RETURN NEEDS    | PACK VOLTAGE           CURRENT            TEMPERATURE    |
|    16 min              28%         |    53.4 V              110.0 A               38.5 °C     |
+------------------------------------+----------------------------------------------------------+
```

---

## ✨ Key Features & Architecture

### 📊 Pure Instrument Dashboard
- **Single-Glance Hero Ring:** Custom 270° multi-zoned circular gauge displaying active state, dynamic RTL threshold markers, and warning bands.
- **Adaptive Cell Matrix:** Real-time individual cell telemetry supporting **6S, 12S, and 14S** architectures with outlier/imbalance highlights.
- **Zero Distractions:** High-contrast dark theme (`#0A0E13`) tailored for outdoor sunlight readability without clutter or decorative noise.

### 🧠 Core Analysis & Physics Engine
- **Voltage Sag Compensation ($V_{rest} = V_{meas} + I \times R_i$):** Corrects cell measurements under heavy throttle to determine actual chemical state-of-charge.
- **Dynamic RTL Estimation:** Continuously solves $Req\% = \left(\frac{\text{Distance}}{\text{Speed}}\right) \times \text{DischargeRate} + \text{Margin}\%$.
- **Cell Delta Fault Detection:** Instantly flags pack degradation when cell imbalance exceeds $80\,\text{mV}$.
- **Rapid Sag Watchdog:** Detects dangerous voltage drops ($\ge 0.15\,\text{V}$ in $2\,\text{s}$ at steady current).

### 🚨 Alert State Machine & Hysteresis
Multi-tier priority alert pipeline with debouncing and anti-chatter hysteresis:
1. **EMERGENCY (`#FF1F44`):** Full-screen flashing modal. Any cell $\le 3.40\,\text{V}$ or rapid sag. *Un-dismissable.*
2. **CRITICAL (`#FF4D4F`):** Full-screen modal. Pack below Dynamic RTL requirement or any cell $\le 3.50\,\text{V}$. *Un-dismissable.*
3. **WARNING (`#FF9F1C`):** Pulsing amber perimeter & actionable banner. Capacity $\le 20\%$ or cell $\le 3.65\,\text{V}$.
4. **NOTICE (`#F2D04B`):** Dismissible banner when capacity $\le 30\%$.
5. **CELL FAULT (`#FF7A2F`):** Dedicated delta warning chip + outlier boundary outline.

### 🕹️ High-Fidelity Flight Simulator
Built-in 5 Hz flight simulation pipeline with realistic non-linear Li-ion discharge curves, adjustable speed multipliers ($1\times, 5\times, 20\times$), battery profiles (DJI Agras T55/DB1580 class), and interactive edge-case scenarios (*Fast Discharge, Dynamic RTL Trigger, Cell Sag, Link Loss*).

---

## 🏗️ Technical Architecture & Package Layout

Strict separation of concerns where `core.*` has **zero Android or UI dependencies**, making logic 100% testable via standard JVM unit tests.

```
com.exodia.batteryalert
├── core/
│   ├── alert/          # AlertEngine state machine, AlertLevel, AlertOutput
│   ├── analysis/       # BatteryAnalyzer, RtlCalculator, ChemistryDetector, GeoMath
│   ├── config/         # AppConfig (Single source of truth for all thresholds)
│   ├── model/          # Pure data classes (BatteryFrame, PositionFrame, BatteryAnalysis)
│   ├── telemetry/      # TelemetryRepository, link watchdog & frame combiner
│   └── transport/      # TelemetryTransport interface, SimulatorTransport
├── ui/
│   ├── debug/          # SimulatorControlSheet composables
│   ├── monitor/        # BatteryMonitorScreen, BatteryMonitorViewModel, BatteryUiState
│   └── theme/          # Custom color tokens, Typography (Inter + tnum), Shapes
└── BatteryAlertApp.kt  # Manual dependency injection (AppContainer)
```

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug / Koala or newer
- JDK 17
- Android SDK 34 (Target SDK 33 / Min SDK 26)

### Build & Run
```bash
# Clone the repository
git clone https://github.com/Kokach1/bhelth.git
cd bhelth

# Build debug APK
./gradlew assembleDebug

# Run pure-logic unit test suite
./gradlew testDebugUnitTest
```

---

## 📚 Documentation & Roadmap

- **[Specification (`SPEC.json`)](docs/SPEC.json):** Full technical contract, domain formulas, and design system requirements.
- **[Progress Tracker (`PROGRESS.md`)](docs/PROGRESS.md):** Current status, verification logs, and engineering notes.
- **[Day 2 Transport Stubs (`TRANSPORTS_DAY2.md`)](docs/TRANSPORTS_DAY2.md):** Integration routes for UDP MAVLink, internal serial (`/dev/ttyS1`), and USB-Serial.
- **[Day 2 Plan (`DAY2_PLAN.md`)](docs/DAY2_PLAN.md):** Foreground services, TTS announcements, siren tones, and blackbox logging roadmap.

---

## 📄 License
Internal proprietary project for **Exodia Drone Systems**. All rights reserved.
