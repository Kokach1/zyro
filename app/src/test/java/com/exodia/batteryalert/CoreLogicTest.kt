package com.exodia.batteryalert

import com.exodia.batteryalert.core.alert.AlertEngine
import com.exodia.batteryalert.core.analysis.*
import com.exodia.batteryalert.core.config.BatteryProfiles
import com.exodia.batteryalert.core.model.*
import org.junit.Assert.*
import org.junit.Test

class CoreLogicTest {
    @Test fun sagCompensationUsesPerCellResistance() {
        val analyzer = BatteryAnalyzer(BatteryProfiles.default); val history = ConsumptionHistory(30)
        val result = analyzer.analyze(BatteryFrame(0, List(14) { 3.8f }, 53.2f, 100f, remainingPercent = 50), PackConfig(14, BatteryChemistry.LI_ION, true), history, 0)
        assertEquals(4f, result.restCellVoltagesV.first(), .001f)
    }
    @Test fun measuredCellDeltaIsStrictlyGreaterThanLimit() {
        val analyzer = BatteryAnalyzer(BatteryProfiles.default); val history = ConsumptionHistory(30)
        fun delta(cells: List<Float>) = analyzer.analyze(BatteryFrame(0, cells, 52f, 0f), PackConfig(cells.size, BatteryChemistry.LI_ION, true), history, 0).cellDeltaV
        assertEquals(.11f, delta(listOf(3.82f, 3.71f) + List(9) { 3.78f }), .001f)
        assertTrue(delta(listOf(3.82f, 3.73f)) > .08f); assertFalse(delta(listOf(3.82f, 3.74f)) > .08f)
    }
    @Test fun detectorUsesCellsOrSafePackEstimate() {
        val detector = ChemistryDetector(BatteryChemistry.LI_ION)
        assertEquals(PackConfig(14, BatteryChemistry.LI_ION, true), detector.detect(BatteryFrame(0, List(14) { 3.8f }, 53f, 0f)))
        detector.reset(); assertEquals(14, detector.detect(BatteryFrame(0, emptyList(), 51.8f, 0f)).cellCount)
    }
    @Test fun rtlFormulaAndReturnRounding() {
        val calculator = RtlCalculator(BatteryProfiles.default)
        val result = calculator.assess(600f, 540f, 18, 10f)
        assertEquals(18.6f, result.requiredPercent ?: 0f, .01f); assertTrue(result.belowRequired); assertEquals(120, result.returnEtaSec)
        assertEquals(125, calculator.assess(612f, 540f, 19, 10f).returnEtaSec)
    }
    @Test fun geoMathCalculatesLatitudeDistance() { assertEquals(111_195.0, GeoMath.haversineMeters(0.0, 0.0, 1.0, 0.0), 200.0) }
    @Test fun consumptionNeedsTenSecondsAndCalculatesRate() {
        val history = ConsumptionHistory(30); history.add(0, 0f); history.add(9_000, 90f); assertNull(history.rateMahPerMin()); history.add(30_000, 300f); history.add(60_000, 600f); assertEquals(600f, history.rateMahPerMin() ?: 0f, .01f)
    }
    @Test fun flightClockStartsOnlyWithMotorLoad() { val clock = FlightClock(); assertEquals(0, clock.onFrame(0, 4f)); assertEquals(0, clock.onFrame(1_000, 5f)); assertEquals(10, clock.onFrame(11_000, 5f)); clock.reset(); assertEquals(0, clock.elapsedSec(12_000)) }
    @Test fun ringZonesFollowRequiredReturnPower() { val zones = ringZones(33.4f); assertEquals(20f, zones.yellowStart ?: 0f, .01f); assertEquals(33.4f, zones.yellowEnd ?: 0f, .01f); assertNull(ringZones(null).tickPercent) }
    @Test fun alertEnginePrioritisesEmergency() {
        val analysis = BatteryAnalysis(14, List(14) { 3.39f }, List(14) { 3.5f }, 3.39f, 3.39f, 3.39f, 0f, 47f, 100f, 20, null, null, null, true, 0)
        val state = AlertEngine().evaluate(analysis, null, ConnectionState.Connected, 0)
        assertEquals(AlertLevel.EMERGENCY, state.active?.level); assertFalse(state.active?.dismissible ?: true)
    }
    @Test fun profilesKeepApproximationDefault() { assertEquals("AGRAS_T55_DB1580", BatteryProfiles.default.id); assertEquals(14, BatteryProfiles.default.cellCount); assertFalse(BatteryProfiles.all.any { it.confirmed }) }
}
