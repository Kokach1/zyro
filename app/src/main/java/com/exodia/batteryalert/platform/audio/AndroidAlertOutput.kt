package com.exodia.batteryalert.platform.audio

import android.content.Context
import com.exodia.batteryalert.core.alert.AlertOutput
import com.exodia.batteryalert.core.alert.AlertOutputScheduler
import com.exodia.batteryalert.core.model.ActiveAlert
import com.exodia.batteryalert.core.model.BatteryAnalysis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Production Android AlertOutput implementation.
 * Integrates TonePlayer, HapticController, and SpeechCoordinator
 * via AlertOutputScheduler.
 */
class AndroidAlertOutput(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) : AlertOutput {

    private val tonePlayer = TonePlayer(scope)
    private val hapticController = HapticController(context)
    private val speechCoordinator = SpeechCoordinator(context)

    private val scheduler = AlertOutputScheduler(
        tonePort = tonePlayer,
        hapticPort = hapticController,
        speechPort = speechCoordinator,
        scope = scope
    )

    override fun onAlertChanged(previous: ActiveAlert?, current: ActiveAlert?) {
        scheduler.onAlertChanged(previous, current)
    }

    override fun onCellFaultChanged(active: Boolean, reason: String?) {
        scheduler.onCellFaultChanged(active, reason)
    }

    override fun onPeriodicStatus(analysis: BatteryAnalysis) {
        scheduler.onPeriodicStatus(analysis)
    }

    fun startPeriodicStatusLoop(intervalSeconds: Long, getLatestAnalysis: () -> BatteryAnalysis?) {
        scheduler.startPeriodicStatusLoop(intervalSeconds, getLatestAnalysis)
    }

    override fun release() {
        scheduler.release()
        tonePlayer.release()
        hapticController.stopVibration()
        speechCoordinator.release()
        scope.cancel()
    }
}
