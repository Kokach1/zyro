package com.exodia.batteryalert

import com.exodia.batteryalert.core.alert.AlertEngine
import com.exodia.batteryalert.core.alert.AlertOutput
import com.exodia.batteryalert.core.config.AppConfig
import com.exodia.batteryalert.core.model.*
import com.exodia.batteryalert.core.telemetry.TelemetryRepository
import com.exodia.batteryalert.core.transport.TelemetryTransport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Test

class FreshnessAndRecoveryTest {

    private class FakeTransport : TelemetryTransport {
        override val id: String = "fake"
        override val displayName: String = "Fake"
        val _conn = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        override val connectionState: StateFlow<ConnectionState> = _conn
        val _frames = MutableSharedFlow<TelemetryFrame>(replay = 10)
        override val frames: SharedFlow<TelemetryFrame> = _frames
        override val sessionSource: SessionSource = SessionSource.LIVE_UDP
        override suspend fun start() { _conn.value = ConnectionState.Connected }
        override suspend fun stop() { _conn.value = ConnectionState.Disconnected }
    }

    @Test
    fun noTrafficWatchdog() {
        var mono = 1_000L
        var wall = 10_000L
        val transport = FakeTransport()
        val repo = TelemetryRepository(
            transport = transport,
            targetSystemId = 1,
            clockMonotonicMs = { mono },
            clockWallMs = { wall }
        )

        // Initial valid battery packet
        val b = BatteryFrame(
            timestampMs = wall,
            cellVoltagesV = List(14) { 3.85f },
            packVoltageV = 53.9f,
            currentA = 10f,
            remainingPercent = 85,
            systemId = 1
        )
        repo.onFrame(TelemetryFrame.Battery(b), nowMonotonic = mono)

        assertEquals(ConnectionState.Connected, repo.state.value.connection)
        assertFalse(repo.state.value.stale)

        // Advance monotonic time by 4,000ms (> 3,000ms timeout) with NO packets
        mono += 4_000L
        wall += 4_000L
        repo.checkWatchdog(mono)

        assertTrue(repo.state.value.connection is ConnectionState.LinkLost)
        assertTrue(repo.state.value.stale)
        assertTrue(repo.state.value.batteryStale)
    }

    @Test
    fun gpsOnlyAndHeartbeatOnlyBatteryExpiry() {
        var mono = 1_000L
        val transport = FakeTransport()
        val repo = TelemetryRepository(
            transport = transport,
            targetSystemId = 1,
            clockMonotonicMs = { mono }
        )

        val b = BatteryFrame(1_000L, List(6) { 3.85f }, 23.1f, 5f, remainingPercent = 80, systemId = 1)
        repo.onFrame(TelemetryFrame.Battery(b), nowMonotonic = mono)

        // Advance 4 seconds, feeding only GPS and Heartbeat
        mono += 4_000L
        val pos = PositionFrame(mono, 37.0, -122.0, 10f, 10f, 5f, systemId = 1)
        val hb = VehicleStateFrame(mono, systemId = 1, componentId = 1, isArmed = true)
        repo.onFrame(TelemetryFrame.Position(pos), nowMonotonic = mono)
        repo.onFrame(TelemetryFrame.VehicleState(hb), nowMonotonic = mono)

        repo.checkWatchdog(mono)

        // Battery must be expired even though GPS and Heartbeat traffic is flowing!
        assertTrue(repo.state.value.batteryStale)
        assertTrue(repo.state.value.battery?.stale == true)
        // Position and heartbeat must NOT be stale
        assertFalse(repo.state.value.positionStale)
        assertFalse(repo.state.value.heartbeatStale)
    }

    @Test
    fun independentGpsExpiry() {
        var mono = 1_000L
        val repo = TelemetryRepository(
            transport = FakeTransport(),
            targetSystemId = 1,
            clockMonotonicMs = { mono }
        )

        val b = BatteryFrame(mono, List(6) { 3.85f }, 23.1f, 5f, remainingPercent = 80, systemId = 1)
        val pos = PositionFrame(mono, 37.0, -122.0, 10f, 10f, 5f, systemId = 1)
        repo.onFrame(TelemetryFrame.Battery(b), nowMonotonic = mono)
        repo.onFrame(TelemetryFrame.Position(pos), nowMonotonic = mono)

        // Advance 6 seconds (> 5,000ms positionMaxAgeMs), refreshing battery but NOT position
        mono += 6_000L
        val b2 = BatteryFrame(mono, List(6) { 3.84f }, 23.0f, 5f, remainingPercent = 79, systemId = 1)
        repo.onFrame(TelemetryFrame.Battery(b2), nowMonotonic = mono)

        repo.checkWatchdog(mono)

        assertFalse(repo.state.value.batteryStale)
        assertTrue(repo.state.value.positionStale)
    }

    @Test
    fun clockJumpImmunity() {
        var mono = 1_000L
        var wall = 100_000L
        val repo = TelemetryRepository(
            transport = FakeTransport(),
            targetSystemId = 1,
            clockMonotonicMs = { mono },
            clockWallMs = { wall }
        )

        val b = BatteryFrame(wall, List(6) { 3.85f }, 23.1f, 5f, remainingPercent = 80, systemId = 1)
        repo.onFrame(TelemetryFrame.Battery(b), nowMonotonic = mono)

        // Jump wall clock 1 hour forward, but monotonic advances only 500ms
        mono += 500L
        wall += 3_600_000L
        repo.checkWatchdog(mono)

        assertFalse(repo.state.value.stale)
        assertFalse(repo.state.value.batteryStale)

        // Jump wall clock 2 hours backward, monotonic advances another 500ms
        mono += 500L
        wall -= 7_200_000L
        repo.checkWatchdog(mono)

        assertFalse(repo.state.value.stale)
        assertFalse(repo.state.value.batteryStale)
    }

    @Test
    fun reconnectNeedsBattery() {
        val transport = FakeTransport()
        val repo = TelemetryRepository(transport = transport, targetSystemId = 1)

        // Transport reports Connected, but no battery frame received yet
        val pos = PositionFrame(1_000L, 37.0, -122.0, 10f, 10f, 5f, systemId = 1)
        repo.onFrame(TelemetryFrame.Position(pos))

        assertNotEquals(ConnectionState.Connected, repo.state.value.connection)

        // Now battery frame arrives
        val b = BatteryFrame(1_000L, List(6) { 3.85f }, 23.1f, 5f, remainingPercent = 80, systemId = 1)
        repo.onFrame(TelemetryFrame.Battery(b))

        assertEquals(ConnectionState.Connected, repo.state.value.connection)
    }

    @Test
    fun severeAndCellFaultSurviveLinkLoss() {
        val engine = AlertEngine()
        var now = 1_000L

        // Trigger Emergency and cell fault
        val analysis = BatteryAnalysis(
            cellCount = 6,
            cellVoltagesV = listOf(3.38f, 3.48f, 3.40f, 3.40f, 3.40f, 3.40f),
            restCellVoltagesV = listOf(3.38f, 3.48f, 3.40f, 3.40f, 3.40f, 3.40f),
            minCellV = 3.38f,
            maxCellV = 3.48f,
            avgCellV = 3.41f,
            cellDeltaV = 0.10f, // > 0.08V -> CELL_FAULT!
            packVoltageV = 20.46f,
            currentA = 10f,
            remainingPercent = 15,
            consumptionMahPerMin = null,
            minutesRemaining = null,
            temperatureC = 30f,
            isSagRapid = false,
            flightElapsedSec = 100
        )

        val state1 = engine.evaluate(analysis, null, ConnectionState.Connected, now)
        assertEquals(AlertLevel.EMERGENCY, state1.active?.level)
        assertTrue(state1.cellFault)

        // Now link lost occurs
        now += 2_000L
        val staleAnalysis = analysis.copy(isStale = true)
        val state2 = engine.evaluate(staleAnalysis, null, ConnectionState.LinkLost(now), now)

        // Severe alert must NOT be replaced by mild Warning!
        assertEquals(AlertLevel.EMERGENCY, state2.active?.level)
        assertTrue(state2.active?.reasons?.contains(AlertReason.LINK_LOST) == true)
        // Cell fault remains active
        assertTrue(state2.cellFault)
    }

    @Test
    fun freshStableHystereticDowngrade() {
        val engine = AlertEngine()
        var now = 1_000L

        // 1. Enter Critical via low minCell <= 3.50V
        fun makeAnalysis(minV: Float, deltaV: Float = 0.02f) = BatteryAnalysis(
            cellCount = 6,
            cellVoltagesV = List(6) { minV },
            restCellVoltagesV = List(6) { minV },
            minCellV = minV,
            maxCellV = minV + deltaV,
            avgCellV = minV,
            cellDeltaV = deltaV,
            packVoltageV = minV * 6,
            currentA = 10f,
            remainingPercent = 50,
            consumptionMahPerMin = null,
            minutesRemaining = null,
            temperatureC = 25f,
            isSagRapid = false,
            flightElapsedSec = 10
        )

        val s1 = engine.evaluate(makeAnalysis(3.49f), null, ConnectionState.Connected, now)
        assertEquals(AlertLevel.CRITICAL, s1.active?.level)

        // 2. Voltage rises slightly to 3.51V (still within 3.50 + 0.03 = 3.53V hysteresis threshold)
        now += 1_000L
        val s2 = engine.evaluate(makeAnalysis(3.51f), null, ConnectionState.Connected, now)
        assertEquals(AlertLevel.CRITICAL, s2.active?.level)

        // 3. Voltage rises to 3.55V (> 3.53V hysteresis cleared), but requires 5.0s criticalClearMs
        now += 1_000L
        val s3 = engine.evaluate(makeAnalysis(3.55f), null, ConnectionState.Connected, now)
        assertEquals(AlertLevel.CRITICAL, s3.active?.level)

        // 4. Advance 4.0s (total 4.0s < 5.0s): still Critical
        now += 4_000L
        val s4 = engine.evaluate(makeAnalysis(3.55f), null, ConnectionState.Connected, now)
        assertEquals(AlertLevel.CRITICAL, s4.active?.level)

        // 5. Advance another 1.1s (total 5.1s >= 5.0s): downgrades to WARNING (since 3.55V <= 3.65V warningCellVoltageV)
        now += 1_100L
        val s5 = engine.evaluate(makeAnalysis(3.55f), null, ConnectionState.Connected, now)
        assertEquals(AlertLevel.WARNING, s5.active?.level)

        // 6. Voltage rises to healthy 3.80V (> 3.68V): starts debounce timer, remains Warning on first sample
        now += 1_000L
        val s6 = engine.evaluate(makeAnalysis(3.80f), null, ConnectionState.Connected, now)
        assertEquals(AlertLevel.WARNING, s6.active?.level)

        // 7. Advance past 1.5s alertDebounceMs (e.g. 1.6s): successfully downgrades to NONE
        now += 1_600L
        val s7 = engine.evaluate(makeAnalysis(3.80f), null, ConnectionState.Connected, now)
        assertNull(s7.active)
    }

    @Test
    fun faultDuringPendingRecoveryBranch() {
        var faultSpoken = false
        val engine = AlertEngine(output = object : AlertOutput {
            override fun onAlertChanged(previous: ActiveAlert?, current: ActiveAlert?) = Unit
            override fun onCellFaultChanged(active: Boolean, reason: String?) {
                if (active) faultSpoken = true
            }
            override fun onPeriodicStatus(analysis: BatteryAnalysis) = Unit
            override fun release() = Unit
        })

        var now = 1_000L
        fun analysisWithDelta(delta: Float) = BatteryAnalysis(
            cellCount = 6,
            cellVoltagesV = listOf(3.80f, 3.80f + delta, 3.80f, 3.80f, 3.80f, 3.80f),
            restCellVoltagesV = listOf(3.80f, 3.80f + delta, 3.80f, 3.80f, 3.80f, 3.80f),
            minCellV = 3.80f,
            maxCellV = 3.80f + delta,
            avgCellV = 3.80f,
            cellDeltaV = delta,
            packVoltageV = 22.8f,
            currentA = 5f,
            remainingPercent = 50,
            consumptionMahPerMin = null,
            minutesRemaining = null,
            temperatureC = 25f,
            isSagRapid = false,
            flightElapsedSec = 10
        )

        val s1 = engine.evaluate(analysisWithDelta(0.02f), null, ConnectionState.Connected, now)
        assertFalse(s1.cellFault)
        assertFalse(faultSpoken)

        // Immediate fault injection delta = 0.09V (> 0.08V)
        now += 200L
        val s2 = engine.evaluate(analysisWithDelta(0.09f), null, ConnectionState.Connected, now)
        assertTrue(s2.cellFault)
        assertTrue(faultSpoken)
    }
}
