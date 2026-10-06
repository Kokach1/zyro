package com.exodia.batteryalert.core.analysis

import com.exodia.batteryalert.core.config.AppConfig
import com.exodia.batteryalert.core.config.BatteryProfile
import com.exodia.batteryalert.core.model.*
import kotlin.math.abs

class BatteryAnalyzer(private val profile: BatteryProfile) {
    private val sagSamples = ArrayDeque<Triple<Long, Float, Float>>()
    fun analyze(frame: BatteryFrame, pack: PackConfig, history: ConsumptionHistory, flightElapsedSec: Long): BatteryAnalysis {
        val count = pack.cellCount.coerceAtLeast(1)
        val measured = frame.cellVoltagesV.filter { it > 0f }.ifEmpty { List(count) { frame.packVoltageV / count } }
        val rest = measured.map { it + frame.currentA * AppConfig.internalResistanceOhmPerCell }
        history.add(frame.timestampMs, frame.consumedMah)
        val average = measured.average().toFloat()
        sagSamples.addLast(Triple(frame.timestampMs, average, frame.currentA))
        while (sagSamples.size > 1 && frame.timestampMs - sagSamples.first().first > AppConfig.rapidSagWindowSeconds * 1_000L) sagSamples.removeFirst()
        val old = sagSamples.firstOrNull()
        val rapid = old != null && old.second - average > AppConfig.rapidSagDropV && abs(frame.currentA - old.third) <= old.third.coerceAtLeast(1f) * AppConfig.rapidSagCurrentTolerance
        val percent = frame.remainingPercent?.takeIf { it in 0..100 } ?: CellVoltageCurve.percentForVoltage(rest.average().toFloat())
        val rate = history.rateMahPerMin()
        val minutes = rate?.takeIf { it >= 50f }?.let { ((profile.capacityMah * percent / 100f) / it).coerceAtMost(99f) }
        return BatteryAnalysis(measured.size, measured, rest, measured.minOrNull() ?: 0f, measured.maxOrNull() ?: 0f, average, (measured.maxOrNull() ?: 0f) - (measured.minOrNull() ?: 0f), frame.packVoltageV, frame.currentA, percent, rate, minutes, frame.temperatureC, rapid, flightElapsedSec)
    }
}
