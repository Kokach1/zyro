package com.exodia.batteryalert

import com.exodia.batteryalert.core.model.*
import com.exodia.batteryalert.core.telemetry.BatteryStateAccumulator
import com.exodia.batteryalert.core.transport.MavlinkCodec
import com.exodia.batteryalert.core.transport.MavlinkCodecConfig
import io.dronefleet.mavlink.common.BatteryStatus
import io.dronefleet.mavlink.common.SysStatus
import org.junit.Assert.*
import org.junit.Test

class CodecAndMergeTest {

    private val codec = MavlinkCodec(MavlinkCodecConfig(targetSystemId = 1, targetComponentId = 0))

    @Test
    fun mavlink1And2EncodedPacketsAndFragments() {
        val payload = BatteryStatus.builder()
            .id(0)
            .voltages(listOf(3800, 3810, 3790) + List(7) { 65535 })
            .currentBattery(1500) // 15.00 A
            .batteryRemaining(75)
            .temperature(2500) // 25.0 C
            .build()

        val v2Bytes = codec.encodeMavlink2(systemId = 1, componentId = 1, sequence = 0, payload = payload)
        assertTrue(v2Bytes.isNotEmpty())

        // Feed v2 in 2-byte fragments
        val framesV2 = mutableListOf<TelemetryFrame>()
        var offset = 0
        while (offset < v2Bytes.size) {
            val chunk = v2Bytes.copyOfRange(offset, minOf(offset + 3, v2Bytes.size))
            framesV2 += codec.feedBytes(chunk)
            offset += chunk.size
        }
        assertEquals(1, framesV2.size)
        val b2 = (framesV2.first() as TelemetryFrame.Battery).value
        assertEquals(75, b2.remainingPercent)
        assertEquals(15.0f, b2.currentA ?: 0f, 0.01f)

        // Feed v1 packet
        val v1Bytes = codec.encodeMavlink1(systemId = 1, componentId = 1, sequence = 1, payload = payload)
        val framesV1 = codec.feedBytes(v1Bytes)
        assertEquals(1, framesV1.size)
    }

    @Test
    fun crcFailureAndResynchronization() {
        val payload = BatteryStatus.builder()
            .id(0)
            .voltages(listOf(3800) + List(9) { 65535 })
            .build()
        val validBytes = codec.encodeMavlink2(systemId = 1, componentId = 1, sequence = 2, payload = payload)

        // Prepend 10 bytes of arbitrary noise
        val garbage = byteArrayOf(0x55, 0xAA.toByte(), 0x12, 0x34, 0xFE.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05)
        val corrupted = validBytes.clone()
        // Corrupt CRC byte
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1].toInt() xor 0xFF).toByte()

        val stream = garbage + corrupted + validBytes
        val frames = codec.feedBytes(stream)

        assertEquals(1, frames.size)
        assertTrue(codec.diagnostics.crcErrors > 0 || codec.diagnostics.droppedBytes > 0)
    }

    @Test
    fun unitsAndSentinels() {
        // -1 current, -1 remaining, 32767 temp, -1 consumed
        val sentinels = BatteryStatus.builder()
            .id(0)
            .voltages(List(10) { 65535 })
            .currentBattery(-1)
            .batteryRemaining(-1)
            .temperature(32767)
            .currentConsumed(-1)
            .build()
        val bytes = codec.encodeMavlink2(systemId = 1, componentId = 1, sequence = 0, payload = sentinels)
        val frame = (codec.feedBytes(bytes).first() as TelemetryFrame.Battery).value

        assertNull(frame.currentA)
        assertFalse(frame.isCurrentKnown)
        assertNull(frame.remainingPercent)
        assertNull(frame.temperatureC)
        assertNull(frame.consumedMah)

        // Legitimate zero values: 0A current, 0% remaining
        val zeros = BatteryStatus.builder()
            .id(0)
            .voltages(listOf(3800) + List(9) { 65535 })
            .currentBattery(0)
            .batteryRemaining(0)
            .temperature(0)
            .currentConsumed(0)
            .build()
        val zeroBytes = codec.encodeMavlink2(systemId = 1, componentId = 1, sequence = 1, payload = zeros)
        val zeroFrame = (codec.feedBytes(zeroBytes).first() as TelemetryFrame.Battery).value

        assertEquals(0.0f, zeroFrame.currentA ?: -1f, 0.001f)
        assertTrue(zeroFrame.isCurrentKnown)
        assertEquals(0, zeroFrame.remainingPercent)
        assertEquals(0.0f, zeroFrame.temperatureC ?: -1f, 0.001f)
        assertEquals(0.0f, zeroFrame.consumedMah ?: -1f, 0.001f)
    }

    @Test
    fun zeroBaseCellAndExtensionNearZero() {
        // Base cell 0 is measured 0V (dead cell fault) alongside active cells
        val deadCellPayload = BatteryStatus.builder()
            .id(0)
            .voltages(listOf(0, 3800, 3800) + List(7) { 65535 })
            .voltagesExt(listOf(1, 3800, 65535, 65535)) // ext index 0 has 1 (near-zero sentinel)
            .build()

        val bytes = codec.encodeMavlink2(systemId = 1, componentId = 1, sequence = 0, payload = deadCellPayload)
        val frame = (codec.feedBytes(bytes).first() as TelemetryFrame.Battery).value

        assertFalse(frame.isAggregateOnly)
        assertEquals(12, frame.cellVoltagesV.size)
        // Base slot 0 is preserved as 0.0V (not dropped!)
        assertEquals(0.0f, frame.cellVoltagesV[0], 0.0001f)
        assertEquals(3.8f, frame.cellVoltagesV[1], 0.01f)
        // Ext slot 0 (index 10) is preserved as 0.001V
        assertEquals(0.001f, frame.cellVoltagesV[10], 0.0001f)
        assertEquals(3.8f, frame.cellVoltagesV[11], 0.01f)
    }

    @Test
    fun missingCPositionsDoNotCompress() {
        // Cells 0, 1, 3 are present; cell 2 is 65535 (missing in middle)
        val holePayload = BatteryStatus.builder()
            .id(0)
            .voltages(listOf(3800, 3820, 65535, 3810) + List(6) { 65535 })
            .build()

        val bytes = codec.encodeMavlink2(systemId = 1, componentId = 1, sequence = 0, payload = holePayload)
        val frame = (codec.feedBytes(bytes).first() as TelemetryFrame.Battery).value

        assertEquals(4, frame.cellVoltagesV.size)
        assertEquals(3.80f, frame.cellVoltagesV[0], 0.01f)
        assertEquals(3.82f, frame.cellVoltagesV[1], 0.01f)
        assertTrue(frame.cellVoltagesV[2].isNaN()) // Middle slot missing!
        assertEquals(3.81f, frame.cellVoltagesV[3], 0.01f) // C4 did NOT compress into index 2!
    }

    @Test
    fun aggregatePackForms() {
        // Slot 0 large voltage with all other cells missing
        val aggPayload = BatteryStatus.builder()
            .id(0)
            .voltages(listOf(48000) + List(9) { 65535 })
            .build()
        val bytes = codec.encodeMavlink2(systemId = 1, componentId = 1, sequence = 0, payload = aggPayload)
        val frame = (codec.feedBytes(bytes).first() as TelemetryFrame.Battery).value

        assertTrue(frame.isAggregateOnly)
        assertTrue(frame.cellVoltagesV.isEmpty())
        assertEquals(48.0f, frame.packVoltageV ?: 0f, 0.01f)

        // SYS_STATUS aggregate
        val sysStatus = SysStatus.builder()
            .voltageBattery(24500)
            .currentBattery(1200)
            .batteryRemaining(60)
            .build()
        val sysBytes = codec.encodeMavlink2(systemId = 1, componentId = 1, sequence = 1, payload = sysStatus)
        val sysFrame = (codec.feedBytes(sysBytes).first() as TelemetryFrame.Battery).value

        assertTrue(sysFrame.isAggregateOnly)
        assertTrue(sysFrame.isFromFallback)
        assertEquals(24.5f, sysFrame.packVoltageV ?: 0f, 0.01f)
        assertEquals(12.0f, sysFrame.currentA ?: 0f, 0.01f)
        assertEquals(60, sysFrame.remainingPercent)
    }

    @Test
    fun componentAndSystemPolicy() {
        val strictCodec = MavlinkCodec(MavlinkCodecConfig(targetSystemId = 1, targetComponentId = 1))
        val payload = BatteryStatus.builder().id(0).voltages(listOf(3800) + List(9) { 65535 }).build()

        // Packet from system 2
        val sys2Bytes = strictCodec.encodeMavlink2(systemId = 2, componentId = 1, sequence = 0, payload = payload)
        val framesSys2 = strictCodec.feedBytes(sys2Bytes)
        assertTrue(framesSys2.isEmpty())
        assertEquals(1, strictCodec.diagnostics.systemIdMismatches)

        // Packet from wrong component
        val comp2Bytes = strictCodec.encodeMavlink2(systemId = 1, componentId = 2, sequence = 1, payload = payload)
        val framesComp2 = strictCodec.feedBytes(comp2Bytes)
        assertTrue(framesComp2.isEmpty())

        // Matching packet
        val matchBytes = strictCodec.encodeMavlink2(systemId = 1, componentId = 1, sequence = 2, payload = payload)
        val matchFrames = strictCodec.feedBytes(matchBytes)
        assertEquals(1, matchFrames.size)
    }

    @Test
    fun interleavedSysStatusFallbackPreservesPrimaryAges() {
        val accumulator = BatteryStateAccumulator(targetSystemId = 1)

        val primaryFrame = BatteryFrame(
            timestampMs = 1000L,
            cellVoltagesV = List(14) { 3.82f },
            packVoltageV = 53.48f,
            currentA = 20f,
            consumedMah = 500f,
            temperatureC = 35f,
            remainingPercent = 80,
            batteryId = 0,
            isFromFallback = false
        )

        val consolidated1 = accumulator.onBatteryFrame(primaryFrame, nowMonotonicMs = 1000L)
        assertNotNull(consolidated1)
        assertEquals(14, consolidated1!!.cellVoltagesV.size)
        assertEquals(35f, consolidated1.temperatureC ?: 0f, 0.1f)
        assertEquals(500f, consolidated1.consumedMah ?: 0f, 0.1f)
        assertFalse(consolidated1.isFromFallback)

        // Advance 2 seconds (well before 3s maxAge)
        val fallbackFrame = BatteryFrame(
            timestampMs = 3000L,
            cellVoltagesV = emptyList(),
            packVoltageV = 53.20f,
            currentA = 25f,
            remainingPercent = 78,
            batteryId = 0,
            isFromFallback = true
        )

        val consolidated2 = accumulator.onBatteryFrame(fallbackFrame, nowMonotonicMs = 3000L)
        assertNotNull(consolidated2)
        // Primary cells, temperature and consumed counter are NOT erased!
        assertEquals(14, consolidated2!!.cellVoltagesV.size)
        assertEquals(35f, consolidated2.temperatureC ?: 0f, 0.1f)
        assertEquals(500f, consolidated2.consumedMah ?: 0f, 0.1f)
        // Primary was fresh at 3000ms (1000ms + 2000ms <= 3000ms maxAge), so fallback did NOT overwrite primary packVoltage
        assertEquals(53.48f, consolidated2.packVoltageV ?: 0f, 0.01f)

        // Advance past 3s maxAge for primary (e.g. now = 5000ms, primary age is 4000ms > 3000ms)
        val fallbackFrame2 = BatteryFrame(
            timestampMs = 5000L,
            cellVoltagesV = emptyList(),
            packVoltageV = 52.80f,
            currentA = 30f,
            remainingPercent = 75,
            batteryId = 0,
            isFromFallback = true
        )
        val consolidated3 = accumulator.onBatteryFrame(fallbackFrame2, nowMonotonicMs = 5000L)
        assertNotNull(consolidated3)
        // Fallback fills stale pack voltage and current
        assertEquals(52.80f, consolidated3!!.packVoltageV ?: 0f, 0.01f)
        assertEquals(30f, consolidated3.currentA ?: 0f, 0.01f)
        // Primary cells are now expired / stale: isCellsFresh is false
        assertFalse(consolidated3.isCellsFresh)
    }

    @Test
    fun packAssociationIsolation() {
        val accumulator = BatteryStateAccumulator(targetSystemId = 1)

        val pack0 = BatteryFrame(
            timestampMs = 1000L,
            cellVoltagesV = List(6) { 3.80f },
            packVoltageV = 22.8f,
            currentA = 10f,
            batteryId = 0
        )
        val pack1 = BatteryFrame(
            timestampMs = 1000L,
            cellVoltagesV = List(12) { 3.70f },
            packVoltageV = 44.4f,
            currentA = 25f,
            batteryId = 1
        )

        accumulator.onBatteryFrame(pack0, nowMonotonicMs = 1000L)
        val res1 = accumulator.onBatteryFrame(pack1, nowMonotonicMs = 1000L)
        assertNotNull(res1)
        assertEquals(1, res1!!.batteryId)
        assertEquals(12, res1.cellVoltagesV.size)

        // Targeted accumulator only listens to pack 0
        val target0Acc = BatteryStateAccumulator(targetSystemId = 1, targetBatteryId = 0)
        assertNotNull(target0Acc.onBatteryFrame(pack0, 1000L))
        assertNull(target0Acc.onBatteryFrame(pack1, 1000L))
    }
}
