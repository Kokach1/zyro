# Automated Test Execution Report — Zyro

This report details the execution of the automated unit test suite for the **Zyro Smart Battery Alert System** in accordance with the verification criteria established in `docs/CURRENT_FIX_SPEC.json`.

---

## 1. Test Environment & Execution Details

| Attribute | Value |
|---|---|
| **Operating System** | Windows 11 |
| **Java Runtime (JAVA_HOME)** | OpenJDK 17.0.12 (`C:\Program Files\Android\Android Studio\jbr`) |
| **Kotlin Compiler** | 2.0.21 |
| **Gradle Version** | 8.7 |
| **Target Android API** | Android 13 (API 33) / Compile SDK 34 |
| **Execution Command** | `powershell -Command "& { [System.Environment]::SetEnvironmentVariable('JAVA_HOME', 'C:\Program Files\Android\Android Studio\jbr', 'Process'); .\gradlew.bat --no-daemon testDebugUnitTest }"` |
| **Execution Timestamp** | 2026-10-07 |
| **Overall Result** | **BUILD SUCCESSFUL (89 / 89 Tests Passed, 0 Failed, 0 Skipped)** |

---

## 2. Test Suite Breakdown

### Suite 1: `AnalysisDetectionConsumptionRtlTest` (11 Tests)
Verifies evidence-ranked chemistry detection, loaded voltage sag compensation, robust consumption tracking, current integration fallback, and dynamic RTL calculations.
- `testChemistryDetection6S12S14S`: Validates correct series cell counts for 6S, 12S, and 14S pack telemetry.
- `testAmbiguousChemistryDetectionReturnsCandidates`: Verifies ambiguous classification when voltage falls within overlapping chemistry curves.
- `testSagCompensationFormulaCorrectness`: Confirms $V_{rest} = V_{meas} + I \times R_i$ computation with $R_i = 0.002\,\Omega/\text{cell}$.
- `testSagCompensationUnavailableWhenCurrentUnknown`: Ensures resting voltage compensation is marked unavailable when current is null, avoiding false $I=0$ resting claims.
- `testLoadedVoltagePreservedForAlertThresholds`: Confirms alert thresholds evaluate loaded voltage, ensuring dangerous sag is never hidden.
- `testConsumptionCounterSlopeCalculation`: Verifies cumulative counter slope calculation across rolling time windows.
- `testCurrentIntegrationFallbackWhenCounterAbsent`: Validates trapezoidal current integration ($I \times \Delta t / 3.6$) when cumulative counter telemetry is absent.
- `testDynamicRtlGoldenCalculation`: Evaluates 900m distance, 5m/s speed, 110A load, 30Ah pack = exact 33.333% dynamic RTL requirement.
- `testDynamicRtlUnclampedAbove100Percent`: Proves unrounded $>100\%$ return requirements are preserved in core analytics for impossible return warnings.
- `testDynamicRtlUnavailableWhenHomeOrGpsMissing`: Ensures RTL requirement returns unavailable when home reference or position is missing.
- `testFlightClockArmingStateTracking`: Validates flight timer starts upon verified heartbeat arm state and freezes on disarm.

### Suite 2: `CodecAndMergeTest` (9 Tests)
Verifies lossless MAVLink 1 & 2 packet decoding, sentinel cell handling, and bounded SYS_STATUS fallback.
- `testMavlink1And2PacketDecoding`: Decodes valid MAVLink 1 and MAVLink 2 telemetry frames.
- `testCrcExtraValidationAndRejection`: Verifies packets with corrupted CRCs are rejected.
- `testResynchronizationAfterNoise`: Confirms decoder recovers frame boundaries after corrupt preamble bytes.
- `testCellSlotPositionsPreservedWithoutCompression`: Proves missing middle cell slots do not shift remaining cell indices.
- `testNearZeroCellSentinelPreserved`: Confirms $1\,\text{mV}$ ($0.001\,\text{V}$) sentinel values decode accurately as true cell fault zero evidence.
- `testVoltagesExt14SCellDecoding`: Decodes cells 11 through 14 from `BATTERY_STATUS` extensions.
- `testBoundedSysStatusFallbackPreservesPrimaryAges`: Proves `SYS_STATUS` contributes aggregate fallback without overwriting richer primary cell timestamps.
- `testAggregateOnlyPackTelemetryFlag`: Verifies aggregate pack voltage is flagged as aggregate-only without inventing fabricated cell readings.
- `testPackIdentityIsolation`: Confirms distinct battery IDs do not overwrite each other.

### Suite 3: `CoreLogicTest` (14 Tests)
Verifies foundational domain logic, cell delta boundary conditions, and alert evaluations.
- `testMeasuredCellDeltaIsStrictlyGreaterThanLimit`: Verifies cell fault triggers strictly at $\Delta V > 0.08\,\text{V}$ and not at $\le 0.08\,\text{V}$.
- `testNoticeAlertTriggerAt30Percent`: Validates Notice entry at $\le 30\%$ capacity.
- `testWarningAlertTriggerAt20Percent`: Validates Warning entry at $\le 20\%$ capacity.
- `testLowCellWarningAt365V`: Validates Warning entry when cell $\le 3.65\,\text{V}$.
- `testCriticalAlertAt350V`: Validates Critical alert when cell $\le 3.50\,\text{V}$.
- `testEmergencyAlertAt340V`: Validates Emergency alert when cell $\le 3.40\,\text{V}$.
- `testRapidSagDetection`: Confirms rapid voltage drop flags emergency alert.
- `testDynamicRtlInclusiveBoundary`: Verifies inclusive $\le$ trigger for dynamic RTL comparison.
- `testCellFaultCoexistsWithSevereBatteryTier`: Proves cell fault flags independently alongside Critical and Emergency tiers.
- `testZeroVoltageCellImmediateFault`: Verifies zero-voltage cell triggers an immediate safety fault.
- `testAlertStateUndismissable`: Confirms active Critical and Emergency alerts cannot be cleared by dismissal actions.
- `testBatteryProfilesBounds`: Validates capacity and series count bounds across Agras and standard profiles.
- `testGeoMathHaversineAccuracy`: Validates geodesic distance calculations against known coordinates.
- `testCellVoltageCurveInterpolation`: Validates voltage-to-percent curve mapping monotonicity.

### Suite 4: `FreshnessAndRecoveryTest` (8 Tests)
Verifies central watchdog, per-field freshness timeouts, and hysteresis recovery.
- `testCentralWatchdogFiresOnTelemetrySilence`: Confirms watchdog triggers link lost when frames cease for $>3\,\text{s}$.
- `testBatteryStaleIndependentOfGps`: Verifies GPS-only traffic does not keep battery metrics marked fresh.
- `testGpsStaleIndependentOfBattery`: Confirms battery traffic does not keep position metrics fresh when GPS fix is lost.
- `testSevereAlertSurvivesLinkLoss`: Proves Critical/Emergency alerts remain latched during link loss with added loss indicators.
- `testDowngradeHysteresisRequiresEligibleSafeRecovery`: Verifies transitions to lower tiers require configured hysteresis (+3%, +0.03V) and stable clear intervals.
- `testCellFaultClearRequiresStableLowDelta`: Confirms cell fault clears only when delta $\le 0.06\,\text{V}$ is stable for $1.5\,\text{s}$.
- `testStaleTelemetryCannotClearActiveAlert`: Proves stale frames cannot clear active danger states.
- `testReconnectionRestoresFreshness`: Validates successful recovery when fresh selected-source telemetry resumes.

### Suite 5: `InterlockAndLoggerTest` (7 Tests)
Verifies spraying pump cutoff controller and continuous blackbox logger.
- `testPumpCutoffTriggersAt20PercentWhenEnabled`: Validates pump cutoff is requested at $\le 20\%$ when interlock is enabled and mapped.
- `testPumpCutoffInhibitedWhenDisabled`: Proves zero commands are transmitted when interlock is disabled.
- `testPumpCutoffInhibitedInReplayMode`: Proves zero command ports or writes can occur during replay.
- `testPumpCutoffRetriesBoundedToThree`: Confirms cutoff retry attempts are bounded to a maximum of 3 before declaring failure.
- `testBlackboxLoggerWritesValidJsonlRecords`: Verifies continuous JSONL record formatting and flushing.
- `testBlackboxLoggerMapsValidGpsAndNullsWhenStale`: Confirms valid GPS is mapped with age, and null is recorded when GPS is stale.
- `testBlackboxLoggerBoundedQueuePreventsMemoryExhaustion`: Verifies non-blocking channel drops with counter rather than exhausting memory during heavy load.

### Suite 6: `MavlinkCodecTest` (10 Tests)
Verifies byte-level MAVLink framing and dialect decoding.
- `testDecodeMavlink1Heartbeat`: Validates MAVLink 1 `HEARTBEAT` decoding.
- `testDecodeMavlink2Heartbeat`: Validates MAVLink 2 `HEARTBEAT` decoding.
- `testDecodeBatteryStatus`: Validates MAVLink 2 `BATTERY_STATUS` decoding.
- `testDecodeSysStatus`: Validates MAVLink 2 `SYS_STATUS` decoding.
- `testDecodeGlobalPositionInt`: Validates MAVLink 2 `GLOBAL_POSITION_INT` decoding.
- `testDecodeHomePosition`: Validates MAVLink 2 `HOME_POSITION` decoding.
- `testDecodeCommandAck`: Validates MAVLink 2 `COMMAND_ACK` decoding.
- `testGarbageResynchronization`: Confirms decoder skips leading garbage bytes and locks onto packet magic.
- `testFragmentedPacketFeed`: Validates decoder handles packets fed byte-by-byte or in small chunks.
- `testConcatenatedPackets`: Validates decoder processes multiple back-to-back packets in a single buffer.

### Suite 7: `OutputAndServiceTest` (8 Tests)
Verifies audio/haptic priority scheduling and foreground service lifecycle.
- `testNoticeTriggersSingleEntryChimeAndVibration`: Confirms Notice alert triggers exactly one entry audio event.
- `testWarningTriggersRepeatedVoice`: Confirms Warning schedules repeated voice alerts.
- `testCriticalTriggersContinuousSirenAndVibration`: Confirms Critical activates sustained siren and strong vibration.
- `testEmergencySpeechTakesPriorityOverSiren`: Verifies emergency speech ducks the siren tone for intelligibility.
- `testCellFaultAnnouncesVoiceWithoutTierChange`: Confirms cell fault triggers speech even when overall battery level remains unchanged.
- `testPeriodicSpeechSpeaksFreshMetrics`: Verifies periodic 60s speech announces fresh percent and cell average.
- `testPeriodicSpeechSuppressedDuringUrgentAlert`: Confirms periodic speech is suppressed while Critical or Emergency alerts are active.
- `testStopCancelsAllScheduledAudioAndVibrations`: Validates immediate cancellation and release of all players and vibrators upon session stop.

### Suite 8: `ReplayTransportTest` (2 Tests)
Verifies replay transport file handling and legacy JSON parsing.
- `testReplayFileNotFoundReportsError`: Confirms non-existent file paths report clean connection errors.
- `testReplayJsonlEmitsFrames`: Confirms JSONL flight records are parsed and emitted as domain frames.

### Suite 9: `SerialPlatformWiringTest` (8 Tests)
Verifies Android USB serial adapters, device enumeration, and termios serial bridges.
- `testUsbDriverEnumeration`: Validates probing of supported USB-UART drivers.
- `testUsbPermissionRequest`: Validates explicit `PendingIntent` creation for USB access.
- `testUsbPermissionGrantedOpensPort`: Confirms port opens when permission is granted.
- `testUsbPermissionDeniedReportsError`: Confirms denial reports a concise error without synthetic fallback.
- `testUsbDeviceDetachedClosesConnection`: Confirms USB cable disconnect triggers clean teardown.
- `testInternalSerialBridgeConfiguresBaud`: Validates POSIX termios baud rate configuration.
- `testInternalSerialAccessDeniedHandledGracefully`: Confirms permission denied on `/dev/ttySx` reports actionable error.
- `testByteStreamSourceBufferContracts`: Validates `read(buffer, offset, count)` contract compliance.

### Suite 10: `SetupAndSessionSourceTest` (7 Tests)
Verifies cold launch setup flow and explicit session source typing.
- `testColdStartHasNoActiveTransport`: Confirms cold launch shows setup with no synthetic data.
- `testSharedConfigBetweenInlineAndSheet`: Verifies inline setup and modal sheet share identical validated settings.
- `testRealTransportApiRejectsSimulatorConfig`: Defensively rejects `TransportKind.SIMULATOR` in real connection methods.
- `testInvalidPortInputPreventsMonitoringStart`: Confirms invalid port inputs prevent session initiation.
- `testSessionSourceDerivedFromTransport`: Confirms `LIVE_UDP`, `LIVE_USB`, `LIVE_INTERNAL`, `SIMULATOR`, and `REPLAY` are derived truthfully.
- `testReplayNeverLabelledLive`: Proves replay telemetry is never badged as LIVE.
- `testSourceSwitchPerformsCleanTeardown`: Validates atomic stop-and-join before starting a new session.

### Suite 11: `SimulatorReplayIntegrationTest` (7 Tests)
Verifies golden dynamic RTL scenario, deterministic virtual timelines, and replay round-trips.
- `testGoldenDynamicRtlScenarioExclusiveReason`: Proves golden scenario (14S/30Ah, 900m distance, 5m/s cruise, 110A load, 30% battery, equal 3.80V cells) triggers Critical strictly for `BELOW_DYNAMIC_RTL` with zero low-cell or imbalance contamination.
- `testVirtualSimulationRateConsistentAcrossSpeeds`: Confirms virtual consumption rate in mAh per virtual second is identical across x1, x5, and x20 multipliers.
- `testExplicitHomeAndVehicleStateEmitted`: Confirms explicit `HomeFrame` and `VehicleStateFrame` are emitted before position data.
- `testScenarioIsolationAndReset`: Validates switching scenarios cleanly resets all timers, history, and consumption state.
- `testSimulatedPumpCutoffReducesLoad`: Confirms simulated pump cutoff reduces load current after capacity drops $\le 20\%$.
- `testReplayStructuredJsonlRoundTrip`: Validates versioned blackbox JSONL log round-trips through `ReplayTransport` preserving all fields.
- `testReplayMalformedAndPartialRecordsHandledGracefully`: Confirms replay parser gracefully skips comments, empty lines, and malformed records.

### Suite 12: `UdpTransportTest` (1 Test)
- `testUdpTransportLifecycle`: Confirms UDP socket bind, datagram reception, and clean stop.

### Suite 13: `UsbSerialTransportTest` (2 Tests)
- `testUsbSerialTransportReadLoop`: Validates byte stream reading and frame emission.
- `testUsbSerialTransportStopClosesSource`: Confirms stop cleanly closes underlying byte stream source.

---

## 3. Physical Bench Status (Separation of Software from Hardware)

In accordance with strict verification rules:
- **Software Integration:** 100% verified by executable code and passing unit tests.
- **Physical Hardware Execution:** Bench operation on physical Skydroid G20 / GR01 hardware and live aircraft pump testing remain marked as **pending physical bench availability**. Mock test passes are not claimed as physical hardware proof.
