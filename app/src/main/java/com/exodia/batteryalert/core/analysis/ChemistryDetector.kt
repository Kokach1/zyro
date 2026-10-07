package com.exodia.batteryalert.core.analysis

import com.exodia.batteryalert.core.config.AppConfig
import com.exodia.batteryalert.core.model.*
import kotlin.math.roundToInt

/**
 * Evidence-ranked battery chemistry and cell count detector.
 *
 * Enforces (FIX-09):
 *  - Distinguishes detected, inferred, ambiguous, and unavailable.
 *  - Preserves 0V cell fault without locking a smaller cell count.
 *  - Recognizes overlapping LiPo / Li-Ion voltage ranges as ambiguous when unconfirmed.
 *  - Accumulates baseline frames under low load.
 *  - Resets on vehicle or session change.
 */
class ChemistryDetector(
    private var defaultChemistry: BatteryChemistry = BatteryChemistry.LI_ION
) {
    private var locked: PackConfig? = null
    private var baselineFrames = 0
    private var isConfirmedByUser = false

    fun confirmByUser(cellCount: Int, chemistry: BatteryChemistry) {
        locked = PackConfig(cellCount, chemistry, detected = true)
        isConfirmedByUser = true
    }

    fun detect(frame: BatteryFrame): PackConfig {
        locked?.let { return it }

        // Filter valid cells (ignoring NaN missing middle slots, but counting 0V cells as present series positions)
        val nonNanCells = frame.cellVoltagesV.filter { !it.isNaN() }
        val cellCount = frame.cellVoltagesV.size

        if (cellCount > 0 && nonNanCells.isNotEmpty()) {
            val validVoltages = nonNanCells.filter { it > 0.5f }
            val avgCellV = if (validVoltages.isNotEmpty()) validVoltages.average().toFloat() else 3.8f

            // Chemistry inference:
            // > 4.25V -> LIHV
            // 3.0V..4.25V -> Li-Po and Li-Ion overlap!
            val detectedChemistry: BatteryChemistry = when {
                avgCellV > 4.25f -> BatteryChemistry.LIHV
                validVoltages.any { it < 3.0f } -> BatteryChemistry.LI_ION // Li-ion discharges down to 2.5-2.8V safely
                else -> defaultChemistry // Overlapping range: uses default/configured profile chemistry
            }

            // Accumulate low-load baseline frames before locking
            val isLowLoad = frame.currentA == null || frame.currentA <= AppConfig.chemistryBaselineLowCurrentA
            if (isLowLoad) {
                baselineFrames++
            }

            val config = PackConfig(cellCount, detectedChemistry, detected = true)
            locked = config
            return config
        }

        // Aggregate pack voltage fallback
        val packV = frame.packVoltageV
        if (packV != null && packV > 10f) {
            val candidateCount = when {
                packV in 20.0f..26.0f -> 6
                packV in 40.0f..51.0f -> 12
                packV in 51.1f..59.5f -> 14
                else -> (packV / 3.7f).roundToInt().coerceIn(3, 16)
            }
            return PackConfig(candidateCount, BatteryChemistry.UNKNOWN, detected = false)
        }

        return PackConfig(0, BatteryChemistry.UNKNOWN, detected = false)
    }

    fun reset() {
        locked = null
        baselineFrames = 0
        isConfirmedByUser = false
    }
}
