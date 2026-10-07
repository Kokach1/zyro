package com.exodia.batteryalert.core.transport

import com.exodia.batteryalert.core.model.*
import io.dronefleet.mavlink.annotations.MavlinkMessageInfo
import io.dronefleet.mavlink.common.*
import io.dronefleet.mavlink.minimal.Heartbeat
import io.dronefleet.mavlink.minimal.MavModeFlag
import io.dronefleet.mavlink.minimal.MavType
import io.dronefleet.mavlink.protocol.MavlinkPacket
import io.dronefleet.mavlink.serialization.payload.reflection.ReflectionPayloadDeserializer
import io.dronefleet.mavlink.serialization.payload.reflection.ReflectionPayloadSerializer
import java.io.ByteArrayOutputStream
import kotlin.math.hypot

/**
 * Configuration for the MAVLink codec.
 */
data class MavlinkCodecConfig(
    val targetSystemId: Int = 1,
    val targetComponentId: Int = 0, // 0 = accept any component
    val requireSignature: Boolean = false,
    val secretKey: ByteArray? = null
)

/**
 * Diagnostics and telemetry statistics tracked by [MavlinkCodec].
 */
data class MavlinkDiagnostics(
    var bytesReceived: Long = 0,
    var framesParsed: Long = 0,
    var crcErrors: Long = 0,
    var signatureErrors: Long = 0,
    var unhandledMessages: Long = 0,
    var systemIdMismatches: Long = 0,
    var droppedBytes: Long = 0,
    var lastValidPacketTimeMs: Long = 0,
    var lastBatteryUpdateTimeMs: Long = 0,
    var lastPositionUpdateTimeMs: Long = 0,
    var lastHeartbeatTimeMs: Long = 0
)

/**
 * High-performance, streaming MAVLink 1 and MAVLink 2 codec.
 *
 * Implements:
 *  - Frame validation with CRC_EXTRA for all standard common dialect messages.
 *  - Automatic resynchronization when receiving noise or garbage bytes.
 *  - Fragmented byte assembly and concatenated packet demuxing.
 *  - Support for 14S batteries (voltages 1..10 + voltagesExt 11..14 with cell=1 near-zero sentinel).
 *  - Aggregate pack detection (>65534 mV split / slot-0-only aggregate).
 *  - SYS_STATUS fallback, GLOBAL_POSITION_INT, HEARTBEAT (armed status), HOME_POSITION, and COMMAND_ACK.
 *  - Error counters and bounded buffer memory to prevent memory leaks.
 */
class MavlinkCodec(
    private val config: MavlinkCodecConfig = MavlinkCodecConfig()
) {
    companion object {
        const val MAGIC_V1 = 0xFE
        const val MAGIC_V2 = 0xFD
        const val MAX_BUFFER_SIZE = 65536
    }

    private val dialect = CommonDialect()
    private val deserializer = ReflectionPayloadDeserializer()
    private val serializer = ReflectionPayloadSerializer()

    private val accumulator = ByteArrayOutputStream(4096)
    val diagnostics = MavlinkDiagnostics()

    /**
     * Feeds incoming byte data into the stream parser.
     * Returns all successfully decoded and verified [TelemetryFrame] instances.
     */
    @Synchronized
    fun feedBytes(
        data: ByteArray,
        length: Int = data.size,
        nowMs: Long = System.currentTimeMillis()
    ): List<TelemetryFrame> {
        diagnostics.bytesReceived += length

        // Bounded memory check: if buffer grows too large due to continuous corrupt data without STX
        if (accumulator.size() + length > MAX_BUFFER_SIZE) {
            diagnostics.droppedBytes += accumulator.size()
            accumulator.reset()
        }

        accumulator.write(data, 0, length)
        val buffer = accumulator.toByteArray()
        val results = mutableListOf<TelemetryFrame>()

        var cursor = 0
        while (cursor < buffer.size) {
            val magic = buffer[cursor].toInt() and 0xFF

            if (magic == MAGIC_V1) {
                // MAVLink 1 packet: 6 bytes header + payloadLen + 2 bytes CRC = 8 + payloadLen
                if (buffer.size - cursor < 8) {
                    // Incomplete header, wait for more bytes
                    break
                }
                val payloadLen = buffer[cursor + 1].toInt() and 0xFF
                val totalLen = 8 + payloadLen
                if (buffer.size - cursor < totalLen) {
                    // Incomplete payload, wait for more bytes
                    break
                }

                val frameBytes = buffer.copyOfRange(cursor, cursor + totalLen)
                val packet = try {
                    MavlinkPacket.fromV1Bytes(frameBytes)
                } catch (e: Exception) {
                    null
                }

                if (packet != null && isValidPacketCrc(packet)) {
                    val frame = processPacket(packet, nowMs)
                    if (frame != null) results.add(frame)
                    cursor += totalLen
                } else {
                    if (packet != null) diagnostics.crcErrors++
                    diagnostics.droppedBytes++
                    cursor++
                }
            } else if (magic == MAGIC_V2) {
                // MAVLink 2 packet: 10 bytes header + payloadLen + 2 bytes CRC [+ 13 signature]
                if (buffer.size - cursor < 10) {
                    // Incomplete header, wait for more bytes
                    break
                }
                val payloadLen = buffer[cursor + 1].toInt() and 0xFF
                val incompatFlags = buffer[cursor + 2].toInt() and 0xFF
                val isSigned = (incompatFlags and 0x01) != 0
                val totalLen = 12 + payloadLen + (if (isSigned) 13 else 0)

                if (buffer.size - cursor < totalLen) {
                    // Incomplete frame, wait for more bytes
                    break
                }

                val frameBytes = buffer.copyOfRange(cursor, cursor + totalLen)
                val packet = try {
                    MavlinkPacket.fromV2Bytes(frameBytes)
                } catch (e: Exception) {
                    null
                }

                if (packet != null && isValidPacketCrc(packet)) {
                    val frame = processPacket(packet, nowMs)
                    if (frame != null) results.add(frame)
                    cursor += totalLen
                } else {
                    if (packet != null) diagnostics.crcErrors++
                    diagnostics.droppedBytes++
                    cursor++
                }
            } else {
                // Garbage byte before magic byte — drop 1 byte to resynchronize
                diagnostics.droppedBytes++
                cursor++
            }
        }

        // Keep remaining unparsed bytes in accumulator
        accumulator.reset()
        if (cursor < buffer.size) {
            accumulator.write(buffer, cursor, buffer.size - cursor)
        }

        return results
    }

    fun isValidPacketCrc(packet: MavlinkPacket): Boolean {
        val messageId = packet.messageId
        if (!dialect.supports(messageId)) return false
        val messageClass = dialect.resolve(messageId)
        val messageInfo = messageClass?.getAnnotation(MavlinkMessageInfo::class.java) as? MavlinkMessageInfo ?: return false
        return packet.validateCrc(messageInfo.crc)
    }

    /**
     * Validates and processes a parsed [MavlinkPacket].
     */
    fun processPacket(packet: MavlinkPacket, nowMs: Long = System.currentTimeMillis()): TelemetryFrame? {
        val messageId = packet.messageId
        if (!dialect.supports(messageId)) {
            diagnostics.unhandledMessages++
            return null
        }

        val messageClass = dialect.resolve(messageId)
        val messageInfo = messageClass?.getAnnotation(MavlinkMessageInfo::class.java) as? MavlinkMessageInfo
        if (messageInfo == null) {
            diagnostics.unhandledMessages++
            return null
        }

        // Validate CRC_EXTRA
        if (!packet.validateCrc(messageInfo.crc)) {
            diagnostics.crcErrors++
            return null
        }

        // Check signature if signed or required
        if (packet.isSigned) {
            if (config.secretKey != null) {
                if (!packet.validateSignature(config.secretKey)) {
                    diagnostics.signatureErrors++
                    return null
                }
            }
        } else if (config.requireSignature) {
            diagnostics.signatureErrors++
            return null
        }

        // Check system ID filter (0 matches any)
        if (config.targetSystemId != 0 && packet.systemId != config.targetSystemId) {
            diagnostics.systemIdMismatches++
            return null
        }

        // Check component ID filter if set
        if (config.targetComponentId != 0 && packet.componentId != config.targetComponentId) {
            return null
        }

        diagnostics.framesParsed++
        diagnostics.lastValidPacketTimeMs = nowMs

        val payload = try {
            deserializer.deserialize(packet.payload, messageClass)
        } catch (e: Exception) {
            diagnostics.unhandledMessages++
            return null
        }

        return when (payload) {
            is BatteryStatus -> mapBatteryStatus(payload, packet, nowMs)
            is SysStatus -> mapSysStatus(payload, packet, nowMs)
            is GlobalPositionInt -> mapGlobalPositionInt(payload, packet, nowMs)
            is Heartbeat -> mapHeartbeat(payload, packet, nowMs)
            is HomePosition -> mapHomePosition(payload, packet, nowMs)
            is CommandAck -> mapCommandAck(payload, packet, nowMs)
            else -> {
                diagnostics.unhandledMessages++
                null
            }
        }
    }

    private fun mapBatteryStatus(
        msg: BatteryStatus,
        packet: MavlinkPacket,
        nowMs: Long
    ): TelemetryFrame.Battery {
        diagnostics.lastBatteryUpdateTimeMs = nowMs

        val rawVoltages = msg.voltages() ?: emptyList()
        val rawExt = msg.voltagesExt() ?: emptyList()

        val slot0 = if (rawVoltages.isNotEmpty()) rawVoltages[0] else 65535
        val allOtherBaseMissing = rawVoltages.drop(1).all { it == 65535 }
        val allExtMissingOrZero = rawExt.all { it == 0 || it == 65535 }

        val isUnsupportedCells = (slot0 == 0 && allOtherBaseMissing && allExtMissingOrZero)
        val isPureAggregateSlot0 = (slot0 in 1..65534 && allOtherBaseMissing && allExtMissingOrZero && slot0 > 10000)

        val cellVoltages = mutableListOf<Float>()
        val packVoltage: Float?
        val isAggregate: Boolean

        if (isUnsupportedCells) {
            isAggregate = true
            packVoltage = null
        } else if (isPureAggregateSlot0) {
            isAggregate = true
            packVoltage = slot0 / 1000f
        } else {
            var lastActiveIndex = -1
            for (i in 0 until minOf(10, rawVoltages.size)) {
                val v = rawVoltages[i]
                if (v in 0 until 65535) {
                    lastActiveIndex = maxOf(lastActiveIndex, i)
                }
            }
            for (j in 0 until minOf(4, rawExt.size)) {
                val v = rawExt[j]
                if (v in 1 until 65535) {
                    lastActiveIndex = maxOf(lastActiveIndex, 10 + j)
                }
            }

            if (lastActiveIndex == -1) {
                isAggregate = true
                packVoltage = if (slot0 in 1..65534) slot0 / 1000f else null
            } else {
                isAggregate = false
                for (idx in 0..lastActiveIndex) {
                    if (idx < 10) {
                        val v = if (idx < rawVoltages.size) rawVoltages[idx] else 65535
                        if (v == 65535) {
                            cellVoltages.add(Float.NaN)
                        } else {
                            cellVoltages.add(v / 1000f)
                        }
                    } else {
                        val extIdx = idx - 10
                        val v = if (extIdx < rawExt.size) rawExt[extIdx] else 65535
                        if (v == 0 || v == 65535) {
                            cellVoltages.add(Float.NaN)
                        } else if (v == 1) {
                            cellVoltages.add(0.001f)
                        } else {
                            cellVoltages.add(v / 1000f)
                        }
                    }
                }
                val validCells = cellVoltages.filter { !it.isNaN() }
                packVoltage = if (validCells.isNotEmpty()) validCells.sum() else if (slot0 in 1..65534) slot0 / 1000f else null
            }
        }

        val temp = if (msg.temperature() != 32767 && msg.temperature() != -32768) msg.temperature() / 100f else null
        val current = if (msg.currentBattery() != -1) msg.currentBattery() / 100f else null
        val percent = if (msg.batteryRemaining() in 0..100) msg.batteryRemaining() else null
        val consumed = if (msg.currentConsumed() != -1 && msg.currentConsumed() >= 0) msg.currentConsumed().toFloat() else null

        val frame = BatteryFrame(
            timestampMs = nowMs,
            cellVoltagesV = cellVoltages,
            packVoltageV = packVoltage,
            currentA = current,
            consumedMah = consumed,
            temperatureC = temp,
            remainingPercent = percent,
            batteryId = msg.id(),
            isAggregateOnly = isAggregate,
            systemId = packet.systemId,
            componentId = packet.componentId,
            faultBitmask = msg.faultBitmask()?.value()?.toLong() ?: 0L,
            isCurrentKnown = (current != null),
            isCellsFresh = !isAggregate && cellVoltages.any { !it.isNaN() }
        )
        return TelemetryFrame.Battery(frame)
    }

    private fun mapSysStatus(
        msg: SysStatus,
        packet: MavlinkPacket,
        nowMs: Long
    ): TelemetryFrame.Battery? {
        val voltMv = msg.voltageBattery()
        val currCa = msg.currentBattery()
        val remPct = msg.batteryRemaining()

        if (voltMv == 65535 && currCa == -1 && remPct == -1) {
            return null // No battery information in SYS_STATUS
        }

        val packV = if (voltMv != 65535) voltMv / 1000f else null
        val current = if (currCa != -1) currCa / 100f else null
        val percent = if (remPct in 0..100) remPct else null

        val frame = BatteryFrame(
            timestampMs = nowMs,
            cellVoltagesV = emptyList(),
            packVoltageV = packV,
            currentA = current,
            remainingPercent = percent,
            batteryId = 0,
            isAggregateOnly = true,
            systemId = packet.systemId,
            componentId = packet.componentId,
            isCurrentKnown = (current != null),
            isFromFallback = true
        )
        return TelemetryFrame.Battery(frame)
    }

    private fun mapGlobalPositionInt(
        msg: GlobalPositionInt,
        packet: MavlinkPacket,
        nowMs: Long
    ): TelemetryFrame.Position? {
        val lat = msg.lat() / 1e7
        val lon = msg.lon() / 1e7

        // Range sanity validation:
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            return null
        }

        diagnostics.lastPositionUpdateTimeMs = nowMs

        val alt = msg.alt() / 1000f
        val relAlt = msg.relativeAlt() / 1000f
        val vx = msg.vx() / 100f
        val vy = msg.vy() / 100f
        val groundSpeed = hypot(vx, vy)

        val frame = PositionFrame(
            timestampMs = nowMs,
            latDeg = lat,
            lonDeg = lon,
            altitudeM = alt,
            relativeAltitudeM = relAlt,
            groundSpeedMps = groundSpeed,
            systemId = packet.systemId,
            componentId = packet.componentId
        )
        return TelemetryFrame.Position(frame)
    }

    private fun mapHeartbeat(
        msg: Heartbeat,
        packet: MavlinkPacket,
        nowMs: Long
    ): TelemetryFrame.VehicleState {
        diagnostics.lastHeartbeatTimeMs = nowMs

        val isArmed = msg.baseMode()?.flagsEnabled(MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED) == true
        val isAutopilot = msg.type()?.entry() != MavType.MAV_TYPE_GCS

        val frame = VehicleStateFrame(
            timestampMs = nowMs,
            systemId = packet.systemId,
            componentId = packet.componentId,
            isArmed = isArmed,
            autopilotType = msg.autopilot()?.value() ?: 0,
            systemStatus = msg.systemStatus()?.value() ?: 0,
            isAutopilot = isAutopilot
        )
        return TelemetryFrame.VehicleState(frame)
    }

    private fun mapHomePosition(
        msg: HomePosition,
        packet: MavlinkPacket,
        nowMs: Long
    ): TelemetryFrame.Home? {
        val lat = msg.latitude() / 1e7
        val lon = msg.longitude() / 1e7

        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            return null
        }

        val frame = HomeFrame(
            timestampMs = nowMs,
            latDeg = lat,
            lonDeg = lon,
            altitudeM = msg.altitude() / 1000f,
            source = HomeSource.AUTOPILOT_HOME,
            isValid = true
        )
        return TelemetryFrame.Home(frame)
    }

    private fun mapCommandAck(
        msg: CommandAck,
        packet: MavlinkPacket,
        nowMs: Long
    ): TelemetryFrame.CommandResult {
        val result = when (msg.result()?.entry()) {
            MavResult.MAV_RESULT_ACCEPTED -> CommandAckResult.ACCEPTED
            MavResult.MAV_RESULT_TEMPORARILY_REJECTED -> CommandAckResult.TEMPORARILY_REJECTED
            MavResult.MAV_RESULT_DENIED -> CommandAckResult.DENIED
            MavResult.MAV_RESULT_UNSUPPORTED -> CommandAckResult.UNSUPPORTED
            MavResult.MAV_RESULT_FAILED -> CommandAckResult.FAILED
            MavResult.MAV_RESULT_IN_PROGRESS -> CommandAckResult.IN_PROGRESS
            else -> CommandAckResult.FAILED
        }

        val frame = CommandResultFrame(
            timestampMs = nowMs,
            commandId = msg.command()?.value() ?: 0,
            result = result,
            progress = msg.progress(),
            targetSystem = msg.targetSystem(),
            targetComponent = msg.targetComponent()
        )
        return TelemetryFrame.CommandResult(frame)
    }

    // ── Synthetic / Outbound Packet Serialization Helpers (for tests and sender) ──

    /**
     * Serializes any supported MAVLink payload into a valid MAVLink 2 packet byte array.
     */
    fun <T : Any> encodeMavlink2(
        systemId: Int,
        componentId: Int,
        sequence: Int,
        payload: T
    ): ByteArray {
        val messageInfo = payload::class.java.getAnnotation(MavlinkMessageInfo::class.java)
            ?: throw IllegalArgumentException("Class ${payload::class.java.name} is missing @MavlinkMessageInfo")
        val payloadBytes = serializer.serialize(payload)
        val packet = MavlinkPacket.createUnsignedMavlink2Packet(
            sequence,
            systemId,
            componentId,
            messageInfo.id,
            messageInfo.crc,
            payloadBytes
        )
        return packet.rawBytes
    }

    /**
     * Serializes any supported MAVLink payload into a valid MAVLink 1 packet byte array.
     */
    fun <T : Any> encodeMavlink1(
        systemId: Int,
        componentId: Int,
        sequence: Int,
        payload: T
    ): ByteArray {
        val messageInfo = payload::class.java.getAnnotation(MavlinkMessageInfo::class.java)
            ?: throw IllegalArgumentException("Class ${payload::class.java.name} is missing @MavlinkMessageInfo")
        val payloadBytes = serializer.serialize(payload)
        val packet = MavlinkPacket.createMavlink1Packet(
            sequence,
            systemId,
            componentId,
            messageInfo.id,
            messageInfo.crc,
            payloadBytes
        )
        return packet.rawBytes
    }
}
