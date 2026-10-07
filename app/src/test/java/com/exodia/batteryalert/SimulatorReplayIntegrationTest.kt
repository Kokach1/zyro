package com.exodia.batteryalert

import com.exodia.batteryalert.core.alert.AlertEngine
import com.exodia.batteryalert.core.analysis.*
import com.exodia.batteryalert.core.logging.TelemetryLogger
import com.exodia.batteryalert.core.model.*
import com.exodia.batteryalert.core.telemetry.TelemetryRepository
import com.exodia.batteryalert.core.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SimulatorReplayIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testGoldenDynamicRtlScenarioExclusiveReason() = runBlocking {
        val clock = object : Clock {
            var time = 1_000_000L
            override fun nowMs(): Long = time
        }
        val sim = SimulatorTransport(clock)
        sim.setScenario(SimulatorScenario.CRITICAL_RTL)

        val profile = sim.profile
        assertEquals(14, profile.cellCount)
        assertEquals(30000, profile.capacityMah)

        val repo = TelemetryRepository(
            transport = sim,
            targetSystemId = 1,
            clockMonotonicMs = { clock.nowMs() },
            clockWallMs = { clock.nowMs() }
        )

        val analyzer = BatteryAnalyzer(profile)
        val history = ConsumptionHistory(30)
        val detector = ChemistryDetector(profile.chemistry)
        val flightClock = FlightClock()
        val alertEngine = AlertEngine()

        // Direct frame feed to repo for deterministic unit test
        val collectorJob = launch {
            sim.frames.collect { frame ->
                repo.onFrame(frame, clock.nowMs())
            }
        }
        yield()

        repeat(5) {
            sim.emitTick()
            yield()
            clock.time += 200L
        }
        collectorJob.cancel()

        val state = repo.state.value
        assertNotNull("Battery frame should be present", state.battery)
        assertNotNull("Home frame should be present", state.home)
        assertNotNull("Position frame should be present", state.position)

        val bFrame = state.battery!!
        val pFrame = state.position!!
        val hFrame = state.home!!

        // 1. Equal 3.80V measured cells across all 14 slots
        assertEquals(14, bFrame.cellVoltagesV.size)
        bFrame.cellVoltagesV.forEach { v ->
            assertEquals(3.80f, v, 0.001f)
        }
        assertEquals(14 * 3.80f, bFrame.packVoltageV!!, 0.01f)
        assertEquals(30, bFrame.remainingPercent)
        assertEquals(110.0f, bFrame.currentA!!, 0.01f)

        // 2. Geodesic distance is exactly 900m
        val distance = GeoMath.haversineMeters(hFrame.latDeg, hFrame.lonDeg, pFrame.latDeg, pFrame.lonDeg).toFloat()
        assertEquals(900.0f, distance, 1.0f)

        // 3. Analysis pipeline
        val pack = detector.detect(bFrame)
        val analysis = analyzer.analyze(bFrame, pack, history, flightClock.onFrame(bFrame.timestampMs, bFrame.currentA, state.vehicleState?.isArmed))

        assertNotNull(analysis)
        assertEquals(1833.333f, analysis.consumptionMahPerMin!!, 1.0f)
        assertEquals(3.80f, analysis.minCellV!!, 0.001f)
        assertEquals(3.80f, analysis.maxCellV!!, 0.001f)
        assertEquals(0.00f, analysis.cellDeltaV!!, 0.001f)

        // 4. RTL Calculator assessment
        val rtl = RtlCalculator(profile).assess(
            distanceM = distance,
            rateMahPerMin = analysis.consumptionMahPerMin,
            remainingPercent = analysis.remainingPercent,
            minutesRemaining = analysis.minutesRemaining
        )
        assertNotNull(rtl)
        // 900m / 5m/s = 180s. burnRate = (1833.333 / 60 / 30000) * 100 = 0.10185 %/s.
        // required = 180 * 0.10185 + 15 = 18.333 + 15 = 33.333%
        assertEquals(33.333f, rtl.requiredPercent!!, 0.1f)
        assertTrue(rtl.belowRequired)

        // 5. Alert Engine assessment
        val evaluation = alertEngine.evaluate(analysis, rtl, ConnectionState.Connected, bFrame.timestampMs)
        assertEquals(AlertLevel.CRITICAL, evaluation.active?.level)
        assertFalse(evaluation.cellFault)

        // Exclusive reason is BELOW_DYNAMIC_RTL
        val reasons = evaluation.active?.reasons ?: emptyList()
        assertTrue("Must contain BELOW_DYNAMIC_RTL", reasons.contains(AlertReason.BELOW_DYNAMIC_RTL))
        assertFalse("Must NOT contain LOW_CELL_VOLTAGE_CRITICAL", reasons.contains(AlertReason.LOW_CELL_VOLTAGE_CRITICAL))
        assertFalse("Must NOT contain LOW_CELL_VOLTAGE_EMERGENCY", reasons.contains(AlertReason.LOW_CELL_VOLTAGE_EMERGENCY))
        assertFalse("Must NOT contain CELL_IMBALANCE", reasons.contains(AlertReason.CELL_IMBALANCE))
        assertFalse("Must NOT contain RAPID_SAG", reasons.contains(AlertReason.RAPID_SAG))

        sim.stop()
    }

    @Test
    fun testVirtualSimulationRateConsistentAcrossSpeeds() = runBlocking {
        suspend fun simulateTicks(speed: Int, count: Int): Pair<Long, Float> {
            val sim = SimulatorTransport()
            sim.setScenario(SimulatorScenario.CRITICAL_RTL)
            sim.setSpeedMultiplier(speed)
            val baseVirtual = sim.simulatedVirtualMs
            val baseConsumed = sim.consumedMah
            repeat(count) {
                sim.emitTick()
            }
            return Pair(sim.simulatedVirtualMs - baseVirtual, sim.consumedMah - baseConsumed)
        }

        val (virtualMs1, consumed1) = simulateTicks(1, 10)
        val (virtualMs5, consumed5) = simulateTicks(5, 10)
        val (virtualMs20, consumed20) = simulateTicks(20, 10)

        // Virtual time advances proportionally to speed multiplier
        assertEquals(2000L, virtualMs1)
        assertEquals(10000L, virtualMs5)
        assertEquals(40000L, virtualMs20)

        // Rate in mAh per virtual second is identical across speeds!
        val rate1 = consumed1 / (virtualMs1 / 1000f)
        val rate5 = consumed5 / (virtualMs5 / 1000f)
        val rate20 = consumed20 / (virtualMs20 / 1000f)

        assertEquals(rate1, rate5, 0.01f)
        assertEquals(rate1, rate20, 0.01f)
    }

    @Test
    fun testExplicitHomeAndVehicleStateEmitted() = runBlocking {
        val sim = SimulatorTransport()
        sim.setScenario(SimulatorScenario.NORMAL_FLIGHT)

        sim.emitTick()

        val homeFrame = withTimeout(1000) {
            sim.frames.first { it is TelemetryFrame.Home } as TelemetryFrame.Home
        }
        assertEquals(SimulatorTransport.HOME_LAT, homeFrame.value.latDeg, 0.0001)
        assertEquals(SimulatorTransport.HOME_LON, homeFrame.value.lonDeg, 0.0001)
        assertEquals(HomeSource.SIMULATOR_HOME, homeFrame.value.source)
        assertTrue(homeFrame.value.isValid)

        val vehicleFrame = withTimeout(1000) {
            sim.frames.first { it is TelemetryFrame.VehicleState } as TelemetryFrame.VehicleState
        }
        assertTrue(vehicleFrame.value.isArmed)

        sim.stop()
    }

    @Test
    fun testScenarioIsolationAndReset() = runBlocking {
        val sim = SimulatorTransport()

        // 1. Emergency scenario
        sim.setScenario(SimulatorScenario.EMERGENCY)
        sim.emitTick()
        assertTrue(sim.consumedMah > 0)
        assertEquals(SimulatorScenario.EMERGENCY, sim.scenario)

        // 2. Switch to Normal flight clears all states
        sim.setScenario(SimulatorScenario.NORMAL_FLIGHT)
        assertEquals(0f, sim.consumedMah, 0.001f)
        assertEquals(0L, sim.simulatedVirtualMs)
        assertEquals(0f, sim.distanceM, 0.001f)
        assertEquals(SimulatorScenario.NORMAL_FLIGHT, sim.scenario)

        // 3. Low cell scenario specifically tests min cell <= 3.50V without imbalance
        sim.setScenario(SimulatorScenario.LOW_CELL)
        val deferredLow = async {
            withTimeout(1000) {
                (sim.frames.first { it is TelemetryFrame.Battery } as TelemetryFrame.Battery).value
            }
        }
        sim.emitTick()
        val lowBattery = deferredLow.await()
        assertEquals(3.48f, lowBattery.cellVoltagesV[0], 0.001f)
        assertEquals(3.52f, lowBattery.cellVoltagesV[1], 0.001f)
        assertTrue(lowBattery.cellVoltagesV.maxOrNull()!! - lowBattery.cellVoltagesV.minOrNull()!! <= 0.08f)

        // 4. Cell imbalance scenario has delta > 0.08V
        sim.setScenario(SimulatorScenario.CELL_IMBALANCE)
        val deferredImb = async {
            withTimeout(1000) {
                (sim.frames.first { it is TelemetryFrame.Battery } as TelemetryFrame.Battery).value
            }
        }
        sim.emitTick()
        val imbBattery = deferredImb.await()
        assertEquals(3.70f, imbBattery.cellVoltagesV[0], 0.001f)
        assertEquals(3.82f, imbBattery.cellVoltagesV[1], 0.001f)
        assertEquals(0.12f, imbBattery.cellVoltagesV[1] - imbBattery.cellVoltagesV[0], 0.001f)

        sim.stop()
    }

    @Test
    fun testSimulatedPumpCutoffReducesLoad() = runBlocking {
        val sim = SimulatorTransport()
        sim.setScenario(SimulatorScenario.NORMAL_FLIGHT)
        sim.simulatePumpCutoff = true

        // Force consumed near 20%
        sim.setScenario(SimulatorScenario.WARNING_20) // 20%
        sim.emitTick()

        assertTrue(sim.isPumpCutoffActive)

        sim.stop()
    }

    @Test
    fun testReplayStructuredJsonlRoundTrip() = runBlocking {
        val testFile = tempFolder.newFile("blackbox_log.jsonl")

        // Write a valid LogRecord matching TelemetryLogger schema
        val recordLine = buildString {
            append("{")
            append("\"schema\":1,")
            append("\"utc\":1700000000000,")
            append("\"mono\":5000000,")
            append("\"source\":\"REPLAY\",")
            append("\"sysId\":1,")
            append("\"batId\":0,")
            append("\"cells\":[3.820,3.820,3.820,3.820,3.820,3.820],")
            append("\"packV\":22.920,")
            append("\"currA\":45.5,")
            append("\"consumedMah\":1200.0,")
            append("\"percent\":75,")
            append("\"tempC\":32.0,")
            append("\"stale\":false,")
            append("\"lat\":10.015900,")
            append("\"lon\":76.341900,")
            append("\"alt\":35.0,")
            append("\"posAge\":150,")
            append("\"posStale\":false,")
            append("\"alert\":\"NONE\",")
            append("\"cellFault\":false,")
            append("\"pump\":\"NONE\"")
            append("}")
        }
        testFile.writeText(recordLine + "\n")

        val transport = ReplayTransport(
            filePath = testFile.absolutePath,
            speedMultiplier = 100
        )
        assertEquals(SessionSource.REPLAY, transport.sessionSource)
        assertEquals("Replay", transport.displayName)

        val deferredBattery = async {
            withTimeout(3000) {
                transport.frames.first { it is TelemetryFrame.Battery } as TelemetryFrame.Battery
            }
        }

        transport.start()
        val batFrame = deferredBattery.await()

        assertEquals(6, batFrame.value.cellVoltagesV.size)
        assertEquals(3.820f, batFrame.value.cellVoltagesV[0], 0.001f)
        assertEquals(22.920f, batFrame.value.packVoltageV!!, 0.01f)
        assertEquals(45.5f, batFrame.value.currentA!!, 0.01f)
        assertEquals(75, batFrame.value.remainingPercent)

        transport.stop()
        assertEquals(ConnectionState.Disconnected, transport.connectionState.value)
    }

    @Test
    fun testReplayMalformedAndPartialRecordsHandledGracefully() = runBlocking {
        val testFile = tempFolder.newFile("mixed_log.jsonl")
        testFile.writeText(
            """
            # Comment line
            
            {not valid json}
            {"type":"battery", "cells":[3.85, 3.85], "current":10.0, "percent":90}
            random garbage
            """.trimIndent()
        )

        val transport = ReplayTransport(
            filePath = testFile.absolutePath,
            speedMultiplier = 100
        )

        val deferredBattery = async {
            withTimeout(3000) {
                transport.frames.first { it is TelemetryFrame.Battery } as TelemetryFrame.Battery
            }
        }

        transport.start()
        val bat = deferredBattery.await().value

        assertEquals(2, bat.cellVoltagesV.size)
        assertEquals(90, bat.remainingPercent)

        transport.stop()
    }
}

