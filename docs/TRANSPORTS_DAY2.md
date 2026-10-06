# Day 2 transport paths

Only `SimulatorTransport` exists in Day 1. Real telemetry remains unverified.

- UDP to the Skydroid link: test `192.168.144.101` ports 14550 and 14551.
- Internal serial: test `/dev/ttyS1` at 921600; normal Android applications may be blocked.
- USB serial adapter: add `usb-serial-for-android` only after hardware confirmation.

The working G20 path and whether the aircraft exposes MAVLink are unverified. Each adapter must emit unit-converted `TelemetryFrame` values through `TelemetryTransport`.
