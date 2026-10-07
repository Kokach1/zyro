package com.exodia.batteryalert.core.config

object AppConfig {
    // ── Transport ──────────────────────────────────────────────────────────
    // transportId removed: transport is now selected via TransportConfig/TransportKind.

    // ── Battery Profile ────────────────────────────────────────────────────
    const val batteryProfileId = "AGRAS_T55_DB1580"

    // ── FRD alert thresholds (company-mandated, do not change without FRD update) ──
    const val noticePercent = 30
    const val warningPercent = 20
    const val warningCellVoltageV = 3.65f
    const val criticalCellVoltageV = 3.50f
    const val emergencyCellVoltageV = 3.40f
    const val cellDeltaFaultV = 0.08f           // Strictly >; FR-2.2
    const val rtlSafetyMarginPercent = 15f       // FR-3.1

    // ── Proposed engineering defaults (not FRD-mandated; label as assumptions) ──
    /** SUGGESTED DEFAULT — confirm cruising speed with Exodia. */
    const val cruisingSpeedMps = 5f
    /** SUGGESTED DEFAULT — confirm Ri per cell with Exodia. */
    const val internalResistanceOhmPerCell = 0.002f
    /** Rolling consumption window for rate estimation. */
    const val consumptionWindowSeconds = 30L
    /** Minimum history required before slope is trusted over instantaneous estimate. */
    const val minimumHistoryMs = 10_000L
    /** Minimum rate (mAh/min) for the slope to be considered meaningful. */
    const val minimumRateMahPerMin = 50f
    /** SUGGESTED DEFAULT — rapid sag detection parameters. */
    const val rapidSagDropV = 0.15f
    const val rapidSagWindowSeconds = 2L
    const val rapidSagCurrentTolerance = 0.10f

    // ── Freshness/watchdog thresholds (separate per field) ─────────────────
    const val batteryFieldMaxAgeMs = 3_000L
    const val heartbeatTimeoutMs = 3_000L
    const val positionMaxAgeMs = 5_000L

    // ── Alert hysteresis/debounce (proposed, not FRD exact) ───────────────
    const val alertHysteresisPercent = 3
    const val alertHysteresisCellVoltageV = 0.03f
    const val alertDebounceMs = 1_500L
    const val criticalClearMs = 5_000L
    const val deltaClearVoltageV = 0.06f        // Below this, cell fault clears (with timer)
    const val deltaClearMs = 1_500L

    // ── Output ─────────────────────────────────────────────────────────────
    const val ttsIntervalSeconds = 60L
    const val warningRepeatMs = 10_000L
    const val emergencyRepeatGapMs = 1_000L
    const val cellFaultRepeatMs = 15_000L

    // ── Spray interlock ────────────────────────────────────────────────────
    const val sprayInterlockPercent = 20      // FR-5.1
    /** Real hardware cutoff is disabled by default. Simulator may enable for demo. */
    const val sprayAutoCutoffRealDefault = false

    // ── Flight clock ───────────────────────────────────────────────────────
    /** Fallback current threshold (A) for demo flight detection when arm state unavailable. */
    const val flightStartCurrentA = 5f

    // ── Chemistry detection ────────────────────────────────────────────────
    /** Number of stable low-current frames before locking chemistry/cell configuration. */
    const val chemistryBaselineFrames = 5
    /** SUGGESTED DEFAULT — max current (A) considered low-load for baseline. */
    const val chemistryBaselineLowCurrentA = 5f
}

