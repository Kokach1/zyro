package com.exodia.batteryalert

import com.exodia.batteryalert.core.alert.*
import com.exodia.batteryalert.core.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OutputAndServiceTest {

    private class TestTonePort : TonePort {
        var chimeCount = 0
        var sirenActive = false
        override fun playChime() { chimeCount++ }
        override fun startSiren() { sirenActive = true }
        override fun stopSiren() { sirenActive = false }
    }

    private class TestHapticPort : HapticPort {
        var noticeCount = 0
        var warningCount = 0
        var severeActive = false
        override fun vibrateNotice() { noticeCount++ }
        override fun vibrateWarning() { warningCount++ }
        override fun startSevereVibration() { severeActive = true }
        override fun stopVibration() { severeActive = false }
    }

    private class TestSpeechPort : SpeechPort {
        val spoken = mutableListOf<Pair<String, Boolean>>()
        var isSpeakingFlag = false
        override fun speak(text: String, isUrgent: Boolean) {
            spoken.add(text to isUrgent)
        }
        override fun isSpeaking(): Boolean = isSpeakingFlag
        override fun stop() {
            spoken.clear()
            isSpeakingFlag = false
        }
    }

    @Test
    fun noticeOnceOnEntryWithoutRepetitionStorm() = runTest {
        val tone = TestTonePort()
        val haptic = TestHapticPort()
        val speech = TestSpeechPort()
        val scheduler = AlertOutputScheduler(tone, haptic, speech, this)

        val noticeAlert = ActiveAlert(AlertLevel.NOTICE, listOf(AlertReason.LOW_PERCENT_30), "Notice", "Plan to return soon", 1000L, false)

        // First entry
        scheduler.onAlertChanged(null, noticeAlert)
        assertEquals(1, tone.chimeCount)
        assertEquals(1, haptic.noticeCount)

        // Subsequent frames at same level
        scheduler.onAlertChanged(noticeAlert, noticeAlert)
        assertEquals(1, tone.chimeCount)
        assertEquals(1, haptic.noticeCount)
    }

    @Test
    fun warningRepeatsVoiceAndVibration() = runTest {
        val tone = TestTonePort()
        val haptic = TestHapticPort()
        val speech = TestSpeechPort()
        val scheduler = AlertOutputScheduler(tone, haptic, speech, this)

        val warnAlert = ActiveAlert(AlertLevel.WARNING, listOf(AlertReason.LOW_PERCENT_20), "Warning", "Warning, low battery", 1000L, false)

        scheduler.onAlertChanged(null, warnAlert)
        assertEquals(1, haptic.warningCount)
        assertEquals("Warning, low battery", speech.spoken.first().first)

        // Advance 10s (warningRepeatMs) and run current tasks
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(2, haptic.warningCount)
        assertEquals(2, speech.spoken.size)

        // Advance another 10s
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(3, haptic.warningCount)
        assertEquals(3, speech.spoken.size)

        scheduler.release()
    }

    @Test
    fun criticalStartsSirenAndSevereVibration() = runTest {
        val tone = TestTonePort()
        val haptic = TestHapticPort()
        val speech = TestSpeechPort()
        val scheduler = AlertOutputScheduler(tone, haptic, speech, this)

        val critAlert = ActiveAlert(AlertLevel.CRITICAL, listOf(AlertReason.BELOW_DYNAMIC_RTL), "Critical", "Mandatory RTL recommended!", 1000L, false)

        scheduler.onAlertChanged(null, critAlert)
        assertTrue(tone.sirenActive)
        assertTrue(haptic.severeActive)
        assertTrue(speech.spoken.any { it.first == "Mandatory RTL recommended!" && it.second })

        scheduler.release()
        assertFalse(tone.sirenActive)
        assertFalse(haptic.severeActive)
    }

    @Test
    fun emergencyPriorityOverridesAndRepeats() = runTest {
        val tone = TestTonePort()
        val haptic = TestHapticPort()
        val speech = TestSpeechPort()
        val scheduler = AlertOutputScheduler(tone, haptic, speech, this)

        val emergAlert = ActiveAlert(AlertLevel.EMERGENCY, listOf(AlertReason.LOW_CELL_VOLTAGE_EMERGENCY), "Emergency", "Land immediately", 1000L, false)

        scheduler.onAlertChanged(null, emergAlert)
        assertTrue(tone.sirenActive)
        assertTrue(haptic.severeActive)
        assertEquals("Land immediately", speech.spoken.first().first)
        assertTrue(speech.spoken.first().second) // isUrgent = true

        // Advance 3.5s (2s + 1s emergencyRepeatGapMs)
        advanceTimeBy(3_500L)
        assertTrue(speech.spoken.size >= 2)
        assertEquals("Land immediately", speech.spoken.last().first)

        scheduler.release()
    }

    @Test
    fun cellFaultChangeWithoutTierChangeSpeaksAction() = runTest {
        val tone = TestTonePort()
        val haptic = TestHapticPort()
        val speech = TestSpeechPort()
        val scheduler = AlertOutputScheduler(tone, haptic, speech, this)

        // Battery tier remains NONE, but cell fault activates
        scheduler.onCellFaultChanged(active = true, reason = "Cell voltage imbalance - land & inspect battery")
        assertEquals(1, haptic.noticeCount)
        assertEquals("Cell voltage imbalance - land & inspect battery", speech.spoken.first().first)

        // Fault clears
        scheduler.onCellFaultChanged(active = false, reason = null)
        scheduler.release()
    }

    @Test
    fun periodic60sFreshSpeechAndSuppressionDuringDanger() = runTest {
        val tone = TestTonePort()
        val haptic = TestHapticPort()
        val speech = TestSpeechPort()
        val scheduler = AlertOutputScheduler(tone, haptic, speech, this)

        val analysis = BatteryAnalysis(
            cellCount = 14,
            cellVoltagesV = List(14) { 3.82f },
            restCellVoltagesV = List(14) { 3.82f },
            minCellV = 3.82f,
            maxCellV = 3.82f,
            avgCellV = 3.82f,
            cellDeltaV = 0.0f,
            packVoltageV = 53.48f,
            currentA = 15f,
            remainingPercent = 65,
            consumptionMahPerMin = 1000f,
            minutesRemaining = 18f,
            temperatureC = 30f,
            isSagRapid = false,
            flightElapsedSec = 120
        )

        var latest: BatteryAnalysis? = analysis
        scheduler.startPeriodicStatusLoop(intervalSeconds = 60) { latest }

        // Advance 60s
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(1, speech.spoken.size)
        assertTrue(speech.spoken.first().first.contains("Battery 65 percent"))

        // When Critical enters, periodic status is suppressed
        val critAlert = ActiveAlert(AlertLevel.CRITICAL, listOf(AlertReason.LOW_CELL_VOLTAGE_CRITICAL), "Critical", "Mandatory RTL recommended!", 1000L, false)
        scheduler.onAlertChanged(null, critAlert)
        speech.spoken.clear()

        // Advance another 60s
        advanceTimeBy(60_000L)
        runCurrent()
        // No periodic status spoken during Critical!
        assertFalse(speech.spoken.any { it.first.contains("Cell average") })

        scheduler.release()
    }

    @Test
    fun stopClearsAllOutputs() = runTest {
        val tone = TestTonePort()
        val haptic = TestHapticPort()
        val speech = TestSpeechPort()
        val scheduler = AlertOutputScheduler(tone, haptic, speech, this)

        val warnAlert = ActiveAlert(AlertLevel.WARNING, listOf(AlertReason.LOW_PERCENT_20), "Warning", "Warning, low battery", 1000L, false)
        scheduler.onAlertChanged(null, warnAlert)
        tone.startSiren()
        haptic.startSevereVibration()

        scheduler.release()

        assertFalse(tone.sirenActive)
        assertFalse(haptic.severeActive)
    }

    @Test
    fun singleServiceSessionPreservedInContainer() = runTest {
        val appContainer = com.exodia.batteryalert.AppContainer()
        assertNull(appContainer.currentTransport)

        // Simulator session started
        val sim = appContainer.startSimulator()
        assertSame(sim, appContainer.currentTransport)

        // Container retains same transport instance across lookups
        assertSame(sim, appContainer.currentTransport)

        appContainer.stopTransport()
        assertNull(appContainer.currentTransport)
    }
}
