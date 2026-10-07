package com.exodia.batteryalert.core.telemetry

import com.exodia.batteryalert.core.config.AppConfig
import com.exodia.batteryalert.core.model.BatteryFrame

/**
 * Field-level accumulator keyed by (systemId, batteryId).
 * Enforces:
 *   - BATTERY_STATUS is primary for its pack fields.
 *   - SYS_STATUS is a bounded fallback: contributes ONLY missing or stale aggregate fields.
 *   - SYS_STATUS never erases fresh cell voltages, temperature, or consumed mAh.
 *   - Independent field-level freshness tracking via monotonic clock.
 *   - Vehicle system and pack isolation.
 */
class BatteryStateAccumulator(
    val targetSystemId: Int = 1,
    val targetBatteryId: Int? = null,
) {
    private class PackFields {
        var lastUpdateMonotonicMs: Long = 0L

        var cellVoltagesV: List<Float> = emptyList()
        var cellsMonotonicMs: Long = 0L

        var packVoltageV: Float? = null
        var packMonotonicMs: Long = 0L
        var isPackFromPrimary: Boolean = false

        var currentA: Float? = null
        var currentMonotonicMs: Long = 0L
        var isCurrentFromPrimary: Boolean = false

        var consumedMah: Float? = null
        var consumedMonotonicMs: Long = 0L

        var temperatureC: Float? = null
        var tempMonotonicMs: Long = 0L

        var remainingPercent: Int? = null
        var percentMonotonicMs: Long = 0L
        var isPercentFromPrimary: Boolean = false

        var isAggregateOnly: Boolean = false
        var faultBitmask: Long = 0L
    }

    private val packs = mutableMapOf<Int, PackFields>()
    private var activeBatteryId: Int = 0

    @Synchronized
    fun onBatteryFrame(frame: BatteryFrame, nowMonotonicMs: Long): BatteryFrame? {
        if (frame.systemId != targetSystemId) {
            return null
        }

        val bId = frame.batteryId
        if (targetBatteryId != null && bId != targetBatteryId) {
            return null
        }

        val pack = packs.getOrPut(bId) { PackFields() }
        activeBatteryId = bId
        pack.lastUpdateMonotonicMs = nowMonotonicMs

        if (!frame.isFromFallback) {
            // BATTERY_STATUS primary fields
            if (frame.cellVoltagesV.isNotEmpty()) {
                pack.cellVoltagesV = frame.cellVoltagesV
                pack.cellsMonotonicMs = nowMonotonicMs
            }
            if (frame.packVoltageV != null) {
                pack.packVoltageV = frame.packVoltageV
                pack.packMonotonicMs = nowMonotonicMs
                pack.isPackFromPrimary = true
            }
            if (frame.currentA != null) {
                pack.currentA = frame.currentA
                pack.currentMonotonicMs = nowMonotonicMs
                pack.isCurrentFromPrimary = true
            }
            if (frame.consumedMah != null) {
                pack.consumedMah = frame.consumedMah
                pack.consumedMonotonicMs = nowMonotonicMs
            }
            if (frame.temperatureC != null) {
                pack.temperatureC = frame.temperatureC
                pack.tempMonotonicMs = nowMonotonicMs
            }
            if (frame.remainingPercent != null) {
                pack.remainingPercent = frame.remainingPercent
                pack.percentMonotonicMs = nowMonotonicMs
                pack.isPercentFromPrimary = true
            }
            pack.isAggregateOnly = frame.isAggregateOnly
            pack.faultBitmask = frame.faultBitmask
        } else {
            // SYS_STATUS fallback fields: only fill if primary is missing or stale
            val maxAge = AppConfig.batteryFieldMaxAgeMs

            val packStale = !pack.isPackFromPrimary || (nowMonotonicMs - pack.packMonotonicMs > maxAge)
            if (packStale && frame.packVoltageV != null) {
                pack.packVoltageV = frame.packVoltageV
                pack.packMonotonicMs = nowMonotonicMs
                pack.isPackFromPrimary = false
            }

            val currentStale = !pack.isCurrentFromPrimary || (nowMonotonicMs - pack.currentMonotonicMs > maxAge)
            if (currentStale && frame.currentA != null) {
                pack.currentA = frame.currentA
                pack.currentMonotonicMs = nowMonotonicMs
                pack.isCurrentFromPrimary = false
            }

            val percentStale = !pack.isPercentFromPrimary || (nowMonotonicMs - pack.percentMonotonicMs > maxAge)
            if (percentStale && frame.remainingPercent != null) {
                pack.remainingPercent = frame.remainingPercent
                pack.percentMonotonicMs = nowMonotonicMs
                pack.isPercentFromPrimary = false
            }
        }

        return buildConsolidatedFrame(pack, bId, nowMonotonicMs, frame.timestampMs)
    }

    @Synchronized
    fun checkFreshness(nowMonotonicMs: Long): BatteryFrame? {
        val pack = packs[activeBatteryId] ?: return null
        return buildConsolidatedFrame(pack, activeBatteryId, nowMonotonicMs, System.currentTimeMillis())
    }

    private fun buildConsolidatedFrame(
        pack: PackFields,
        batteryId: Int,
        nowMonotonicMs: Long,
        wallTimestampMs: Long
    ): BatteryFrame {
        val maxAge = AppConfig.batteryFieldMaxAgeMs

        val cellsFresh = (nowMonotonicMs - pack.cellsMonotonicMs <= maxAge) && pack.cellVoltagesV.isNotEmpty()
        val packFresh = (nowMonotonicMs - pack.packMonotonicMs <= maxAge)
        val currentFresh = (nowMonotonicMs - pack.currentMonotonicMs <= maxAge)
        val percentFresh = (nowMonotonicMs - pack.percentMonotonicMs <= maxAge)
        val tempFresh = (nowMonotonicMs - pack.tempMonotonicMs <= maxAge)
        val consumedFresh = (nowMonotonicMs - pack.consumedMonotonicMs <= maxAge)

        val anyBatteryDataFresh = cellsFresh || packFresh || currentFresh || percentFresh

        return BatteryFrame(
            timestampMs = wallTimestampMs,
            cellVoltagesV = pack.cellVoltagesV,
            packVoltageV = if (packFresh) pack.packVoltageV else null,
            currentA = if (currentFresh) pack.currentA else null,
            consumedMah = if (consumedFresh) pack.consumedMah else null,
            temperatureC = if (tempFresh) pack.temperatureC else null,
            remainingPercent = if (percentFresh) pack.remainingPercent else null,
            batteryId = batteryId,
            isAggregateOnly = pack.isAggregateOnly || (!cellsFresh && packFresh),
            systemId = targetSystemId,
            componentId = 1,
            stale = !anyBatteryDataFresh,
            receivedAtMonotonicMs = nowMonotonicMs,
            faultBitmask = pack.faultBitmask,
            isCurrentKnown = currentFresh && pack.currentA != null,
            isFromFallback = !pack.isPackFromPrimary,
            isCellsFresh = cellsFresh
        )
    }

    @Synchronized
    fun reset() {
        packs.clear()
        activeBatteryId = 0
    }
}
