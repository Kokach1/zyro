package com.exodia.batteryalert.core.model

data class BatteryFrame(
    val timestampMs: Long,
    val cellVoltagesV: List<Float>,
    val packVoltageV: Float,
    val currentA: Float,
    val consumedMah: Float? = null,
    val temperatureC: Float? = null,
    val remainingPercent: Int? = null,
    val batteryId: Int = 0,
    val cycleCount: Int? = null,
    val healthPercent: Int? = null
)

data class PositionFrame(
    val timestampMs: Long, val latDeg: Double, val lonDeg: Double,
    val altitudeM: Float, val relativeAltitudeM: Float, val groundSpeedMps: Float
)

sealed interface TelemetryFrame {
    data class Battery(val value: BatteryFrame) : TelemetryFrame
    data class Position(val value: PositionFrame) : TelemetryFrame
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class LinkLost(val sinceMs: Long) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

enum class BatteryChemistry { LI_ION, LIPO, LIHV, UNKNOWN }
data class PackConfig(val cellCount: Int, val chemistry: BatteryChemistry, val detected: Boolean)
enum class AlertLevel { NONE, NOTICE, WARNING, CRITICAL, EMERGENCY }
enum class AlertReason { LOW_PERCENT_30, LOW_PERCENT_20, LOW_CELL_VOLTAGE_WARN, BELOW_DYNAMIC_RTL, LOW_CELL_VOLTAGE_CRITICAL, LOW_CELL_VOLTAGE_EMERGENCY, RAPID_SAG, CELL_IMBALANCE, LINK_LOST }
data class ActiveAlert(val level: AlertLevel, val reasons: List<AlertReason>, val title: String, val message: String, val sinceMs: Long, val dismissible: Boolean)

data class BatteryAnalysis(
    val cellCount: Int, val cellVoltagesV: List<Float>, val restCellVoltagesV: List<Float>,
    val minCellV: Float, val maxCellV: Float, val avgCellV: Float, val cellDeltaV: Float,
    val packVoltageV: Float, val currentA: Float, val remainingPercent: Int,
    val consumptionMahPerMin: Float?, val minutesRemaining: Float?, val temperatureC: Float?,
    val isSagRapid: Boolean, val flightElapsedSec: Long
)

data class RtlAssessment(
    val distanceToHomeM: Float?, val requiredPercent: Float?, val marginPercent: Float,
    val belowRequired: Boolean, val returnEtaSec: Int?, val returnEtaExceedsTimeLeft: Boolean
)
