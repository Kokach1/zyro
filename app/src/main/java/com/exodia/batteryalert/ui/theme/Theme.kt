package com.exodia.batteryalert.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

object AlertColors {
    val Background = Color(0xFF0A0E13); val Surface = Color(0xFF121820); val Raised = Color(0xFF1A222C); val Outline = Color(0xFF26303B)
    val Primary = Color(0xFFEAF0F6); val Secondary = Color(0xFF93A3B4); val Disabled = Color(0xFF4B5866)
    val Normal = Color(0xFF3DD68C); val Notice = Color(0xFFF2D04B); val Warning = Color(0xFFFF9F1C); val Critical = Color(0xFFFF4D4F); val Emergency = Color(0xFFFF1F44); val CellFault = Color(0xFFFF7A2F); val LinkLost = Color(0xFF7E8A97); val Accent = Color(0xFF5AA9FF)
}
val Tabular = TextStyle(fontFamily = FontFamily.SansSerif, fontFeatureSettings = "tnum")
@Composable fun BatteryAlertTheme(content: @Composable () -> Unit) = content()
