package com.exodia.batteryalert.core.analysis

import com.exodia.batteryalert.core.model.*
import kotlin.math.roundToInt

class ChemistryDetector(private val chemistry: BatteryChemistry) {
    private var locked: PackConfig? = null
    fun detect(frame: BatteryFrame): PackConfig {
        locked?.let { return it }
        val count = frame.cellVoltagesV.count { it > 0f }
        return if (count > 0) PackConfig(count, chemistry, true).also { locked = it }
        else PackConfig((frame.packVoltageV / 3.7f).roundToInt().coerceIn(3, 14), chemistry, false)
    }
    fun reset() { locked = null }
}
