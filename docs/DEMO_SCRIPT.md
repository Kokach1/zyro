# Operator Demo & Evaluation Script — Zyro

This script provides step-by-step operating procedures for evaluating the **Zyro Smart Battery Alert System** across all operational scenarios, alert tiers, and transport modes.

---

## 1. Startup & Connection Setup

1. **Cold Launch App:**
   - Launch the Zyro application on the device or emulator.
   - **Expected State:** The app opens directly to the **Connection Setup** interface. No synthetic data, live metrics, or simulated telemetry are displayed. Active transport is disconnected.
2. **Inspect Setup Controls:**
   - Verify available transport tabs: **Live UDP**, **Live USB**, **Live Serial**, and **Flight Replay**.
   - Verify the **Simulator** button is cleanly centered below the primary connection controls with no demo captions.
3. **Start Simulation:**
   - Tap the centered **Simulator** button.
   - **Expected State:** The dashboard appears with the `SIMULATOR` source badge. The vehicle arming status indicates armed, and initial telemetry frames populate with healthy default metrics.

---

## 2. Demonstrating Alert Tiers & Matrix Triggers

Tap the bottom drawer handle or access the debug menu to open the **Simulator Control Sheet**.

### Scenario A: Normal Flight
- **Action:** Select `Normal flight`.
- **Expected Visuals:**
  - Standard dark instrument theme (`#0A0E13`).
  - Remaining capacity starts at 100% and discharges gradually at ~90A.
  - All 14 cell tiles display balanced voltages (> 3.85V).
  - RTL ring arc displays the safe dynamic energy margin.
- **Audio/Haptics:** Silence.

### Scenario B: Notice Alert (Remaining $\le 30\%$)
- **Action:** Select `30 percent`.
- **Trigger:** Remaining capacity reaches $30\%$ (with safe distance to home, e.g. 100m).
- **Expected Visuals:**
  - Compact yellow top banner: `Plan to return soon`.
  - Hero ring transitions to yellow zone.
  - Dashboard telemetry remains fully visible.
- **Audio/Haptics:** Single entry chime and short vibration upon entry. No repetitive chime storms.

### Scenario C: Warning Alert (Remaining $\le 20\%$ OR Cell $\le 3.65\,\text{V}$)
- **Action:** Select `20 percent`.
- **Trigger:** Remaining capacity drops to $20\%$ or loaded cell voltage drops to $\le 3.65\,\text{V}$.
- **Expected Visuals:**
  - In-place amber accent and perimeter border.
  - Action prompt: `Warning, low battery`.
  - Individual cell tiles near $3.62\,\text{V}$ highlight in warning amber.
- **Audio/Haptics:** Periodic repeated voice alert: *"Warning, low battery"*.

### Scenario D: Dynamic RTL Critical Alert (Golden Requirement FR-3.1 / FR-3.2)
- **Action:** Select `Dynamic RTL`.
- **Parameters:**
  - Profile: 14S, 30,000 mAh capacity.
  - Distance to Home: 900 meters.
  - Cruising Speed: 5.0 m/s (180 seconds return time).
  - Discharge Load: 110.0 A (Rate: 1,833.33 mAh/min).
  - Cell Voltages: Exactly equal measured 3.80V across all 14 cells.
  - Dynamic RTL Requirement: $180 \times \left(\frac{1833.33}{60 \times 30000} \times 100\right) + 15\% = 33.333\%$.
  - Remaining Battery: $30.0\%$.
- **Trigger:** Remaining capacity ($30\%$) is less than or equal to required RTL energy ($33.333\%$).
- **Expected Visuals:**
  - **In-place red dashboard theme** applied across background and surfaces.
  - Centered hero readout displays `30%` with bold red `RETURN NOW` action banner.
  - Live cell voltages (3.80V), current (110A), home distance (900m), and return ETA (2m 50s) remain fully visible.
  - Alert reason is exclusively `BELOW_DYNAMIC_RTL` (zero low-cell or imbalance contamination).
- **Audio/Haptics:** Continuous high-pitch siren tone and strong vibration pattern.

### Scenario E: Low Cell Voltage Critical Alert (Cell $\le 3.50\,\text{V}$)
- **Action:** Select `Low cell`.
- **Trigger:** Individual cell voltage drops to $3.48\,\text{V}$ ($\le 3.50\,\text{V}$) while overall capacity is at 50%.
- **Expected Visuals:**
  - In-place red dashboard theme with `RETURN NOW` warning label.
  - Affected cell tile (C1) displays $3.48\,\text{V}$ in distinct danger highlight.
- **Audio/Haptics:** Siren tone and urgent alert dispatch.

### Scenario F: Emergency Alert (Cell $\le 3.40\,\text{V}$ OR Rapid Sag)
- **Action:** Select `Emergency`.
- **Trigger:** Minimum cell voltage drops to $3.38\,\text{V}$ ($\le 3.40\,\text{V}$).
- **Expected Visuals:**
  - Static in-place red dashboard theme.
  - Prominent persistent action banner: `LAND NOW`.
  - Telemetry readouts remain visible for pilot navigation.
- **Audio/Haptics:** Repeated high-priority voice command: *"Land immediately"*. Siren ducks to ensure voice intelligibility.

### Scenario G: Independent Cell Fault Imbalance (FR-2.2)
- **Action:** Select `Cell fault`.
- **Trigger:** Measured delta $\Delta V = V_{max} - V_{min} = 3.82\,\text{V} - 3.70\,\text{V} = 0.12\,\text{V} > 0.08\,\text{V}$.
- **Expected Visuals:**
  - Independent orange cell fault badge displays `Delta 0.12V (C1)`.
  - Coexists cleanly with active battery severity tier without covering the dashboard.
- **Audio/Haptics:** Spoken prompt: *"Cell voltage imbalance - land & inspect battery"*.

---

## 3. Link Loss & Telemetry Freshness (FR-1.2 / Watchdog)

1. **Trigger Link Loss:**
   - In the Simulator Control Sheet, select `Link lost`.
2. **Expected Behavior:**
   - After a brief duration, the central watchdog detects link inactivity ($> 3\,\text{s}$).
   - The connection badge transitions to `LINK LOST`.
   - Telemetry values dim with stale indicators (`stale: true`).
   - If an active Critical or Emergency alert was latched, the danger theme persists with an added `TELEMETRY LOST` status indicator rather than clearing to safe.

---

## 4. Blackbox Logging & SAF Export (FR-5.3)

1. **Continuous Recording:**
   - While monitoring is active, `TelemetryLogger` streams timestamped JSONL flight records to internal app-private storage.
   - Each record captures UTC time, monotonic sample time, source mode, raw cell voltages, pack voltage, current, consumed mAh, GPS coordinates, position age, and alert states.
2. **Export Log File:**
   - Navigate to **Connection Setup** -> **Export Blackbox Logs**.
   - Use the Android Storage Access Framework (SAF) document picker to save the session `.jsonl` or `.csv` file.

---

## 5. Flight Replay (FIX-21)

1. **Open Replay Source:**
   - In Connection Setup, select **Flight Replay**.
   - Choose a recorded `.jsonl` blackbox log or `.tlog` flight file.
2. **Playback Execution:**
   - Tap **Start Replay**.
   - **Expected State:** The dashboard indicates `REPLAY` as the active session source.
   - Playback paces frames according to recorded timestamp intervals.
   - Hardware command ports are strictly inhibited (zero actuator writes).
