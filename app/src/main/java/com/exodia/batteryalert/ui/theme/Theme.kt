package com.exodia.batteryalert.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.exodia.batteryalert.core.model.AlertLevel

object AlertColors {
    val Background = Color(0xFF0A0E13); val Surface = Color(0xFF121820); val Raised = Color(0xFF1A222C); val Outline = Color(0xFF26303B)
    val Primary = Color(0xFFEAF0F6); val Secondary = Color(0xFF93A3B4); val Disabled = Color(0xFF4B5866)
    val Normal = Color(0xFF3DD68C); val Notice = Color(0xFFF2D04B); val Warning = Color(0xFFFF9F1C); val Critical = Color(0xFFFF4D4F); val Emergency = Color(0xFFFF1F44); val CellFault = Color(0xFFFF7A2F); val LinkLost = Color(0xFF7E8A97); val Accent = Color(0xFF5AA9FF)
}

data class DashboardThemeColors(
    val background: Color,
    val surface: Color,
    val raised: Color,
    val outline: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val accent: Color,
    val isSevereAlert: Boolean = false,
)

fun dashboardThemeColors(alertLevel: AlertLevel?): DashboardThemeColors {
    return when (alertLevel) {
        AlertLevel.EMERGENCY -> DashboardThemeColors(
            background = Color(0xFF1C0A0D),
            surface = Color(0xFF2A0F13),
            raised = Color(0xFF381419),
            outline = AlertColors.Emergency,
            primaryText = Color(0xFFFFF0F2),
            secondaryText = Color(0xFFD69A9E),
            accent = AlertColors.Emergency,
            isSevereAlert = true,
        )
        AlertLevel.CRITICAL -> DashboardThemeColors(
            background = Color(0xFF1A0A0C),
            surface = Color(0xFF280E10),
            raised = Color(0xFF351216),
            outline = AlertColors.Critical,
            primaryText = Color(0xFFFFF0F0),
            secondaryText = Color(0xFFD69A9E),
            accent = AlertColors.Critical,
            isSevereAlert = true,
        )
        AlertLevel.WARNING -> DashboardThemeColors(
            background = Color(0xFF0E0D0A),
            surface = Color(0xFF1A1710),
            raised = Color(0xFF262015),
            outline = AlertColors.Warning,
            primaryText = AlertColors.Primary,
            secondaryText = AlertColors.Secondary,
            accent = AlertColors.Warning,
            isSevereAlert = false,
        )
        else -> DashboardThemeColors(
            background = AlertColors.Background,
            surface = AlertColors.Surface,
            raised = AlertColors.Raised,
            outline = AlertColors.Outline,
            primaryText = AlertColors.Primary,
            secondaryText = AlertColors.Secondary,
            accent = AlertColors.Accent,
            isSevereAlert = false,
        )
    }
}

val Tabular = TextStyle(fontFamily = FontFamily.SansSerif, fontFeatureSettings = "tnum")
@Composable fun BatteryAlertTheme(content: @Composable () -> Unit) = content()
