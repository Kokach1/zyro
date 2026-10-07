# Day 2 FRD traceability

| Requirement | Software status | Physical status | Evidence / limitation |
|---|---|---|---|
| FR-1.1 Serial telemetry | BLOCKED | NOT_TESTED | No confirmed G20 path or USB mapping. |
| FR-1.2 MAVLink decoding | BLOCKED | NOT_TESTED | Simulator emits normalized frames only. |
| FR-1.3 Pack detection | PARTIAL | NOT_TESTED | Existing cell-count detector; chemistry remains provisional. |
| FR-2.1 Sag compensation | PASS | NOT_TESTED | `BatteryAnalyzer` unit test. |
| FR-2.2 Cell delta | PASS | NOT_TESTED | `BatteryAnalyzer` unit test. |
| FR-2.3 Consumption estimate | PARTIAL | NOT_TESTED | Existing history/rate test. |
| FR-3.1 Dynamic RTL | PASS | NOT_TESTED | `RtlCalculator` 900 m golden fixture. |
| FR-3.2 Critical RTL | PASS | NOT_TESTED | Inclusive boundary calculation; UI alert remains Day 1 implementation. |
| FR-5.1 Pump interlock | BLOCKED | NOT_TESTED | No confirmed actuator mapping; no commands are sent. |
| FR-5.2 Periodic TTS | BLOCKED | NOT_TESTED | Day 2 output work not started. |
| FR-5.3 GPS-mapped logging | BLOCKED | NOT_TESTED | Day 2 logging work not started. |

The simulator is a demonstration source, not hardware evidence. All profile values and controller protocol assumptions remain unverified.
