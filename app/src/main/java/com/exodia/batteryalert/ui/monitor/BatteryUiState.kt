package com.exodia.batteryalert.ui.monitor

import com.exodia.batteryalert.core.config.BatteryProfile
import com.exodia.batteryalert.core.model.*

data class BatteryUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val sessionSource: SessionSource? = null,
    val analysis: BatteryAnalysis? = null,
    val rtl: RtlAssessment? = null,
    val alert: ActiveAlert? = null,
    val cellFault: Boolean = false,
    val profile: BatteryProfile? = null,
    val distanceM: Float? = null,
    val isPaused: Boolean = false,
) {
    val simulatorMode: Boolean get() = sessionSource == SessionSource.SIMULATOR
}

fun formatDistance(value: Float?): String = when {
    value == null -> "–"
    value < 1000 -> "${value.toInt()} m"
    else -> "%.1f km".format(value / 1000f)
}

fun formatSeconds(value: Int?): String = when {
    value == null -> "–"
    value < 60 -> "${value} s"
    else -> "${value / 60} min ${value % 60} s"
}

fun formatFlightTime(seconds: Long): String = if (seconds < 3600) {
    "%d:%02d".format(seconds / 60, seconds % 60)
} else {
    "%d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
}
