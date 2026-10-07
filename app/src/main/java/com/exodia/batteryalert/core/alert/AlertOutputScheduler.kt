package com.exodia.batteryalert.core.alert

import com.exodia.batteryalert.core.config.AppConfig
import com.exodia.batteryalert.core.model.ActiveAlert
import com.exodia.batteryalert.core.model.AlertLevel
import com.exodia.batteryalert.core.model.BatteryAnalysis
import kotlinx.coroutines.*

interface TonePort {
    fun playChime()
    fun startSiren()
    fun stopSiren()
}

interface HapticPort {
    fun vibrateNotice()
    fun vibrateWarning()
    fun startSevereVibration()
    fun stopVibration()
}

interface SpeechPort {
    fun speak(text: String, isUrgent: Boolean)
    fun isSpeaking(): Boolean
    fun stop()
}

/**
 * Output scheduler implementing the FRD alert matrix and audio/haptic/TTS policies.
 *
 * Enforces (FIX-14, FIX-15):
 *  - Priority: Emergency > Critical > Warning > Cell fault / Notice > periodic battery telemetry.
 *  - Notice: Single chime + short vibration on entry (not every frame).
 *  - Warning: Repeated voice alert ("Warning, low battery") every 10s.
 *  - Critical: Sustained high-pitch siren + strongest supported vibration + RTL voice alert.
 *  - Emergency: Land immediately repeated with controlled gaps (1s) and continuous vibration.
 *  - Cell fault: Spoken action prompt ("Cell voltage imbalance - land & inspect battery") independent of tier.
 *  - Periodic battery status: Every 60s ("Battery X percent, Cell average Y volts"), ducked during danger.
 *  - Cancellation: Stop clears all loops, players, vibration, and speech.
 */
class AlertOutputScheduler(
    val tonePort: TonePort,
    val hapticPort: HapticPort,
    val speechPort: SpeechPort,
    private val scope: CoroutineScope,
    private val clockMonotonicMs: () -> Long = { System.nanoTime() / 1_000_000L }
) : AlertOutput {

    private var repeatJob: Job? = null
    private var periodicTtsJob: Job? = null

    private var currentLevel: AlertLevel = AlertLevel.NONE
    private var isCellFaultActive: Boolean = false

    override fun onAlertChanged(previous: ActiveAlert?, current: ActiveAlert?) {
        val prevLevel = previous?.level ?: AlertLevel.NONE
        val newLevel = current?.level ?: AlertLevel.NONE
        currentLevel = newLevel

        if (prevLevel == newLevel) return

        // Cancel previous repeating loops
        repeatJob?.cancel()
        repeatJob = null

        when (newLevel) {
            AlertLevel.NONE -> {
                tonePort.stopSiren()
                hapticPort.stopVibration()
            }
            AlertLevel.NOTICE -> {
                tonePort.stopSiren()
                hapticPort.stopVibration()
                // Single chime and short vibration on entry
                tonePort.playChime()
                hapticPort.vibrateNotice()
            }
            AlertLevel.WARNING -> {
                tonePort.stopSiren()
                hapticPort.vibrateWarning()
                // Speak immediately on entry, then repeat every warningRepeatMs (10s)
                speechPort.speak("Warning, low battery", isUrgent = false)
                repeatJob = scope.launch {
                    while (isActive) {
                        delay(AppConfig.warningRepeatMs)
                        hapticPort.vibrateWarning()
                        speechPort.speak("Warning, low battery", isUrgent = false)
                    }
                }
            }
            AlertLevel.CRITICAL -> {
                tonePort.startSiren()
                hapticPort.startSevereVibration()
                speechPort.speak("Mandatory RTL recommended!", isUrgent = true)
                repeatJob = scope.launch {
                    while (isActive) {
                        delay(12_000L)
                        speechPort.speak("Mandatory RTL recommended!", isUrgent = true)
                    }
                }
            }
            AlertLevel.EMERGENCY -> {
                // Highest priority!
                tonePort.startSiren()
                hapticPort.startSevereVibration()
                speechPort.speak("Land immediately", isUrgent = true)
                repeatJob = scope.launch {
                    while (isActive) {
                        // Wait for speech to complete or controlled gap
                        delay(2_000L + AppConfig.emergencyRepeatGapMs)
                        speechPort.speak("Land immediately", isUrgent = true)
                    }
                }
            }
        }
    }

    override fun onCellFaultChanged(active: Boolean, reason: String?) {
        isCellFaultActive = active
        if (active) {
            hapticPort.vibrateNotice()
            speechPort.speak(reason ?: "Cell voltage imbalance - land & inspect battery", isUrgent = currentLevel >= AlertLevel.CRITICAL)
        }
    }

    override fun onPeriodicStatus(analysis: BatteryAnalysis) {
        // Skip periodic speech during Emergency, Critical, or if data is stale
        if (currentLevel in setOf(AlertLevel.CRITICAL, AlertLevel.EMERGENCY) || analysis.isStale) {
            return
        }

        val pct = analysis.remainingPercent
        val avg = analysis.avgCellV
        val text = if (avg != null && avg > 0f) {
            "Battery $pct percent, Cell average ${"%.2f".format(avg)} volts"
        } else {
            "Battery $pct percent"
        }
        speechPort.speak(text, isUrgent = false)
    }

    fun startPeriodicStatusLoop(intervalSeconds: Long = AppConfig.ttsIntervalSeconds, getLatestAnalysis: () -> BatteryAnalysis?) {
        periodicTtsJob?.cancel()
        periodicTtsJob = scope.launch {
            while (isActive) {
                delay(intervalSeconds * 1000L)
                val analysis = getLatestAnalysis()
                if (analysis != null) {
                    onPeriodicStatus(analysis)
                }
            }
        }
    }

    override fun release() {
        repeatJob?.cancel()
        repeatJob = null
        periodicTtsJob?.cancel()
        periodicTtsJob = null
        tonePort.stopSiren()
        hapticPort.stopVibration()
        speechPort.stop()
    }
}
