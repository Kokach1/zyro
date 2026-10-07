# Known Limitations & Hardware Assumptions — Zyro

This document provides a factual record of known system constraints, hardware dependencies pending physical confirmation, and intentional design deviations in accordance with the authoritative specification `docs/CURRENT_FIX_SPEC.json`.

---

## 1. Physical Hardware & Vendor Integration Gaps

The following hardware parameters could not be conclusively determined from static documentation and require on-device bench confirmation with physical aircraft and controller hardware:

| Parameter | Current Status | Software Handling | Operational Impact |
|---|---|---|---|
| **Skydroid G20 Internal Serial Node** | Unconfirmed vendor node path (e.g., `/dev/ttyS1` vs `/dev/ttyS3`) | Fully configurable input field in Setup. POSIX termios bridge implemented in `AndroidInternalSerialStream`. | If node path is incorrect or blocked by Android SELinux policy, connection fails cleanly with `serial access denied`. Fallback to UDP is recommended. |
| **G20 Internal Serial Baud Rate** | Unconfirmed (commonly 921600 or 115200) | Fully selectable in Setup (`57600`, `115200`, `921600`). | Must match flight controller serial port settings. |
| **USB VID/PID for G20** | Unconfirmed hardware-specific VID/PID | Probes all standard USB-UART drivers via `usb-serial-for-android`. | Compatible with standard external FTDI, CP210x, CH34x, and CDC-ACM adapters. |
| **Spraying Pump Actuator Channel** | Unconfirmed Flight Controller servo/relay channel | Guarded behind `PumpInterlockController` opt-in gate. Default state: Disabled. | Prevents sending unintended commands to unknown channels (e.g. flight controls or arming switches). Commands are inhibited until mapping is confirmed. |
| **G20 Physical Haptic Transducer** | Controller haptic hardware capabilities unverified | Version-adaptive `Vibrator` and `VibratorManager` implementation with amplitude fallbacks. | If controller lacks a vibration motor, haptic calls fail gracefully without interrupting visual or audible siren alerts. |

---

## 2. Intentional User Visual Deviations

In accordance with user design instructions in `docs/CURRENT_FIX_SPEC.json`, the presentation of Critical and Emergency alerts intentionally departs from the company Functional Requirements Document (FRD):

| Alert Tier | Company FRD Literal Specification | Implemented Presentation (Authoritative User Override) | Rationale |
|---|---|---|---|
| **CRITICAL** | Full-screen red modal dialog covering the dashboard. | **In-place red dashboard theme** with prominent `RETURN NOW` action label at the hero percentage. | Full-screen modal blocks live metrics (cells, distance, time left) at the moment the operator needs them most. In-place theme preserves continuous metric visibility. |
| **EMERGENCY** | Flashing / blinking red full-screen overlay. | **In-place red dashboard theme** with persistent `LAND NOW` action banner. Continuous voice commands. | Rapid flashing induces eye fatigue under direct sunlight and impairs reading cell voltage data during forced landings. |

> **Traceability Note:** This deviation is documented in `docs/FRD_TRACEABILITY.md` and `docs/PROGRESS.md`. Literal FRD fullscreen/flashing composables have been retired.

---

## 3. Analytical & Physics Model Assumptions

1. **Chemistry Detection Boundary:**
   - Chemistry voltage curves (Li-Ion, LiPo, LiHV) overlap significantly depending on state of charge and electrical load. For example, a resting cell at $3.75\,\text{V}$ could be a mid-discharge LiPo or a high-charge Li-Ion cell.
   - `ChemistryDetector` classifies configuration as `AMBIGUOUS` when baseline readings overlap, requiring pilot confirmation in setup rather than asserting false certainty.
2. **Dynamic RTL Cruising Speed:**
   - In accordance with safety rules, hovering ground speed ($0\,\text{m/s}$) cannot substitute for cruising return speed in RTL energy calculations.
   - Cruising speed defaults to $5.0\,\text{m/s}$ (configurable in settings) and requires validation against actual drone operating parameters.
3. **Internal Resistance ($R_i$) per Cell:**
   - Resting voltage sag compensation $V_{rest} = V_{meas} + I \times R_i$ requires validated per-cell internal resistance. Default value is $0.002\,\Omega/\text{cell}$ ($2\,\text{m}\Omega$).
   - If current telemetry is absent or invalid, resting voltage compensation is marked unavailable; loaded cell voltage is always used for safety threshold evaluation.

---

## 4. UI & Typography Considerations

1. **Font Packaging:**
   - The system utilizes `FontFamily.SansSerif` with tabular figures (`FontFeatureSettings = "tnum"`) to ensure zero-jitter layout stability for high-frequency numerical updates.
   - Dedicated Inter font `.ttf` files are not bundled in repository assets to keep binary size minimal.
2. **Strict Professional UI:**
   - Zero emojis or decorative Unicode symbols are permitted in app UI, notifications, errors, or simulator controls.
   - All connection status text adheres to standard operational terminology without `NOT_TESTED` suffixes.
