# Day 2 Transport Paths [SUPERSEDED - HISTORICAL RECORD ONLY]

> **NOTICE:** This document is archived for historical reference. The operative transport architecture and configurations are defined in [`docs/CURRENT_FIX_SPEC.json`](CURRENT_FIX_SPEC.json) (`2026-10-07-v2-in-place-alerts`). Concrete implementations exist in `platform/transport/AndroidUsbSerialStream.kt` and `platform/transport/AndroidInternalSerialStream.kt`.

- UDP to the Skydroid link: test `192.168.144.101` ports 14550 and 14551.
- Internal serial: test `/dev/ttyS1` at 921600; normal Android applications may be blocked.
- USB serial adapter: add `usb-serial-for-android` only after hardware confirmation.

The working G20 path and whether the aircraft exposes MAVLink are unverified. Each adapter must emit unit-converted `TelemetryFrame` values through `TelemetryTransport`.
