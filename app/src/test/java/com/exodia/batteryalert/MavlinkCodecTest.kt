package com.exodia.batteryalert

import com.exodia.batteryalert.core.model.*
import com.exodia.batteryalert.core.transport.MavlinkCodec
import com.exodia.batteryalert.core.transport.MavlinkCodecConfig
import io.dronefleet.mavlink.common.BatteryStatus
import io.dronefleet.mavlink.common.CommandAck
import io.dronefleet.mavlink.common.GlobalPositionInt
import io.dronefleet.mavlink.common.HomePosition
import io.dronefleet.mavlink.minimal.Heartbeat
import io.dronefleet.mavlink.minimal.MavAutopilot
import io.dronefleet.mavlink.minimal.MavModeFlag
import io.dronefleet.mavlink.minimal.MavState
import io.dronefleet.mavlink.minimal.MavType
import io.dronefleet.mavlink.util.EnumValue
import org.junit.Assert.*
import org.junit.Test

class MavlinkCodecTest {

    private val codec = MavlinkCodec(MavlinkCodecConfig(targetSystemId = 1, targetComponentId = 1))

    @Test
    fun testMavlink2BatteryStatusValid14S() {
        val voltages10 = listOf(
            3850, 3840, 3860, 3850, 3845, 3855, 3840, 3850, 3860, 3850
        )
        val voltagesExt4 = listOf(
            3845, 3855, 3840, 3850
        )

        val batteryPayload = BatteryStatus.builder()
            .id(0)
            .temperature(2850) // 28.50 deg C
            .currentBattery(4500) // 45.00 A
            .currentConsumed(1200) // 1200 mAh
            .batteryRemaining(75) // 75%
            .voltages(voltages10)
            .voltagesExt(voltagesExt4)
            .build()

        val rawPacket = codec.encodeMavlink2(
            systemId = 1,
            componentId = 1,
            sequence = 1,
            payload = batteryPayload
        )

        val frames = codec.feedBytes(rawPacket)
        assertEquals(1, frames.size)
        assertTrue(frames[0] is TelemetryFrame.Battery)

        val battery = (frames[0] as TelemetryFrame.Battery).value
        assertEquals(14, battery.cellVoltagesV.size)
        assertEquals(3.850f, battery.cellVoltagesV[0], 0.001f)
        assertEquals(3.850f, battery.cellVoltagesV[13], 0.001f)
        assertEquals(53.890f, battery.packVoltageV!!, 0.01f)
        assertEquals(45.0f, battery.currentA!!, 0.01f)
        assertEquals(75, battery.remainingPercent)
        assertEquals(28.5f, battery.temperatureC!!, 0.01f)
        assertEquals(1200f, battery.consumedMah!!, 0.1f)
        assertFalse(battery.isAggregateOnly)
    }

    @Test
    fun testVoltagesExtNearZeroSentinel() {
        // Cell 11 encoded as 1 (near-zero sentinel mV)
        val voltages10 = List(10) { 3800 }
        val voltagesExt4 = listOf(1, 3800, 3800, 3800)

        val batteryPayload = BatteryStatus.builder()
            .id(0)
            .temperature(2500)
            .currentBattery(1000)
            .batteryRemaining(50)
            .voltages(voltages10)
            .voltagesExt(voltagesExt4)
            .build()

        val rawPacket = codec.encodeMavlink2(1, 1, 2, batteryPayload)
        val frames = codec.feedBytes(rawPacket)

        assertEquals(1, frames.size)
        val battery = (frames[0] as TelemetryFrame.Battery).value
        assertEquals(14, battery.cellVoltagesV.size)
        // Cell 11 (index 10) must be 0.001f V (measured near-zero sentinel)
        assertEquals(0.001f, battery.cellVoltagesV[10], 0.0001f)
    }

    @Test
    fun testAggregateOnlyPackDetection() {
        // Slot 0 has 52000 mV, remaining base are 65535, ext are 0
        val voltages10 = listOf(52000) + List(9) { 65535 }
        val voltagesExt4 = listOf(0, 0, 0, 0)

        val batteryPayload = BatteryStatus.builder()
            .id(0)
            .temperature(32767) // unknown temp
            .currentBattery(-1) // unknown current
            .batteryRemaining(60)
            .voltages(voltages10)
            .voltagesExt(voltagesExt4)
            .build()

        val rawPacket = codec.encodeMavlink2(1, 1, 3, batteryPayload)
        val frames = codec.feedBytes(rawPacket)

        assertEquals(1, frames.size)
        val battery = (frames[0] as TelemetryFrame.Battery).value
        assertTrue(battery.isAggregateOnly)
        assertTrue(battery.cellVoltagesV.isEmpty())
        assertEquals(52.0f, battery.packVoltageV!!, 0.01f)
        assertNull(battery.temperatureC)
        assertFalse(battery.isCurrentKnown)
    }

    @Test
    fun testMavlink1PacketParsing() {
        val heartbeatPayload = Heartbeat.builder()
            .type(EnumValue.of(MavType.MAV_TYPE_QUADROTOR))
            .autopilot(EnumValue.of(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA))
            .baseMode(EnumValue.of(MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED))
            .systemStatus(EnumValue.of(MavState.MAV_STATE_ACTIVE))
            .build()

        val rawPacket = codec.encodeMavlink1(1, 1, 4, heartbeatPayload)
        val frames = codec.feedBytes(rawPacket)

        assertEquals(1, frames.size)
        assertTrue(frames[0] is TelemetryFrame.VehicleState)
        val state = (frames[0] as TelemetryFrame.VehicleState).value
        assertTrue(state.isArmed)
        assertTrue(state.isAutopilot)
    }

    @Test
    fun testCorruptCrcRejected() {
        val heartbeatPayload = Heartbeat.builder()
            .type(EnumValue.of(MavType.MAV_TYPE_QUADROTOR))
            .autopilot(EnumValue.of(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA))
            .baseMode(EnumValue.of(MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED))
            .systemStatus(EnumValue.of(MavState.MAV_STATE_ACTIVE))
            .build()

        val rawPacket = codec.encodeMavlink2(1, 1, 5, heartbeatPayload)
        // Corrupt one byte in payload
        rawPacket[12] = (rawPacket[12].toInt() xor 0xFF).toByte()

        val frames = codec.feedBytes(rawPacket)
        assertEquals(0, frames.size)
        assertEquals(1L, codec.diagnostics.crcErrors)
    }

    @Test
    fun testNoiseAndGarbageResynchronization() {
        val posPayload = GlobalPositionInt.builder()
            .timeBootMs(12345L)
            .lat(377749000) // 37.7749 deg
            .lon(-1224194000) // -122.4194 deg
            .alt(50000) // 50m
            .relativeAlt(15000) // 15m
            .vx(1000) // 10 m/s
            .vy(0)
            .vz(0)
            .hdg(9000)
            .build()

        val validPacket = codec.encodeMavlink2(1, 1, 6, posPayload)
        // Prepend random garbage bytes
        val garbage = byteArrayOf(0x55, 0xAA.toByte(), 0x12, 0x34, 0x99.toByte(), 0x00, 0xFE.toByte(), 0x01)
        val noisyStream = garbage + validPacket

        val frames = codec.feedBytes(noisyStream)
        assertEquals(1, frames.size)
        assertTrue(frames[0] is TelemetryFrame.Position)
        val pos = (frames[0] as TelemetryFrame.Position).value
        assertEquals(37.7749, pos.latDeg, 0.0001)
        assertEquals(-122.4194, pos.lonDeg, 0.0001)
        assertEquals(50.0f, pos.altitudeM, 0.01f)
        assertEquals(10.0f, pos.groundSpeedMps, 0.01f)
    }

    @Test
    fun testFragmentedRead() {
        val posPayload = GlobalPositionInt.builder()
            .timeBootMs(12345L)
            .lat(400000000)
            .lon(-800000000)
            .alt(30000)
            .relativeAlt(10000)
            .vx(0)
            .vy(0)
            .vz(0)
            .hdg(0)
            .build()

        val validPacket = codec.encodeMavlink2(1, 1, 7, posPayload)
        val chunk1 = validPacket.copyOfRange(0, 15)
        val chunk2 = validPacket.copyOfRange(15, validPacket.size)

        val frames1 = codec.feedBytes(chunk1)
        assertEquals(0, frames1.size) // Incomplete, should yield no frames

        val frames2 = codec.feedBytes(chunk2)
        assertEquals(1, frames2.size) // Now complete!
        assertTrue(frames2[0] is TelemetryFrame.Position)
    }

    @Test
    fun testConcatenatedPackets() {
        val hb = Heartbeat.builder()
            .type(EnumValue.of(MavType.MAV_TYPE_QUADROTOR))
            .autopilot(EnumValue.of(MavAutopilot.MAV_AUTOPILOT_GENERIC))
            .baseMode(EnumValue.of(MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED))
            .systemStatus(EnumValue.of(MavState.MAV_STATE_ACTIVE))
            .build()

        val home = HomePosition.builder()
            .latitude(401234567)
            .longitude(-801234567)
            .altitude(120000)
            .build()

        val packet1 = codec.encodeMavlink2(1, 1, 8, hb)
        val packet2 = codec.encodeMavlink2(1, 1, 9, home)
        val combined = packet1 + packet2

        val frames = codec.feedBytes(combined)
        assertEquals(2, frames.size)
        assertTrue(frames[0] is TelemetryFrame.VehicleState)
        assertTrue(frames[1] is TelemetryFrame.Home)
    }

    @Test
    fun testSystemIdFiltering() {
        val hb = Heartbeat.builder()
            .type(EnumValue.of(MavType.MAV_TYPE_QUADROTOR))
            .autopilot(EnumValue.of(MavAutopilot.MAV_AUTOPILOT_GENERIC))
            .baseMode(EnumValue.of(MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED))
            .systemStatus(EnumValue.of(MavState.MAV_STATE_ACTIVE))
            .build()

        // Sent with systemId = 2 (our codec is configured for systemId = 1)
        val packetOtherVehicle = codec.encodeMavlink2(2, 1, 10, hb)
        val frames = codec.feedBytes(packetOtherVehicle)

        assertEquals(0, frames.size)
        assertEquals(1L, codec.diagnostics.systemIdMismatches)
    }

    @Test
    fun testCommandAckParsing() {
        val ack = CommandAck.builder()
            .command(EnumValue.of(io.dronefleet.mavlink.common.MavCmd.MAV_CMD_DO_SPRAYER))
            .result(EnumValue.of(io.dronefleet.mavlink.common.MavResult.MAV_RESULT_ACCEPTED))
            .progress(100)
            .targetSystem(1)
            .targetComponent(1)
            .build()

        val packet = codec.encodeMavlink2(1, 1, 11, ack)
        val frames = codec.feedBytes(packet)

        assertEquals(1, frames.size)
        assertTrue(frames[0] is TelemetryFrame.CommandResult)
        val res = (frames[0] as TelemetryFrame.CommandResult).value
        assertEquals(ack.command().value(), res.commandId)
        assertEquals(CommandAckResult.ACCEPTED, res.result)
    }
}
