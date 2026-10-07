package com.exodia.batteryalert.core.transport

import com.exodia.batteryalert.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Replay transport for scoped JSONL and timestamp-framed MAVLink .tlog files.
 *
 * Requirements from DAY2_SPEC / Step 07:
 *   - Detect JSONL vs .tlog by file extension / content.
 *   - For .tlog: read 8-byte big-endian microsecond timestamp prefix, then MAVLink packet.
 *     Feed packet bytes to [MavlinkCodec].
 *   - For JSONL: parse structured domain frames line by line.
 *   - Virtual clock: respects inter-frame delays (scaled by [speedMultiplier]).
 *   - Label: always shows REPLAY [DEMO] in UI, never LIVE.
 *   - Hardware writes / pump commands are strictly suppressed in replay mode.
 */
class ReplayTransport(
    val filePath: String,
    val vehicleSystemId: Int = 1,
    var speedMultiplier: Int = 1,
    var loop: Boolean = false
) : TelemetryTransport {

    override val id = "replay"
    override val displayName = "Replay [DEMO]"

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _frames = MutableSharedFlow<TelemetryFrame>(replay = 1, extraBufferCapacity = 64)
    override val frames: Flow<TelemetryFrame> = _frames.asSharedFlow()

    val codec = MavlinkCodec(MavlinkCodecConfig(targetSystemId = vehicleSystemId))

    private var job: Job? = null

    override suspend fun start() {
        if (job?.isActive == true) return

        val file = File(filePath)
        if (!file.exists()) {
            _connectionState.value = ConnectionState.Error("Replay: file not found at $filePath")
            return
        }

        _connectionState.value = ConnectionState.Connecting

        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (file.name.endsWith(".tlog", ignoreCase = true)) {
                replayTlog(file)
            } else {
                replayJsonl(file)
            }
        }
    }

    private suspend fun replayTlog(file: File) {
        withContext(Dispatchers.IO) {
            try {
                do {
                    FileInputStream(file).use { input ->
                        val timeHeader = ByteArray(8)
                        val headerBuffer = ByteBuffer.wrap(timeHeader).order(ByteOrder.BIG_ENDIAN)

                        var lastUsec = 0L

                        while (isActive) {
                            var read = 0
                            while (read < 8) {
                                val n = input.read(timeHeader, read, 8 - read)
                                if (n < 0) break
                                read += n
                            }
                            if (read < 8) break // EOF

                            headerBuffer.rewind()
                            val usec = headerBuffer.long

                            if (lastUsec > 0L && usec > lastUsec) {
                                val deltaMs = (usec - lastUsec) / 1000L
                                val delayMs = (deltaMs / speedMultiplier.coerceAtLeast(1)).coerceIn(10L, 2000L)
                                delay(delayMs)
                            }
                            lastUsec = usec

                            // Read MAVLink packet: magic byte first
                            val magic = input.read()
                            if (magic < 0) break

                            val packetBytes = when (magic) {
                                0xFE -> {
                                    val len = input.read()
                                    if (len < 0) break
                                    val rest = ByteArray(len + 6)
                                    input.read(rest)
                                    byteArrayOf(0xFE.toByte(), len.toByte()) + rest
                                }
                                0xFD -> {
                                    val len = input.read()
                                    val incompat = input.read()
                                    if (len < 0 || incompat < 0) break
                                    val isSigned = (incompat and 0x01) != 0
                                    val restLen = 9 + len + (if (isSigned) 13 else 0)
                                    val rest = ByteArray(restLen)
                                    input.read(rest)
                                    byteArrayOf(0xFD.toByte(), len.toByte(), incompat.toByte()) + rest
                                }
                                else -> continue
                            }

                            val nowMs = System.currentTimeMillis()
                            val decoded = codec.feedBytes(packetBytes, packetBytes.size, nowMs)
                            for (frame in decoded) {
                                if (frame is TelemetryFrame.Battery && _connectionState.value !is ConnectionState.Connected) {
                                    _connectionState.value = ConnectionState.Connected
                                }
                                _frames.emit(frame)
                            }
                        }
                    }
                } while (loop && isActive)
            } catch (e: Exception) {
                if (isActive) {
                    _connectionState.value = ConnectionState.Error("Replay .tlog error: ${e.message}")
                }
            }
        }
    }

    private suspend fun replayJsonl(file: File) {
        withContext(Dispatchers.IO) {
            try {
                do {
                    file.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (!isActive) break
                            val trimmed = line.trim()
                            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                            val frame = parseJsonlLine(trimmed)
                            if (frame != null) {
                                if (frame is TelemetryFrame.Battery && _connectionState.value !is ConnectionState.Connected) {
                                    _connectionState.value = ConnectionState.Connected
                                }
                                _frames.emit(frame)
                                delay((250L / speedMultiplier.coerceAtLeast(1)).coerceAtLeast(10L))
                            }
                        }
                    }
                } while (loop && isActive)
            } catch (e: Exception) {
                if (isActive) {
                    _connectionState.value = ConnectionState.Error("Replay JSONL error: ${e.message}")
                }
            }
        }
    }

    private fun parseJsonlLine(line: String): TelemetryFrame? {
        // Lightweight pure-Kotlin JSON parser without external JSON dependencies in core
        try {
            if (line.contains("\"type\":\"battery\"") || line.contains("\"cellVoltages\"") || line.contains("\"cells\"")) {
                val nowMs = System.currentTimeMillis()
                val cells = extractFloatArray(line, "cells") ?: extractFloatArray(line, "cellVoltages") ?: emptyList()
                val packV = extractFloat(line, "pack_v") ?: extractFloat(line, "packVoltage") ?: cells.sum()
                val currentA = extractFloat(line, "current") ?: extractFloat(line, "currentA") ?: 0f
                val percent = extractInt(line, "remainingPercent") ?: extractInt(line, "percent")
                val temp = extractFloat(line, "temperature") ?: extractFloat(line, "temperatureC")

                return TelemetryFrame.Battery(
                    BatteryFrame(
                        timestampMs = nowMs,
                        cellVoltagesV = cells,
                        packVoltageV = packV,
                        currentA = currentA,
                        temperatureC = temp,
                        remainingPercent = percent,
                        isAggregateOnly = cells.isEmpty()
                    )
                )
            } else if (line.contains("\"type\":\"position\"") || line.contains("\"lat\"")) {
                val nowMs = System.currentTimeMillis()
                val lat = extractDouble(line, "lat") ?: 0.0
                val lon = extractDouble(line, "lon") ?: 0.0
                val alt = extractFloat(line, "alt") ?: 0f
                val speed = extractFloat(line, "speed") ?: 0f

                return TelemetryFrame.Position(
                    PositionFrame(
                        timestampMs = nowMs,
                        latDeg = lat,
                        lonDeg = lon,
                        altitudeM = alt,
                        relativeAltitudeM = alt,
                        groundSpeedMps = speed
                    )
                )
            }
        } catch (ignored: Exception) {}
        return null
    }

    private fun extractFloat(json: String, key: String): Float? {
        val regex = Regex("\"$key\"\\s*:\\s*([0-9.-]+)")
        return regex.find(json)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun extractDouble(json: String, key: String): Double? {
        val regex = Regex("\"$key\"\\s*:\\s*([0-9.-]+)")
        return regex.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun extractInt(json: String, key: String): Int? {
        val regex = Regex("\"$key\"\\s*:\\s*([0-9-]+)")
        return regex.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractFloatArray(json: String, key: String): List<Float>? {
        val regex = Regex("\"$key\"\\s*:\\s*\\[([0-9.,\\s-]+)\\]")
        val match = regex.find(json) ?: return null
        return match.groupValues[1].split(",").mapNotNull { it.trim().toFloatOrNull() }
    }

    override suspend fun stop() {
        job?.cancel()
        job = null
        _connectionState.value = ConnectionState.Disconnected
    }
}
