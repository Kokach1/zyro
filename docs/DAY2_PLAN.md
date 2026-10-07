# Day 2 Plan [SUPERSEDED - HISTORICAL RECORD ONLY]

> **NOTICE:** This document is archived for historical reference. The operative specification is defined in [`docs/CURRENT_FIX_SPEC.json`](CURRENT_FIX_SPEC.json) (`2026-10-07-v2-in-place-alerts`). Do not use this plan for implementation authority.

Confirm the telemetry route before adding any MAVLink or USB dependency. Add a real `TelemetryTransport`, foreground monitoring, voice/tone/vibration outputs, blackbox logging and settings only after confirmation. The current model supports `batteryId`; decide whether future dual packs display as a combined parallel capacity or separate packs. Replace the Agras-class approximation with Exodia's exact battery profile and validate all voltage thresholds.
