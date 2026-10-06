package com.exodia.batteryalert.core.analysis

/** Shared Li-ion approximation for simulation and percent fallback. */
object CellVoltageCurve {
    private val points = listOf(0f to 3.30f, 5f to 3.45f, 10f to 3.55f, 20f to 3.62f, 30f to 3.68f, 40f to 3.74f, 50f to 3.80f, 60f to 3.86f, 70f to 3.93f, 80f to 4.00f, 90f to 4.10f, 100f to 4.18f)
    fun voltageForPercent(percent: Float): Float = interpolate(percent.coerceIn(0f, 100f), points.map { it.first to it.second })
    fun percentForVoltage(voltage: Float): Int = interpolate(voltage.coerceIn(3.30f, 4.18f), points.map { it.second to it.first }).toInt().coerceIn(0, 100)
    private fun interpolate(value: Float, values: List<Pair<Float, Float>>): Float {
        val upper = values.firstOrNull { value <= it.first } ?: return values.last().second
        val index = values.indexOf(upper)
        if (index == 0) return upper.second
        val lower = values[index - 1]
        val portion = (value - lower.first) / (upper.first - lower.first)
        return lower.second + (upper.second - lower.second) * portion
    }
}
