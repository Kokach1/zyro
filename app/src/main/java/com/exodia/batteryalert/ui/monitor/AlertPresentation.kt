package com.exodia.batteryalert.ui.monitor

import androidx.compose.ui.graphics.Color
import com.exodia.batteryalert.core.model.*
import com.exodia.batteryalert.ui.theme.AlertColors

data class AlertPresentation(
    val label: String,
    val actionPrompt: String?,
    val color: Color,
    val suppressRingFill: Boolean,
)

fun alertPresentation(alert: ActiveAlert?, cellFault: Boolean, connection: ConnectionState): AlertPresentation? {
    if (connection is ConnectionState.LinkLost) {
        return AlertPresentation(
            label = "TELEMETRY LOST",
            actionPrompt = "Connection interrupted",
            color = AlertColors.LinkLost,
            suppressRingFill = true,
        )
    }
    val level = alert?.level
    return when {
        level == AlertLevel.EMERGENCY -> AlertPresentation(
            label = "LAND NOW",
            actionPrompt = "Land immediately",
            color = AlertColors.Emergency,
            suppressRingFill = true,
        )
        level == AlertLevel.CRITICAL -> AlertPresentation(
            label = "RETURN NOW",
            actionPrompt = "Mandatory RTL recommended!",
            color = AlertColors.Critical,
            suppressRingFill = true,
        )
        level == AlertLevel.WARNING -> AlertPresentation(
            label = "LOW BATTERY",
            actionPrompt = "Warning, low battery",
            color = AlertColors.Warning,
            suppressRingFill = true,
        )
        level == AlertLevel.NOTICE && alert.reasons.contains(AlertReason.CELL_IMBALANCE) -> AlertPresentation(
            label = "CELL FAULT",
            actionPrompt = "Cell voltage imbalance - land & inspect battery",
            color = AlertColors.CellFault,
            suppressRingFill = true,
        )
        level == AlertLevel.NOTICE -> AlertPresentation(
            label = "PLAN TO RETURN",
            actionPrompt = "Plan to return soon",
            color = AlertColors.Notice,
            suppressRingFill = false,
        )
        cellFault -> AlertPresentation(
            label = "CELL FAULT",
            actionPrompt = "Cell voltage imbalance - land & inspect battery",
            color = AlertColors.CellFault,
            suppressRingFill = true,
        )
        else -> null
    }
}
