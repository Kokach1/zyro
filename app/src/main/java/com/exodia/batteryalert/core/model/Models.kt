package com.exodia.batteryalert.core.model

data class BatteryFrame(
    val timestampMs: Long,
    val cellVoltagesV: List<Float>,
    val packVoltageV: Float?,
    val currentA: Float?,
    val consumedMah: Float? = null,
    val temperatureC: Float? = null,
    val remainingPercent: Int? = null,
    val batteryId: Int = 0,
    val cycleCount: Int? = null,
    val healthPercent: Int? = null,
    // Day 2 fields with safe defaults preserving Day 1 contracts:
    val isAggregateOnly: Boolean = false,
    val systemId: Int = 1,
    val componentId: Int = 1,
    val stale: Boolean = false,
    val receivedAtMonotonicMs: Long = timestampMs,
    val faultBitmask: Long = 0L,
    val isCurrentKnown: Boolean = (currentA != null),
    val isFromFallback: Boolean = false,
    val isCellsFresh: Boolean = !stale && cellVoltagesV.isNotEmpty()
)

data class PositionFrame(
    val timestampMs: Long,
    val latDeg: Double,
    val lonDeg: Double,
    val altitudeM: Float,
    val relativeAltitudeM: Float,
    val groundSpeedMps: Float,
    val systemId: Int = 1,
    val componentId: Int = 1
)

enum class HomeSource { AUTOPILOT_HOME, SIMULATOR_HOME, USER_CONFIRMED_FALLBACK }

data class HomeFrame(
    val timestampMs: Long,
    val latDeg: Double,
    val lonDeg: Double,
    val altitudeM: Float = 0f,
    val source: HomeSource = HomeSource.AUTOPILOT_HOME,
    val isValid: Boolean = true
)

data class VehicleStateFrame(
    val timestampMs: Long,
    val systemId: Int,
    val componentId: Int,
    val isArmed: Boolean,
    val autopilotType: Int = 0,
    val systemStatus: Int = 0,
    val isAutopilot: Boolean = true
)

enum class CommandAckResult {
    ACCEPTED,
    TEMPORARILY_REJECTED,
    DENIED,
    UNSUPPORTED,
    FAILED,
    IN_PROGRESS
}

data class CommandResultFrame(
    val timestampMs: Long,
    val commandId: Int,
    val result: CommandAckResult,
    val progress: Int = 0,
    val targetSystem: Int = 1,
    val targetComponent: Int = 1
)

sealed interface TelemetryFrame {
    data class Battery(val value: BatteryFrame) : TelemetryFrame
    data class Position(val value: PositionFrame) : TelemetryFrame
    data class Home(val value: HomeFrame) : TelemetryFrame
    data class VehicleState(val value: VehicleStateFrame) : TelemetryFrame
    data class CommandResult(val value: CommandResultFrame) : TelemetryFrame
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class LinkLost(val sinceMs: Long) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

enum class SessionSource(val displayName: String, val isLive: Boolean) {
    LIVE_UDP("LIVE UDP", true),
    LIVE_USB("LIVE USB", true),
    LIVE_INTERNAL("LIVE SERIAL", true),
    SIMULATOR("SIMULATOR", false),
    REPLAY("REPLAY", false);
}

enum class BatteryChemistry { LI_ION, LIPO, LIHV, UNKNOWN }
data class PackConfig(val cellCount: Int, val chemistry: BatteryChemistry, val detected: Boolean)
enum class AlertLevel { NONE, NOTICE, WARNING, CRITICAL, EMERGENCY }
enum class AlertReason { LOW_PERCENT_30, LOW_PERCENT_20, LOW_CELL_VOLTAGE_WARN, BELOW_DYNAMIC_RTL, LOW_CELL_VOLTAGE_CRITICAL, LOW_CELL_VOLTAGE_EMERGENCY, RAPID_SAG, CELL_IMBALANCE, LINK_LOST }
data class ActiveAlert(val level: AlertLevel, val reasons: List<AlertReason>, val title: String, val message: String, val sinceMs: Long, val dismissible: Boolean)

data class BatteryAnalysis(
    val cellCount: Int, val cellVoltagesV: List<Float>, val restCellVoltagesV: List<Float>,
    val minCellV: Float?, val maxCellV: Float?, val avgCellV: Float?, val cellDeltaV: Float?,
    val packVoltageV: Float?, val currentA: Float?, val remainingPercent: Int,
    val consumptionMahPerMin: Float?, val minutesRemaining: Float?, val temperatureC: Float?,
    val isSagRapid: Boolean, val flightElapsedSec: Long,
    val isCellsFresh: Boolean = true,
    val isStale: Boolean = false
)

data class RtlAssessment(
    val distanceToHomeM: Float?, val requiredPercent: Float?, val marginPercent: Float,
    val belowRequired: Boolean, val returnEtaSec: Int?, val returnEtaExceedsTimeLeft: Boolean
)
