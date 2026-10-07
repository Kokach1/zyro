package com.exodia.batteryalert.core.analysis

import com.exodia.batteryalert.core.config.AppConfig

/**
 * Robust consumption rate estimator supporting:
 *   - Primary: Cumulative consumed-mAh counter slope over rolling window.
 *   - Fallback: Current integration when consumed-mAh counter is absent.
 *   - Provisional present-load rate I_A * 1000 / 60 until mature history.
 *   - Resets on counter rollback or stale gaps (> 3s).
 */
class ConsumptionHistory(
    private val windowSeconds: Long = AppConfig.consumptionWindowSeconds
) {
    private val counterSamples = ArrayDeque<Pair<Long, Float>>()
    private val currentSamples = ArrayDeque<Pair<Long, Float>>()
    private var integratedMah = 0f
    private var lastCurrentTimeMs: Long? = null

    fun add(timestampMs: Long, consumedMah: Float?) {
        if (consumedMah == null) return

        val last = counterSamples.lastOrNull()
        if (last != null) {
            // Deduplicate same timestamp
            if (timestampMs == last.first) return

            // Counter reset or time regression
            if (timestampMs < last.first || consumedMah < last.second) {
                counterSamples.clear()
            }
        }

        counterSamples.addLast(timestampMs to consumedMah)
        while (counterSamples.size > 1 && timestampMs - counterSamples.first().first > windowSeconds * 1_000L) {
            counterSamples.removeFirst()
        }
    }

    fun addCurrentSample(timestampMs: Long, currentA: Float?) {
        if (currentA == null || currentA <= 0f) {
            lastCurrentTimeMs = null
            return
        }

        val lastTime = lastCurrentTimeMs
        lastCurrentTimeMs = timestampMs

        if (lastTime != null && timestampMs > lastTime) {
            val dtSec = (timestampMs - lastTime) / 1000f
            // Only integrate across bounded intervals <= 3s (do not integrate across stale gap)
            if (dtSec in 0.01f..3.0f) {
                // delta_mAh = I_A * dt_sec / 3.6
                val deltaMah = currentA * dtSec / 3.6f
                integratedMah += deltaMah
                add(timestampMs, integratedMah)
            }
        }

        currentSamples.addLast(timestampMs to currentA)
        while (currentSamples.size > 1 && timestampMs - currentSamples.first().first > windowSeconds * 1_000L) {
            currentSamples.removeFirst()
        }
    }

    fun rateMahPerMin(): Float? {
        val first = counterSamples.firstOrNull()
        val last = counterSamples.lastOrNull()

        if (first != null && last != null) {
            val elapsedMs = last.first - first.first
            if (elapsedMs >= AppConfig.minimumHistoryMs && last.second >= first.second) {
                val rate = (last.second - first.second) / (elapsedMs / 60_000f)
                if (rate >= AppConfig.minimumRateMahPerMin) return rate
            }
        }

        // Provisional instantaneous present-load rate: I_A * 1000 / 60
        val lastCurrent = currentSamples.lastOrNull()?.second
        if (lastCurrent != null && lastCurrent > 0f) {
            return lastCurrent * 1000f / 60f
        }

        return null
    }

    fun clear() {
        counterSamples.clear()
        currentSamples.clear()
        integratedMah = 0f
        lastCurrentTimeMs = null
    }
}
