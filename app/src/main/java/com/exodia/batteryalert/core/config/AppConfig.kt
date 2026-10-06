package com.exodia.batteryalert.core.config

object AppConfig {
    const val transportId = "simulator"
    const val batteryProfileId = "AGRAS_T55_DB1580"
    const val noticePercent = 30
    const val warningPercent = 20
    // FRD values; confirm they suit the actual battery chemistry.
    const val warningCellVoltageV = 3.65f
    const val criticalCellVoltageV = 3.50f
    const val emergencyCellVoltageV = 3.40f
    const val cellDeltaFaultV = 0.08f
    const val rtlSafetyMarginPercent = 15f
    // SUGGESTED DEFAULT - not from FRD, confirm with Exodia.
    const val cruisingSpeedMps = 5f
    // SUGGESTED DEFAULT - not from FRD, confirm with Exodia.
    const val internalResistanceOhmPerCell = 0.002f
    const val consumptionWindowSeconds = 30L
    // SUGGESTED DEFAULT - not from FRD, confirm with Exodia.
    const val rapidSagDropV = 0.15f
    const val rapidSagWindowSeconds = 2L
    const val rapidSagCurrentTolerance = 0.10f
    const val linkLostTimeoutMs = 3_000L
    const val alertHysteresisPercent = 3
    const val alertHysteresisCellVoltageV = 0.03f
    const val alertDebounceMs = 1_500L
    const val criticalClearMs = 5_000L
    const val ttsIntervalSeconds = 60L
    const val sprayInterlockPercent = 20
    const val flightStartCurrentA = 5f
}
