package com.exodia.batteryalert.core.alert

import com.exodia.batteryalert.core.config.AppConfig
import com.exodia.batteryalert.core.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AlertState(val active: ActiveAlert? = null, val cellFault: Boolean = false, val history: List<Pair<Long, AlertLevel>> = emptyList())
interface AlertOutput { fun onAlertChanged(previous: ActiveAlert?, current: ActiveAlert?); fun onPeriodicStatus(analysis: BatteryAnalysis); fun release() }
object NoOpAlertOutput : AlertOutput { override fun onAlertChanged(previous: ActiveAlert?, current: ActiveAlert?) = Unit; override fun onPeriodicStatus(analysis: BatteryAnalysis) = Unit; override fun release() = Unit }

class AlertEngine(private val output: AlertOutput = NoOpAlertOutput) {
    private val _state = MutableStateFlow(AlertState())
    val state: StateFlow<AlertState> = _state.asStateFlow()
    private var pendingSince: Long? = null
    private var clearSince: Long? = null
    fun evaluate(analysis: BatteryAnalysis?, rtl: RtlAssessment?, connection: ConnectionState, now: Long): AlertState {
        if (connection is ConnectionState.LinkLost) return publish(alert(AlertLevel.WARNING, listOf(AlertReason.LINK_LOST), "Telemetry lost", "Last data unavailable", now, false), false, now)
        if (analysis == null) return _state.value
        val cellFault = analysis.cellDeltaV > AppConfig.cellDeltaFaultV
        val candidates = mutableListOf<AlertReason>()
        val level = when {
            analysis.minCellV <= AppConfig.emergencyCellVoltageV || analysis.isSagRapid -> { if (analysis.minCellV <= AppConfig.emergencyCellVoltageV) candidates += AlertReason.LOW_CELL_VOLTAGE_EMERGENCY; if (analysis.isSagRapid) candidates += AlertReason.RAPID_SAG; AlertLevel.EMERGENCY }
            analysis.minCellV <= AppConfig.criticalCellVoltageV || rtl?.belowRequired == true -> { if (analysis.minCellV <= AppConfig.criticalCellVoltageV) candidates += AlertReason.LOW_CELL_VOLTAGE_CRITICAL; if (rtl?.belowRequired == true) candidates += AlertReason.BELOW_DYNAMIC_RTL; AlertLevel.CRITICAL }
            analysis.remainingPercent <= AppConfig.warningPercent || analysis.minCellV <= AppConfig.warningCellVoltageV -> { if (analysis.remainingPercent <= AppConfig.warningPercent) candidates += AlertReason.LOW_PERCENT_20; if (analysis.minCellV <= AppConfig.warningCellVoltageV) candidates += AlertReason.LOW_CELL_VOLTAGE_WARN; AlertLevel.WARNING }
            analysis.remainingPercent <= AppConfig.noticePercent -> { candidates += AlertReason.LOW_PERCENT_30; AlertLevel.NOTICE }
            else -> AlertLevel.NONE
        }
        val current = _state.value.active
        if (level == AlertLevel.NONE) {
            if (current?.level in setOf(AlertLevel.CRITICAL, AlertLevel.EMERGENCY)) {
                clearSince = clearSince ?: now
                if (now - (clearSince ?: now) < AppConfig.criticalClearMs) return _state.value
            }
            pendingSince = null; clearSince = null
            return publish(if (cellFault) alert(AlertLevel.NOTICE, listOf(AlertReason.CELL_IMBALANCE), "Cell imbalance", "Cell voltage imbalance - land & inspect battery", now, true) else null, cellFault, now)
        }
        clearSince = null
        val immediate = level == AlertLevel.CRITICAL || level == AlertLevel.EMERGENCY
        if (!immediate && current?.level != level) { pendingSince = pendingSince ?: now; if (now - (pendingSince ?: now) < AppConfig.alertDebounceMs) return _state.value }
        pendingSince = null
        val alert = when (level) {
            AlertLevel.NOTICE -> alert(level, candidates, "Battery 30%", "Plan to return soon", now, true)
            AlertLevel.WARNING -> alert(level, candidates, "Low battery", "Warning, low battery", now, true)
            AlertLevel.CRITICAL -> alert(level, candidates, "Return now", "Mandatory RTL recommended!", now, false)
            AlertLevel.EMERGENCY -> alert(level, candidates, "Land immediately", "Land immediately", now, false)
            else -> null
        }
        return publish(alert, cellFault, now)
    }
    private fun alert(level: AlertLevel, reasons: List<AlertReason>, title: String, message: String, now: Long, dismissible: Boolean) = ActiveAlert(level, reasons, title, message, _state.value.active?.takeIf { it.level == level }?.sinceMs ?: now, dismissible)
    private fun publish(next: ActiveAlert?, cellFault: Boolean, now: Long): AlertState {
        val old = _state.value.active
        if (old?.level != next?.level) output.onAlertChanged(old, next)
        val history = if (old?.level != next?.level) (_state.value.history + (now to (next?.level ?: AlertLevel.NONE))).takeLast(20) else _state.value.history
        return AlertState(next, cellFault, history).also { _state.value = it }
    }
}
