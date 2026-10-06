# Battery Alert

Single-screen Android battery monitor for an agricultural-drone controller. Day 1 uses an on-device fake flight only; no hardware link is built or tested.

Open the project in Android Studio or run `./gradlew assembleDebug`. The app starts in simulator mode; use the top-right control to choose a scenario, speed, or battery profile.

| Area | Purpose |
|---|---|
| `core/model`, `core/config` | shared data and configurable thresholds |
| `core/analysis`, `core/alert` | pure battery, RTL, and alert logic |
| `core/transport`, `core/telemetry` | simulator and telemetry boundary |
| `ui/monitor`, `ui/debug` | one dashboard and simulator sheet |

Day 2 plugs a real adapter into `TelemetryTransport`; analysis, alerts, and UI do not change. See `docs/TRANSPORTS_DAY2.md` for the currently unverified connection paths.
