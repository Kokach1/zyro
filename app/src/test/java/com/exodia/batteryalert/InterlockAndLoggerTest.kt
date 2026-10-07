package com.exodia.batteryalert

import com.exodia.batteryalert.core.control.*
import com.exodia.batteryalert.core.logging.*
import com.exodia.batteryalert.core.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class InterlockAndLoggerTest {

    private class TestCommandPort : OutgoingCommandPort {
        var sentCount = 0
        var shouldSucceed = true
        var lastMapping: PumpHardwareMapping? = null

        override suspend fun sendPumpCutoff(mapping: PumpHardwareMapping): Boolean {
            sentCount++
            lastMapping = mapping
            return shouldSucceed
        }
    }

    private val confirmedMapping = PumpHardwareMapping(
        commandId = 183,
        channel = 5,
        cutoffValue = 1000f,
        targetSystem = 1,
        targetComponent = 1,
        isConfirmedByVendor = true
    )

    private val unconfirmedMapping = confirmedMapping.copy(isConfirmedByVendor = false)

    @Test
    fun optInCutoffTriggersAt20PercentInclusive() = runTest {
        val port = TestCommandPort()
        val controller = PumpInterlockController(isOptedIn = true, mapping = confirmedMapping, commandPort = port)
        assertEquals(InterlockState.READY, controller.state.value)

        // At 21%: no command
        controller.evaluate(remainingPercent = 21, isStale = false, sessionSource = SessionSource.LIVE_UDP)
        assertEquals(0, port.sentCount)
        assertEquals(InterlockState.READY, controller.state.value)

        // At 20% inclusive: triggers cutoff command
        controller.evaluate(remainingPercent = 20, isStale = false, sessionSource = SessionSource.LIVE_UDP)
        assertEquals(1, port.sentCount)
        assertEquals(InterlockState.CUTOFF_REQUESTED, controller.state.value)

        // Subsequent frame at 19%: does NOT send duplicate command
        controller.evaluate(remainingPercent = 19, isStale = false, sessionSource = SessionSource.LIVE_UDP)
        assertEquals(1, port.sentCount)
    }

    @Test
    fun disabledUnconfiguredStaleAndReplayNeverWrites() = runTest {
        val port = TestCommandPort()

        // 1. Disabled (default)
        val disabledCtrl = PumpInterlockController(isOptedIn = false, mapping = confirmedMapping, commandPort = port)
        disabledCtrl.evaluate(remainingPercent = 15, isStale = false, sessionSource = SessionSource.LIVE_UDP)
        assertEquals(0, port.sentCount)
        assertEquals(InterlockState.DISABLED, disabledCtrl.state.value)

        // 2. Unconfirmed mapping
        val unconfCtrl = PumpInterlockController(isOptedIn = true, mapping = unconfirmedMapping, commandPort = port)
        unconfCtrl.evaluate(remainingPercent = 15, isStale = false, sessionSource = SessionSource.LIVE_UDP)
        assertEquals(0, port.sentCount)
        assertEquals(InterlockState.NOT_CONFIGURED, unconfCtrl.state.value)

        // 3. Stale data or null percent
        val staleCtrl = PumpInterlockController(isOptedIn = true, mapping = confirmedMapping, commandPort = port)
        staleCtrl.evaluate(remainingPercent = 15, isStale = true, sessionSource = SessionSource.LIVE_UDP)
        staleCtrl.evaluate(remainingPercent = null, isStale = false, sessionSource = SessionSource.LIVE_UDP)
        assertEquals(0, port.sentCount)

        // 4. Replay mode
        val replayCtrl = PumpInterlockController(isOptedIn = true, mapping = confirmedMapping, commandPort = port)
        replayCtrl.evaluate(remainingPercent = 10, isStale = false, sessionSource = SessionSource.REPLAY)
        assertEquals(0, port.sentCount)
    }

    @Test
    fun commandAckAndFeedbackSeparation() = runTest {
        val port = TestCommandPort()
        val controller = PumpInterlockController(isOptedIn = true, mapping = confirmedMapping, commandPort = port)

        controller.evaluate(remainingPercent = 18, isStale = false, sessionSource = SessionSource.LIVE_UDP)
        assertEquals(InterlockState.CUTOFF_REQUESTED, controller.state.value)

        // Autopilot ACK received -> ACKNOWLEDGED (not yet confirmed physical OFF)
        controller.onCommandAck(CommandAckResult.ACCEPTED, commandId = 183)
        assertEquals(InterlockState.ACKNOWLEDGED, controller.state.value)

        // Physical feedback verified -> CONFIRMED_OFF
        controller.confirmPhysicalFeedbackOff()
        assertEquals(InterlockState.CONFIRMED_OFF, controller.state.value)
    }

    @Test
    fun noAutomaticPumpOnAfterRecovery() = runTest {
        val port = TestCommandPort()
        val controller = PumpInterlockController(isOptedIn = true, mapping = confirmedMapping, commandPort = port)

        // Cutoff triggered at 18%
        controller.evaluate(remainingPercent = 18, isStale = false, sessionSource = SessionSource.LIVE_UDP)
        controller.onCommandAck(CommandAckResult.ACCEPTED, commandId = 183)
        assertEquals(InterlockState.ACKNOWLEDGED, controller.state.value)

        // Battery percent rises to 50% (e.g. noise recovery or fresh pack)
        controller.evaluate(remainingPercent = 50, isStale = false, sessionSource = SessionSource.LIVE_UDP)
        // Must NOT turn pump back on or send new command!
        assertEquals(1, port.sentCount)
        assertEquals(InterlockState.ACKNOWLEDGED, controller.state.value)
    }

    @Test
    fun boundedRetriesOnFailure() = runTest {
        val port = TestCommandPort().apply { shouldSucceed = false }
        val controller = PumpInterlockController(isOptedIn = true, mapping = confirmedMapping, commandPort = port)

        // Attempt 1 fails
        controller.evaluate(remainingPercent = 15, isStale = false, sessionSource = SessionSource.LIVE_UDP)
        assertEquals(1, port.sentCount)
        assertEquals(InterlockState.FAILED, controller.state.value)

        // Attempt 2
        controller.evaluate(remainingPercent = 15, isStale = false, sessionSource = SessionSource.LIVE_UDP)
        assertEquals(2, port.sentCount)

        // Attempt 3
        controller.evaluate(remainingPercent = 15, isStale = false, sessionSource = SessionSource.LIVE_UDP)
        assertEquals(3, port.sentCount)

        // Attempt 4: capped at max 3 attempts!
        controller.evaluate(remainingPercent = 15, isStale = false, sessionSource = SessionSource.LIVE_UDP)
        assertEquals(3, port.sentCount)
    }

    @Test
    fun continuousDurableRecordsAndGpsAssociation() = runTest {
        val bos = ByteArrayOutputStream()
        val logger = TelemetryLogger(bos, this)

        val bFrame = BatteryFrame(
            timestampMs = 1000L,
            cellVoltagesV = listOf(3.82f, Float.NaN, 3.81f),
            packVoltageV = 11.4f,
            currentA = 12f,
            consumedMah = 200f,
            remainingPercent = 60,
            temperatureC = 28f,
            systemId = 1,
            batteryId = 0
        )

        val posFrame = PositionFrame(
            timestampMs = 950L,
            latDeg = 37.774929,
            lonDeg = -122.419416,
            altitudeM = 25f,
            relativeAltitudeM = 25f,
            groundSpeedMps = 5f,
            systemId = 1
        )

        logger.log(
            analysis = null,
            batteryFrame = bFrame,
            positionFrame = posFrame,
            isPositionStale = false,
            sessionSource = SessionSource.LIVE_UDP,
            alertLevel = AlertLevel.NONE,
            cellFault = false,
            pumpState = "READY",
            nowWallMs = 1000L,
            nowMonoMs = 5000L
        )

        // Log when GPS is stale
        logger.log(
            analysis = null,
            batteryFrame = bFrame,
            positionFrame = posFrame,
            isPositionStale = true,
            sessionSource = SessionSource.LIVE_UDP,
            alertLevel = AlertLevel.WARNING,
            cellFault = true,
            pumpState = "DISABLED",
            nowWallMs = 2000L,
            nowMonoMs = 6000L
        )

        logger.flushAndClose()

        val output = bos.toString(Charsets.UTF_8.name())
        val lines = output.lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)

        // Verify record 1 (GPS fresh)
        val r1 = TelemetryLogger.parseRecord(lines[0])
        assertNotNull(r1)
        assertEquals(37.774929, r1!!.positionLat ?: 0.0, 0.000001)
        assertEquals(-122.419416, r1.positionLon ?: 0.0, 0.000001)
        assertEquals(3, r1.cellVoltagesV.size)
        assertTrue(r1.cellVoltagesV[1].isNaN())
        assertEquals(50L, r1.positionAgeMs)
        assertFalse(r1.isPositionStale)

        // Verify record 2 (GPS stale: null coordinates, never fake 0,0)
        val r2 = TelemetryLogger.parseRecord(lines[1])
        assertNotNull(r2)
        assertNull(r2!!.positionLat)
        assertNull(r2.positionLon)
        assertTrue(r2.isPositionStale)
        assertTrue(r2.cellFault)
    }

    @Test
    fun roundTripExportAndReplayParser() = runTest {
        val originalRecord = LogRecord(
            schemaVersion = 1,
            utcTimeMs = 1728320000000L,
            monoTimeMs = 123456L,
            sessionSource = "LIVE_UDP",
            systemId = 1,
            batteryId = 0,
            cellVoltagesV = listOf(3.850f, 3.840f, Float.NaN, 3.860f),
            packVoltageV = 53.8f,
            currentA = 22.5f,
            consumedMah = 1500f,
            remainingPercent = 75,
            temperatureC = 31.0f,
            isStale = false,
            positionLat = 37.123456,
            positionLon = -122.654321,
            positionAltM = 15.5f,
            positionAgeMs = 150L,
            isPositionStale = false,
            alertLevel = "NOTICE",
            cellFault = false,
            pumpState = "READY"
        )

        val bos = ByteArrayOutputStream()
        val logger = TelemetryLogger(bos, this)
        val bFrame = BatteryFrame(
            timestampMs = originalRecord.utcTimeMs,
            cellVoltagesV = originalRecord.cellVoltagesV,
            packVoltageV = originalRecord.packVoltageV,
            currentA = originalRecord.currentA,
            consumedMah = originalRecord.consumedMah,
            remainingPercent = originalRecord.remainingPercent,
            temperatureC = originalRecord.temperatureC,
            systemId = originalRecord.systemId,
            batteryId = originalRecord.batteryId
        )
        val posFrame = PositionFrame(
            timestampMs = originalRecord.utcTimeMs - 150L,
            latDeg = originalRecord.positionLat!!,
            lonDeg = originalRecord.positionLon!!,
            altitudeM = originalRecord.positionAltM!!,
            relativeAltitudeM = 15.5f,
            groundSpeedMps = 5f,
            systemId = originalRecord.systemId
        )

        logger.log(
            analysis = null,
            batteryFrame = bFrame,
            positionFrame = posFrame,
            isPositionStale = false,
            sessionSource = SessionSource.LIVE_UDP,
            alertLevel = AlertLevel.NOTICE,
            cellFault = false,
            pumpState = "READY",
            nowWallMs = originalRecord.utcTimeMs,
            nowMonoMs = originalRecord.monoTimeMs
        )

        logger.flushAndClose()

        val line = bos.toString(Charsets.UTF_8.name()).lines().first { it.isNotBlank() }
        val parsed = TelemetryLogger.parseRecord(line)
        assertNotNull(parsed)

        assertEquals(originalRecord.utcTimeMs, parsed!!.utcTimeMs)
        assertEquals(originalRecord.sessionSource, parsed.sessionSource)
        assertEquals(originalRecord.packVoltageV ?: 0f, parsed.packVoltageV ?: 0f, 0.01f)
        assertEquals(originalRecord.currentA ?: 0f, parsed.currentA ?: 0f, 0.01f)
        assertEquals(originalRecord.positionLat ?: 0.0, parsed.positionLat ?: 0.0, 0.0001)
        assertEquals(originalRecord.cellVoltagesV.size, parsed.cellVoltagesV.size)
        assertTrue(parsed.cellVoltagesV[2].isNaN())
    }
}
