package com.exodia.batteryalert

import com.exodia.batteryalert.core.analysis.*
import com.exodia.batteryalert.core.config.BatteryProfile
import com.exodia.batteryalert.core.config.BatteryProfiles
import com.exodia.batteryalert.core.model.*
import com.exodia.batteryalert.core.telemetry.TelemetryRepository
import com.exodia.batteryalert.core.transport.TelemetryTransport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Test

class AnalysisDetectionConsumptionRtlTest {

    @Test
    fun stable6S12S14SDetection() {
        val detector = ChemistryDetector(BatteryChemistry.LI_ION)

        // 6S with 5 baseline frames under low load
        val f6 = BatteryFrame(1000L, List(6) { 3.82f }, 22.92f, 2f, systemId = 1)
        val cfg6 = detector.detect(f6)
        assertEquals(6, cfg6.cellCount)
        assertTrue(cfg6.detected)

        // 12S
        detector.reset()
        val f12 = BatteryFrame(1000L, List(12) { 3.80f }, 45.6f, 3f, systemId = 1)
        repeat(5) { detector.detect(f12) }
        val cfg12 = detector.detect(f12)
        assertEquals(12, cfg12.cellCount)
        assertTrue(cfg12.detected)

        // 14S
        detector.reset()
        val f14 = BatteryFrame(1000L, List(14) { 3.85f }, 53.9f, 2f, systemId = 1)
        repeat(5) { detector.detect(f14) }
        val cfg14 = detector.detect(f14)
        assertEquals(14, cfg14.cellCount)
        assertTrue(cfg14.detected)
    }

    @Test
    fun ambiguousChemistryAndAggregateVoltage() {
        val detector = ChemistryDetector(BatteryChemistry.LI_ION)

        // Aggregate pack voltage only (no cells)
        val aggFrame = BatteryFrame(1000L, emptyList(), 48.0f, 0f, systemId = 1)
        val cfgAgg = detector.detect(aggFrame)
        // Inferred count 12, but chemistry is UNKNOWN and detected = false
        assertEquals(12, cfgAgg.cellCount)
        assertEquals(BatteryChemistry.UNKNOWN, cfgAgg.chemistry)
        assertFalse(cfgAgg.detected)

        // Cell voltages > 4.25V detected as LIHV
        detector.reset()
        val hvFrame = BatteryFrame(1000L, List(6) { 4.30f }, 25.8f, 1f, systemId = 1)
        repeat(5) { detector.detect(hvFrame) }
        val cfgHv = detector.detect(hvFrame)
        assertEquals(BatteryChemistry.LIHV, cfgHv.chemistry)
    }

    @Test
    fun noInventedCellsAndPreserveZeroCellFault() {
        val detector = ChemistryDetector(BatteryChemistry.LI_ION)
        // 14S pack with cell 0 at 0.0V (dead cell fault)
        val cellsWithFault = listOf(0.0f) + List(13) { 3.80f }
        val frame = BatteryFrame(1000L, cellsWithFault, 49.4f, 5f, systemId = 1)
        val cfg = detector.detect(frame)
        // Must NOT lock a smaller 13S count! Cell count must remain 14
        assertEquals(14, cfg.cellCount)

        val analyzer = BatteryAnalyzer(BatteryProfiles.default)
        val analysis = analyzer.analyze(frame, cfg, ConsumptionHistory(), 0L)
        // Actual cells are preserved without fabricating equal cells
        assertEquals(14, analysis.cellCount)
        assertEquals(0.0f, analysis.minCellV ?: -1f, 0.001f)
        assertEquals(0.0f, analysis.cellVoltagesV[0], 0.001f)
    }

    @Test
    fun knownSagEquationCompensatesRestingVoltage() {
        val analyzer = BatteryAnalyzer(BatteryProfiles.default)
        val history = ConsumptionHistory()

        // Known values: V_measured = 3.60V, I = 100A, Ri = 0.002 ohm
        // Expected V_rest = 3.60 + (100 * 0.002) = 3.80V
        val frame = BatteryFrame(
            timestampMs = 1000L,
            cellVoltagesV = List(14) { 3.60f },
            packVoltageV = 50.4f,
            currentA = 100f,
            remainingPercent = 20,
            systemId = 1
        )
        val cfg = PackConfig(14, BatteryChemistry.LI_ION, true)
        val analysis = analyzer.analyze(frame, cfg, history, 0L)

        assertEquals(3.80f, analysis.restCellVoltagesV[0], 0.001f)
        // Loaded min-cell voltage must remain 3.60V (NOT masked by resting voltage!)
        assertEquals(3.60f, analysis.minCellV ?: 0f, 0.001f)
    }

    @Test
    fun unknownCurrentLeavesRestingVoltageUncompensated() {
        val analyzer = BatteryAnalyzer(BatteryProfiles.default)
        val frame = BatteryFrame(
            timestampMs = 1000L,
            cellVoltagesV = List(6) { 3.75f },
            packVoltageV = 22.5f,
            currentA = null, // Unknown current!
            remainingPercent = 50,
            systemId = 1
        )
        val analysis = analyzer.analyze(frame, PackConfig(6, BatteryChemistry.LI_ION, true), ConsumptionHistory(), 0L)

        assertNull(analysis.currentA)
        // When current is unknown, restCellVoltagesV == cellVoltagesV (no I=0 assumption)
        assertEquals(3.75f, analysis.restCellVoltagesV[0], 0.001f)
    }

    @Test
    fun currentIntegrationAndCounterFallback() {
        val history = ConsumptionHistory(30)

        // 110A load: delta_mAh = 110 * dt / 3.6
        // dt = 1.0s -> delta_mAh = 30.5555 mAh per second
        // Rate = 110 * 1000 / 60 = 1833.333 mAh/min
        var time = 1000L
        repeat(15) {
            history.addCurrentSample(time, 110f)
            time += 1000L
        }

        val rate = history.rateMahPerMin()
        assertNotNull(rate)
        assertEquals(1833.333f, rate!!, 5.0f)
    }

    @Test
    fun duplicateResetAndStaleGapHandling() {
        val history = ConsumptionHistory(30)

        history.add(1000L, 100f)
        // Duplicate timestamp: ignored
        history.add(1000L, 100f)
        history.add(15000L, 250f)
        val rate1 = history.rateMahPerMin()
        assertNotNull(rate1)

        // Counter reset / rollback (e.g. pack swap, counter resets to 10mAh)
        history.add(16000L, 10f)
        // Old samples cleared, not enough history yet
        assertNull(history.rateMahPerMin())

        // Current integration skips stale gaps (> 3s)
        history.clear()
        history.addCurrentSample(1000L, 50f)
        // 10s gap (> 3s): must NOT integrate across stale gap
        history.addCurrentSample(11000L, 50f)
        // Add valid continuous samples
        history.addCurrentSample(12000L, 50f)
        history.addCurrentSample(13000L, 50f)
        assertNotNull(history.rateMahPerMin())
    }

    @Test
    fun verifiedHomeOnlyAndRejectFirstGpsAsHome() {
        class DummyTransport : TelemetryTransport {
            override val id = "dummy"
            override val displayName = "Dummy"
            override val sessionSource = SessionSource.LIVE_UDP
            override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
            override val frames = MutableSharedFlow<TelemetryFrame>()
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
        }
        val repo = TelemetryRepository(DummyTransport(), targetSystemId = 1)

        // First GPS fix arrives
        val pos = PositionFrame(1000L, 37.7749, -122.4194, 15f, 15f, 5f, systemId = 1)
        repo.onFrame(TelemetryFrame.Position(pos))

        // Repository must NOT synthesize home from first GPS fix!
        assertNull(repo.state.value.home)

        // Explicit verified Home arrives
        val home = HomeFrame(1100L, 37.7749, -122.4194, 0f, HomeSource.AUTOPILOT_HOME, isValid = true)
        repo.onFrame(TelemetryFrame.Home(home))

        assertNotNull(repo.state.value.home)
        assertEquals(37.7749, repo.state.value.home!!.latDeg, 0.0001)
    }

    @Test
    fun goldenDynamicRtlFixture() {
        // Golden scenario:
        // distance = 900m, cruiseSpeed = 5m/s, capacity = 30000mAh, rate = 1833.333mAh/min
        // returnEta = 900 / 5 = 180s
        // percentPerSec = (1833.333 / 60) / 30000 * 100 = 0.10185185%
        // requiredPercent = 180 * 0.10185185 + 15 = 18.33333 + 15 = 33.3333%
        val profile = BatteryProfiles.default
        val calculator = RtlCalculator(profile, cruisingSpeedMps = 5f)

        val result = calculator.assess(
            distanceM = 900f,
            rateMahPerMin = 1833.3333f,
            remainingPercent = 30, // 30% <= 33.333% -> belowRequired = true
            minutesRemaining = 10f
        )

        assertEquals(180, result.returnEtaSec)
        assertEquals(33.3333f, result.requiredPercent ?: 0f, 0.005f)
        assertTrue(result.belowRequired)

        // At 34% remaining, belowRequired is false
        val safeResult = calculator.assess(
            distanceM = 900f,
            rateMahPerMin = 1833.3333f,
            remainingPercent = 34,
            minutesRemaining = 10f
        )
        assertFalse(safeResult.belowRequired)
    }

    @Test
    fun requiredPercentGreaterThan100NotClamped() {
        val profile = BatteryProfiles.default
        val calculator = RtlCalculator(profile, cruisingSpeedMps = 5f)

        // Far distance = 5000m: return seconds = 1000s
        // requiredPercent = 1000 * 0.10185 + 15 = 116.85% (> 100%)
        val result = calculator.assess(
            distanceM = 5000f,
            rateMahPerMin = 1833.3333f,
            remainingPercent = 80,
            minutesRemaining = 20f
        )

        assertNotNull(result.requiredPercent)
        assertTrue(result.requiredPercent!! > 100f)
        assertTrue(result.belowRequired)
    }

    @Test
    fun invalidSpeedOrCapacityYieldsUnavailable() {
        val profile = BatteryProfiles.default
        val calculator = RtlCalculator(profile, cruisingSpeedMps = 5f)

        // Negative distance
        val r1 = calculator.assess(-10f, 1000f, 50, 10f)
        assertNull(r1.requiredPercent)
        assertFalse(r1.belowRequired)

        // Zero rate
        val r2 = calculator.assess(500f, 0f, 50, 10f)
        assertNull(r2.requiredPercent)

        // Invalid cruising speed <= 0
        val r3 = calculator.assess(500f, 1000f, 50, 10f, speedMps = 0f)
        assertNull(r3.requiredPercent)

        // Invalid zero capacity
        val zeroCapProfile = BatteryProfile("Bad", "Bad", 14, BatteryChemistry.LI_ION, 0, 53.2f, source = "Test")
        val r4 = RtlCalculator(zeroCapProfile).assess(500f, 1000f, 50, 10f)
        assertNull(r4.requiredPercent)
    }
}
