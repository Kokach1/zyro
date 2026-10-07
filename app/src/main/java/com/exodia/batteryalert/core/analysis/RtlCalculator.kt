package com.exodia.batteryalert.core.analysis

import com.exodia.batteryalert.core.config.AppConfig
import com.exodia.batteryalert.core.config.BatteryProfile
import com.exodia.batteryalert.core.model.RtlAssessment
import kotlin.math.roundToInt

/**
 * Dynamic Return-To-Launch (RTL) energy requirement calculator.
 *
 * FR-3.1 formula:
 *   return_seconds = distance_to_home_m / cruising_speed_mps
 *   discharge_rate_percent_per_second = (rate_mAh_per_min / 60) / capacity_mAh * 100
 *   required_battery_percent = return_seconds * discharge_rate_percent_per_second + 15
 *
 * Enforces (FIX-12):
 *  - Strict dependency validation: nonnegative distance, positive rate, speed, capacity.
 *  - Unclamped required percent > 100% in core (e.g. impossible return marked unsafe).
 *  - Inclusive <= trigger: remainingPercent <= required_battery_percent.
 *  - Unrounded seconds for calculation; integer rounding for ETA display.
 */
class RtlCalculator(
    private val profile: BatteryProfile,
    private val cruisingSpeedMps: Float = AppConfig.cruisingSpeedMps
) {
    fun assess(
        distanceM: Float?,
        rateMahPerMin: Float?,
        remainingPercent: Int,
        minutesRemaining: Float?,
        speedMps: Float = cruisingSpeedMps
    ): RtlAssessment {
        val margin = AppConfig.rtlSafetyMarginPercent

        // Input validation: distance >= 0, rate > 0, speed > 0, capacity > 0
        if (distanceM == null || distanceM < 0f || !distanceM.isFinite() ||
            rateMahPerMin == null || rateMahPerMin <= 0f || !rateMahPerMin.isFinite() ||
            speedMps <= 0f || !speedMps.isFinite() ||
            profile.capacityMah <= 0
        ) {
            return RtlAssessment(
                distanceToHomeM = distanceM?.takeIf { it >= 0f && it.isFinite() },
                requiredPercent = null,
                marginPercent = margin,
                belowRequired = false,
                returnEtaSec = null,
                returnEtaExceedsTimeLeft = false
            )
        }

        val returnSeconds = distanceM / speedMps
        val ratePercentPerSec = (rateMahPerMin / 60f) / profile.capacityMah * 100f
        val requiredPercent = returnSeconds * ratePercentPerSec + margin

        val etaSec = (kotlin.math.ceil(returnSeconds / 5f) * 5).toInt()
        val belowRequired = remainingPercent <= requiredPercent
        val returnEtaExceedsTimeLeft = minutesRemaining != null && (returnSeconds / 60f) > (minutesRemaining * 0.8f)

        return RtlAssessment(
            distanceToHomeM = distanceM,
            requiredPercent = requiredPercent,
            marginPercent = margin,
            belowRequired = belowRequired,
            returnEtaSec = etaSec,
            returnEtaExceedsTimeLeft = returnEtaExceedsTimeLeft
        )
    }
}
