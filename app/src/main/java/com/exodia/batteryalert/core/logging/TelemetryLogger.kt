package com.exodia.batteryalert.core.logging

import com.exodia.batteryalert.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*

data class LogRecord(
    val schemaVersion: Int = 1,
    val utcTimeMs: Long,
    val monoTimeMs: Long,
    val sessionSource: String,
    val systemId: Int,
    val batteryId: Int,
    val cellVoltagesV: List<Float>,
    val packVoltageV: Float?,
    val currentA: Float?,
    val consumedMah: Float?,
    val remainingPercent: Int?,
    val temperatureC: Float?,
    val isStale: Boolean,
    val positionLat: Double?,
    val positionLon: Double?,
    val positionAltM: Float?,
    val positionAgeMs: Long?,
    val isPositionStale: Boolean,
    val alertLevel: String,
    val cellFault: Boolean,
    val pumpState: String?
)

/**
 * Continuous blackbox telemetry logger implementing company FR-5.3.
 *
 * Enforces (FIX-17):
 *  - Streaming versioned JSONL in app-private storage.
 *  - GPS mapped to latest valid fix with age; null when unavailable (no fake 0,0 coordinates).
 *  - Bounded memory queue (capacity 1000) with drop counter to prevent disk IO blocking.
 *  - Clean flush and close on session stop.
 */
class TelemetryLogger(
    private val outputStream: OutputStream,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val channel = Channel<LogRecord>(capacity = 1000)
    private val writer = BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8))
    private var isClosed = false

    var totalRecordsLogged = 0L
        private set
    var droppedRecordsCount = 0L
        private set

    private val readerJob = scope.launch {
        for (record in channel) {
            try {
                val json = formatJson(record)
                writer.write(json)
                writer.newLine()
                totalRecordsLogged++
            } catch (e: Exception) {
                droppedRecordsCount++
            }
        }
        try {
            writer.flush()
        } catch (e: Exception) {}
    }

    fun log(
        analysis: BatteryAnalysis?,
        batteryFrame: BatteryFrame?,
        positionFrame: PositionFrame?,
        isPositionStale: Boolean,
        sessionSource: SessionSource,
        alertLevel: AlertLevel,
        cellFault: Boolean,
        pumpState: String?,
        nowWallMs: Long = System.currentTimeMillis(),
        nowMonoMs: Long = System.nanoTime() / 1_000_000L
    ) {
        if (isClosed) return

        val bFrame = batteryFrame ?: return
        val posAge = if (positionFrame != null) (nowWallMs - positionFrame.timestampMs).coerceAtLeast(0) else null

        val record = LogRecord(
            schemaVersion = 1,
            utcTimeMs = nowWallMs,
            monoTimeMs = nowMonoMs,
            sessionSource = sessionSource.name,
            systemId = bFrame.systemId,
            batteryId = bFrame.batteryId,
            cellVoltagesV = bFrame.cellVoltagesV,
            packVoltageV = bFrame.packVoltageV,
            currentA = bFrame.currentA,
            consumedMah = bFrame.consumedMah,
            remainingPercent = bFrame.remainingPercent,
            temperatureC = bFrame.temperatureC,
            isStale = bFrame.stale,
            positionLat = if (positionFrame != null && !isPositionStale) positionFrame.latDeg else null,
            positionLon = if (positionFrame != null && !isPositionStale) positionFrame.lonDeg else null,
            positionAltM = if (positionFrame != null && !isPositionStale) positionFrame.altitudeM else null,
            positionAgeMs = posAge,
            isPositionStale = isPositionStale,
            alertLevel = alertLevel.name,
            cellFault = cellFault,
            pumpState = pumpState
        )

        val offered = channel.trySend(record)
        if (!offered.isSuccess) {
            droppedRecordsCount++
        }
    }

    private fun formatJson(r: LogRecord): String {
        val cellsStr = r.cellVoltagesV.joinToString(",", "[", "]") {
            if (it.isNaN()) "null" else "%.3f".format(Locale.US, it)
        }
        val posLatStr = r.positionLat?.let { "%.6f".format(Locale.US, it) } ?: "null"
        val posLonStr = r.positionLon?.let { "%.6f".format(Locale.US, it) } ?: "null"
        val posAltStr = r.positionAltM?.let { "%.1f".format(Locale.US, it) } ?: "null"
        val posAgeStr = r.positionAgeMs?.toString() ?: "null"

        return buildString {
            append("{")
            append("\"schema\":${r.schemaVersion},")
            append("\"utc\":${r.utcTimeMs},")
            append("\"mono\":${r.monoTimeMs},")
            append("\"source\":\"${r.sessionSource}\",")
            append("\"sysId\":${r.systemId},")
            append("\"batId\":${r.batteryId},")
            append("\"cells\":$cellsStr,")
            append("\"packV\":${r.packVoltageV ?: "null"},")
            append("\"currA\":${r.currentA ?: "null"},")
            append("\"consumedMah\":${r.consumedMah ?: "null"},")
            append("\"percent\":${r.remainingPercent ?: "null"},")
            append("\"tempC\":${r.temperatureC ?: "null"},")
            append("\"stale\":${r.isStale},")
            append("\"lat\":$posLatStr,")
            append("\"lon\":$posLonStr,")
            append("\"alt\":$posAltStr,")
            append("\"posAge\":$posAgeStr,")
            append("\"posStale\":${r.isPositionStale},")
            append("\"alert\":\"${r.alertLevel}\",")
            append("\"cellFault\":${r.cellFault},")
            append("\"pump\":\"${r.pumpState ?: "NONE"}\"")
            append("}")
        }
    }

    suspend fun flushAndClose() {
        if (isClosed) return
        isClosed = true
        channel.close()
        readerJob.join()
        withContext(Dispatchers.IO) {
            try {
                writer.flush()
                writer.close()
            } catch (e: Exception) {}
        }
    }

    companion object {
        fun parseRecord(line: String): LogRecord? {
            return try {
                if (!line.startsWith("{") || !line.endsWith("}")) return null
                val map = mutableMapOf<String, String>()
                val content = line.substring(1, line.length - 1)
                var i = 0
                while (i < content.length) {
                    val keyStartQuote = content.indexOf('"', i)
                    if (keyStartQuote < 0) break
                    val keyEndQuote = content.indexOf('"', keyStartQuote + 1)
                    if (keyEndQuote < 0) break
                    val key = content.substring(keyStartQuote + 1, keyEndQuote)
                    val colon = content.indexOf(':', keyEndQuote + 1)
                    if (colon < 0) break
                    var valStart = colon + 1
                    while (valStart < content.length && content[valStart].isWhitespace()) valStart++
                    val valEnd: Int
                    if (valStart < content.length && content[valStart] == '[') {
                        var depth = 1
                        var cur = valStart + 1
                        while (cur < content.length && depth > 0) {
                            if (content[cur] == '[') depth++
                            else if (content[cur] == ']') depth--
                            cur++
                        }
                        valEnd = cur
                    } else if (valStart < content.length && content[valStart] == '"') {
                        val endQuote = content.indexOf('"', valStart + 1)
                        valEnd = if (endQuote >= 0) endQuote + 1 else content.length
                    } else {
                        var cur = valStart
                        while (cur < content.length && content[cur] != ',') cur++
                        valEnd = cur
                    }
                    val value = content.substring(valStart, valEnd).trim()
                    map[key] = value
                    i = valEnd
                    if (i < content.length && content[i] == ',') i++
                }

                val schema = map["schema"]?.toIntOrNull() ?: 1
                val utc = map["utc"]?.toLongOrNull() ?: 0L
                val mono = map["mono"]?.toLongOrNull() ?: 0L
                val source = map["source"]?.removeSurrounding("\"") ?: "LIVE_UDP"
                val sysId = map["sysId"]?.toIntOrNull() ?: 1
                val batId = map["batId"]?.toIntOrNull() ?: 0
                val packV = map["packV"]?.takeIf { it != "null" }?.toFloatOrNull()
                val currA = map["currA"]?.takeIf { it != "null" }?.toFloatOrNull()
                val consumed = map["consumedMah"]?.takeIf { it != "null" }?.toFloatOrNull()
                val pct = map["percent"]?.takeIf { it != "null" }?.toIntOrNull()
                val tempC = map["tempC"]?.takeIf { it != "null" }?.toFloatOrNull()
                val stale = map["stale"]?.toBooleanStrictOrNull() ?: false
                val lat = map["lat"]?.takeIf { it != "null" }?.toDoubleOrNull()
                val lon = map["lon"]?.takeIf { it != "null" }?.toDoubleOrNull()
                val alt = map["alt"]?.takeIf { it != "null" }?.toFloatOrNull()
                val posAge = map["posAge"]?.takeIf { it != "null" }?.toLongOrNull()
                val posStale = map["posStale"]?.toBooleanStrictOrNull() ?: false
                val alert = map["alert"]?.removeSurrounding("\"") ?: "NONE"
                val cellFault = map["cellFault"]?.toBooleanStrictOrNull() ?: false
                val pump = map["pump"]?.removeSurrounding("\"")

                val cellsRaw = map["cells"]?.removeSurrounding("[", "]") ?: ""
                val cells = if (cellsRaw.isBlank()) emptyList() else {
                    cellsRaw.split(",").map { s -> if (s.trim() == "null") Float.NaN else s.trim().toFloat() }
                }

                LogRecord(
                    schemaVersion = schema,
                    utcTimeMs = utc,
                    monoTimeMs = mono,
                    sessionSource = source,
                    systemId = sysId,
                    batteryId = batId,
                    cellVoltagesV = cells,
                    packVoltageV = packV,
                    currentA = currA,
                    consumedMah = consumed,
                    remainingPercent = pct,
                    temperatureC = tempC,
                    isStale = stale,
                    positionLat = lat,
                    positionLon = lon,
                    positionAltM = alt,
                    positionAgeMs = posAge,
                    isPositionStale = posStale,
                    alertLevel = alert,
                    cellFault = cellFault,
                    pumpState = pump
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
