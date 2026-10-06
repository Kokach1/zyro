package com.exodia.batteryalert.core.analysis

import com.exodia.batteryalert.core.config.AppConfig
class FlightClock {
    private var startMs: Long? = null
    fun onFrame(timestampMs: Long, currentA: Float): Long { if (startMs == null && currentA >= AppConfig.flightStartCurrentA) startMs = timestampMs; return elapsedSec(timestampMs) }
    fun elapsedSec(nowMs: Long): Long = startMs?.let { ((nowMs - it) / 1_000L).coerceAtLeast(0) } ?: 0L
    fun reset() { startMs = null }
}
