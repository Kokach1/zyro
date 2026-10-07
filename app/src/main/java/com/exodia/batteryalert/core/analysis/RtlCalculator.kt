package com.exodia.batteryalert.core.analysis

import com.exodia.batteryalert.core.config.AppConfig
import com.exodia.batteryalert.core.config.BatteryProfile
import com.exodia.batteryalert.core.model.RtlAssessment
import kotlin.math.ceil

class RtlCalculator(private val profile: BatteryProfile) {
    fun assess(distanceM: Float?, rateMahPerMin: Float?, remainingPercent: Int, minutesRemaining: Float?): RtlAssessment {
        val eta = distanceM?.let { (ceil((it / AppConfig.cruisingSpeedMps) / 5f) * 5).toInt() }
        val required = if (distanceM == null || rateMahPerMin == null) null else distanceM / AppConfig.cruisingSpeedMps * (rateMahPerMin / 60f / profile.capacityMah * 100f) + AppConfig.rtlSafetyMarginPercent
        return RtlAssessment(distanceM, required, AppConfig.rtlSafetyMarginPercent, required?.let { remainingPercent <= it } ?: false, eta, eta != null && minutesRemaining != null && eta / 60f > minutesRemaining * .8f)
    }
}
