package com.exodia.batteryalert.core.analysis

/** All ring elements share this clockwise 270-degree scale. */
object RingGeometry {
    const val startAngle = 135f
    const val totalSweep = 270f
    fun angleFor(percent: Float): Float = startAngle + totalSweep * (percent.coerceIn(0f, 100f) / 100f)
    fun fillSweep(percent: Float): Float = totalSweep * (percent.coerceIn(0f, 100f) / 100f)
}
