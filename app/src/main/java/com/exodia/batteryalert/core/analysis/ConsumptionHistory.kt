package com.exodia.batteryalert.core.analysis

class ConsumptionHistory(private val windowSeconds: Long) {
    private val samples = ArrayDeque<Pair<Long, Float>>()
    fun add(timestampMs: Long, consumedMah: Float?) {
        if (consumedMah == null) return
        samples.addLast(timestampMs to consumedMah)
        while (samples.size > 1 && timestampMs - samples.first().first > windowSeconds * 1_000L) samples.removeFirst()
    }
    fun rateMahPerMin(): Float? {
        val first = samples.firstOrNull() ?: return null
        val last = samples.lastOrNull() ?: return null
        val elapsedMs = last.first - first.first
        if (elapsedMs < 10_000L || last.second < first.second) return null
        return (last.second - first.second) / (elapsedMs / 60_000f)
    }
    fun clear() = samples.clear()
}
