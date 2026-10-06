package com.exodia.batteryalert.core.analysis

import com.exodia.batteryalert.core.config.AppConfig
data class RingZones(val redStart: Float = 0f, val redEnd: Float = AppConfig.warningPercent.toFloat(), val yellowStart: Float?, val yellowEnd: Float?, val tickPercent: Float?)
fun ringZones(requiredPercent: Float?): RingZones = if (requiredPercent == null) RingZones(yellowStart = null, yellowEnd = null, tickPercent = null) else if (requiredPercent > AppConfig.warningPercent) RingZones(yellowStart = AppConfig.warningPercent.toFloat(), yellowEnd = requiredPercent, tickPercent = requiredPercent) else RingZones(yellowStart = null, yellowEnd = null, tickPercent = requiredPercent)
