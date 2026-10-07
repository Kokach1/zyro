package com.exodia.batteryalert.ui.monitor

import androidx.compose.ui.graphics.Color
import com.exodia.batteryalert.core.model.*
import com.exodia.batteryalert.ui.theme.AlertColors

data class AlertPresentation(val label: String, val color: Color, val suppressRingFill: Boolean)

fun alertPresentation(alert: ActiveAlert?, cellFault: Boolean, connection: ConnectionState): AlertPresentation? {
    if (connection is ConnectionState.LinkLost) return AlertPresentation("TELEMETRY LOST", AlertColors.LinkLost, true)
    val level = alert?.level
    return when {
        level == AlertLevel.EMERGENCY -> AlertPresentation("LAND NOW", AlertColors.Emergency, true)
        level == AlertLevel.CRITICAL -> AlertPresentation("RETURN NOW", AlertColors.Critical, true)
        level == AlertLevel.WARNING -> AlertPresentation("LOW BATTERY", AlertColors.Warning, true)
        level == AlertLevel.NOTICE && alert.reasons.contains(AlertReason.CELL_IMBALANCE) -> AlertPresentation("CELL FAULT", AlertColors.CellFault, true)
        level == AlertLevel.NOTICE -> AlertPresentation("PLAN TO RETURN", AlertColors.Notice, true)
        cellFault -> AlertPresentation("CELL FAULT", AlertColors.CellFault, true)
        else -> null
    }
}
