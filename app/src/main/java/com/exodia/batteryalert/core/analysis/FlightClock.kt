package com.exodia.batteryalert.core.analysis

import com.exodia.batteryalert.core.config.AppConfig

/**
 * Tracks armed flight elapsed time.
 * Primary: Vehicle armed state from HEARTBEAT.
 * Fallback: Current threshold only when arm state is unavailable (e.g. simulator).
 */
class FlightClock {
    private var startMs: Long? = null
    private var totalElapsedMs: Long = 0L
    private var isCurrentlyArmed: Boolean = false
    private var lastArmChangeTimeMs: Long? = null

    fun onVehicleState(timestampMs: Long, isArmed: Boolean): Long {
        if (isArmed && !isCurrentlyArmed) {
            isCurrentlyArmed = true
            lastArmChangeTimeMs = timestampMs
            if (startMs == null) startMs = timestampMs
        } else if (!isArmed && isCurrentlyArmed) {
            lastArmChangeTimeMs?.let {
                totalElapsedMs += (timestampMs - it).coerceAtLeast(0)
            }
            isCurrentlyArmed = false
            lastArmChangeTimeMs = null
        }
        return elapsedSec(timestampMs)
    }

    fun onFrame(timestampMs: Long, currentA: Float?, isArmed: Boolean? = null): Long {
        if (isArmed != null) {
            return onVehicleState(timestampMs, isArmed)
        }
        if (currentA != null && currentA >= AppConfig.flightStartCurrentA) {
            if (startMs == null) startMs = timestampMs
        }
        return elapsedSec(timestampMs)
    }

    fun elapsedSec(nowMs: Long): Long {
        var elapsed = totalElapsedMs
        if (isCurrentlyArmed) {
            lastArmChangeTimeMs?.let {
                elapsed += (nowMs - it).coerceAtLeast(0)
            }
        } else if (startMs != null && totalElapsedMs == 0L) {
            elapsed = (nowMs - startMs!!).coerceAtLeast(0)
        }
        return elapsed / 1_000L
    }

    fun reset() {
        startMs = null
        totalElapsedMs = 0L
        isCurrentlyArmed = false
        lastArmChangeTimeMs = null
    }
}
