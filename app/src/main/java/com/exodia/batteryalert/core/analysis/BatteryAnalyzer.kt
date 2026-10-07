package com.exodia.batteryalert.core.analysis

import com.exodia.batteryalert.core.config.AppConfig
import com.exodia.batteryalert.core.config.BatteryProfile
import com.exodia.batteryalert.core.model.*
import kotlin.math.abs

/**
 * Pure-domain battery analysis engine.
 *
 * Enforces:
 *   - No fabricated measured cells: analyzes actual indexed measured cells only.
 *   - Preserves measured 0V cells (critical safety fault).
 *   - Resting voltage compensation Vrest = Vmeasured + I * Ri only computed when current is known.
 *   - Aggregate-only telemetry does not fabricate measured cells or false balance.
 *   - Loaded min-cell voltage used for alert thresholds.
 */
class BatteryAnalyzer(private val profile: BatteryProfile) {
    private val sagSamples = ArrayDeque<Triple<Long, Float, Float>>()

    fun analyze(
        frame: BatteryFrame,
        pack: PackConfig,
        history: ConsumptionHistory,
        flightElapsedSec: Long
    ): BatteryAnalysis {
        val hasMeasuredCells = frame.isCellsFresh && frame.cellVoltagesV.isNotEmpty() && !frame.isAggregateOnly
        val validCells = frame.cellVoltagesV.filter { !it.isNaN() }
        val count = if (hasMeasuredCells) frame.cellVoltagesV.size else 0

        val average: Float
        val minCell: Float?
        val maxCell: Float?
        val delta: Float?
        val rest: List<Float>

        if (hasMeasuredCells && validCells.isNotEmpty()) {
            average = validCells.average().toFloat()
            minCell = validCells.minOrNull() ?: 0f
            maxCell = validCells.maxOrNull() ?: 0f
            delta = maxCell - minCell
            // Resting voltage only compensated when current is known
            val current = frame.currentA
            rest = if (current != null) {
                frame.cellVoltagesV.map { if (it.isNaN()) Float.NaN else it + current * AppConfig.internalResistanceOhmPerCell }
            } else {
                frame.cellVoltagesV
            }
        } else {
            // Aggregate-only telemetry: estimate average if cell count confirmed, but no measured tiles/delta
            val confirmedCount = pack.cellCount.coerceAtLeast(1)
            average = if (frame.packVoltageV != null) frame.packVoltageV / confirmedCount else 0f
            minCell = null
            maxCell = null
            delta = null
            rest = emptyList()
        }

        // Consumption tracking: update only with valid consumed counter or known current
        if (frame.consumedMah != null) {
            history.add(frame.timestampMs, frame.consumedMah)
        }
        if (frame.currentA != null) {
            history.addCurrentSample(frame.timestampMs, frame.currentA)
        }

        // Rapid sag detection
        val currentA = frame.currentA
        val rapid = if (currentA != null && hasMeasuredCells) {
            sagSamples.addLast(Triple(frame.timestampMs, average, currentA))
            while (sagSamples.size > 1 && frame.timestampMs - sagSamples.first().first > AppConfig.rapidSagWindowSeconds * 1_000L) {
                sagSamples.removeFirst()
            }
            val old = sagSamples.firstOrNull()
            old != null && old.second - average > AppConfig.rapidSagDropV &&
                abs(currentA - old.third) <= old.third.coerceAtLeast(1f) * AppConfig.rapidSagCurrentTolerance
        } else {
            false
        }

        val percent = frame.remainingPercent?.takeIf { it in 0..100 }
            ?: (if (rest.isNotEmpty() && rest.any { !it.isNaN() }) CellVoltageCurve.percentForVoltage(rest.filter { !it.isNaN() }.average().toFloat()) else 0)

        val rate = history.rateMahPerMin()
        val minutes = if (rate != null && rate >= AppConfig.minimumRateMahPerMin && percent in 1..100) {
            ((profile.capacityMah * percent / 100f) / rate).coerceAtMost(99f)
        } else {
            null
        }

        return BatteryAnalysis(
            cellCount = count,
            cellVoltagesV = frame.cellVoltagesV,
            restCellVoltagesV = rest,
            minCellV = minCell,
            maxCellV = maxCell,
            avgCellV = average,
            cellDeltaV = delta,
            packVoltageV = frame.packVoltageV,
            currentA = frame.currentA,
            remainingPercent = percent,
            consumptionMahPerMin = rate,
            minutesRemaining = minutes,
            temperatureC = frame.temperatureC,
            isSagRapid = rapid,
            flightElapsedSec = flightElapsedSec,
            isCellsFresh = hasMeasuredCells,
            isStale = frame.stale
        )
    }
}
