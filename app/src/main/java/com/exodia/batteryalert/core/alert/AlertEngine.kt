package com.exodia.batteryalert.core.alert

import com.exodia.batteryalert.core.config.AppConfig
import com.exodia.batteryalert.core.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AlertState(
    val active: ActiveAlert? = null,
    val cellFault: Boolean = false,
    val history: List<Pair<Long, AlertLevel>> = emptyList()
)

interface AlertOutput {
    fun onAlertChanged(previous: ActiveAlert?, current: ActiveAlert?)
    fun onCellFaultChanged(active: Boolean, reason: String? = null) {}
    fun onPeriodicStatus(analysis: BatteryAnalysis)
    fun release()
}

object NoOpAlertOutput : AlertOutput {
    override fun onAlertChanged(previous: ActiveAlert?, current: ActiveAlert?) = Unit
    override fun onCellFaultChanged(active: Boolean, reason: String?) = Unit
    override fun onPeriodicStatus(analysis: BatteryAnalysis) = Unit
    override fun release() = Unit
}

class AlertEngine(private val output: AlertOutput = NoOpAlertOutput) {
    private val _state = MutableStateFlow(AlertState())
    val state: StateFlow<AlertState> = _state.asStateFlow()

    private var pendingLevel: AlertLevel? = null
    private var pendingSince: Long? = null
    private var clearSince: Long? = null

    private var currentCellFault: Boolean = false
    private var cellFaultClearSince: Long? = null

    fun evaluate(
        analysis: BatteryAnalysis?,
        rtl: RtlAssessment?,
        connection: ConnectionState,
        now: Long
    ): AlertState {
        // Evaluate cell fault independently and immediately (FIX-13)
        val rawDelta = analysis?.cellDeltaV
        if (rawDelta != null && rawDelta > AppConfig.cellDeltaFaultV) {
            // Strict delta > 0.08V -> immediate fault entry
            if (!currentCellFault) {
                currentCellFault = true
                cellFaultClearSince = null
                output.onCellFaultChanged(true, "Cell voltage imbalance - land & inspect battery")
            }
        } else if (currentCellFault) {
            // Distinct clear threshold and time: delta <= 0.06V stable for 1.5s
            if (rawDelta != null && rawDelta <= AppConfig.deltaClearVoltageV && analysis.isCellsFresh) {
                val clearStart = cellFaultClearSince ?: now
                cellFaultClearSince = clearStart
                if (now - clearStart >= AppConfig.deltaClearMs) {
                    currentCellFault = false
                    cellFaultClearSince = null
                    output.onCellFaultChanged(false, null)
                }
            } else {
                cellFaultClearSince = null
            }
        }

        // Link loss handling: preserve severe state with link lost indicator (FIX-08 / FIX-13)
        val currentAlert = _state.value.active
        if (connection is ConnectionState.LinkLost || (analysis != null && analysis.isStale)) {
            pendingLevel = null
            pendingSince = null
            clearSince = null

            if (currentAlert != null && (currentAlert.level == AlertLevel.CRITICAL || currentAlert.level == AlertLevel.EMERGENCY)) {
                val reasons = (currentAlert.reasons + AlertReason.LINK_LOST).distinct()
                val retained = currentAlert.copy(reasons = reasons)
                return publish(retained, currentCellFault, now)
            } else {
                val alert = ActiveAlert(
                    level = AlertLevel.WARNING,
                    reasons = listOf(AlertReason.LINK_LOST),
                    title = "Telemetry lost",
                    message = "Last data unavailable",
                    sinceMs = currentAlert?.takeIf { it.level == AlertLevel.WARNING }?.sinceMs ?: now,
                    dismissible = false
                )
                return publish(alert, currentCellFault, now)
            }
        }

        if (analysis == null) {
            return _state.value
        }

        // Raw battery candidate levels
        val minCell = analysis.minCellV
        val percent = analysis.remainingPercent
        val isSag = analysis.isSagRapid
        val isRtlBelow = rtl?.belowRequired == true

        val candidates = mutableListOf<AlertReason>()
        val rawCandidate: AlertLevel = when {
            (minCell != null && minCell <= AppConfig.emergencyCellVoltageV) || isSag -> {
                if (minCell != null && minCell <= AppConfig.emergencyCellVoltageV) candidates += AlertReason.LOW_CELL_VOLTAGE_EMERGENCY
                if (isSag) candidates += AlertReason.RAPID_SAG
                AlertLevel.EMERGENCY
            }
            (minCell != null && minCell <= AppConfig.criticalCellVoltageV) || isRtlBelow -> {
                if (minCell != null && minCell <= AppConfig.criticalCellVoltageV) candidates += AlertReason.LOW_CELL_VOLTAGE_CRITICAL
                if (isRtlBelow) candidates += AlertReason.BELOW_DYNAMIC_RTL
                AlertLevel.CRITICAL
            }
            percent <= AppConfig.warningPercent || (minCell != null && minCell <= AppConfig.warningCellVoltageV) -> {
                if (percent <= AppConfig.warningPercent) candidates += AlertReason.LOW_PERCENT_20
                if (minCell != null && minCell <= AppConfig.warningCellVoltageV) candidates += AlertReason.LOW_CELL_VOLTAGE_WARN
                AlertLevel.WARNING
            }
            percent <= AppConfig.noticePercent -> {
                candidates += AlertReason.LOW_PERCENT_30
                AlertLevel.NOTICE
            }
            else -> AlertLevel.NONE
        }

        val currentLevel = currentAlert?.level ?: AlertLevel.NONE

        val resolvedLevel: AlertLevel = if (rawCandidate > currentLevel) {
            // Escalation
            if (rawCandidate == AlertLevel.EMERGENCY || rawCandidate == AlertLevel.CRITICAL) {
                // Immediate escalation for Critical and Emergency
                pendingLevel = null
                pendingSince = null
                clearSince = null
                rawCandidate
            } else {
                // Debounce for Notice / Warning
                if (pendingLevel == rawCandidate) {
                    val pSince = pendingSince ?: now
                    if (now - pSince >= AppConfig.alertDebounceMs) {
                        pendingLevel = null
                        pendingSince = null
                        rawCandidate
                    } else {
                        currentLevel
                    }
                } else {
                    pendingLevel = rawCandidate
                    pendingSince = now
                    currentLevel
                }
            }
        } else if (rawCandidate < currentLevel) {
            // Downgrade with reason-specific hysteresis and stable clear periods
            pendingLevel = null
            pendingSince = null

            val hysteresisEligible = when (currentLevel) {
                AlertLevel.EMERGENCY -> {
                    (minCell == null || minCell > AppConfig.emergencyCellVoltageV + AppConfig.alertHysteresisCellVoltageV) && !isSag
                }
                AlertLevel.CRITICAL -> {
                    (minCell == null || minCell > AppConfig.criticalCellVoltageV + AppConfig.alertHysteresisCellVoltageV) && !isRtlBelow
                }
                AlertLevel.WARNING -> {
                    percent > AppConfig.warningPercent + AppConfig.alertHysteresisPercent &&
                        (minCell == null || minCell > AppConfig.warningCellVoltageV + AppConfig.alertHysteresisCellVoltageV)
                }
                AlertLevel.NOTICE -> {
                    percent > AppConfig.noticePercent + AppConfig.alertHysteresisPercent
                }
                AlertLevel.NONE -> true
            }

            if (hysteresisEligible) {
                val requiredClearMs = if (currentLevel in setOf(AlertLevel.CRITICAL, AlertLevel.EMERGENCY)) {
                    AppConfig.criticalClearMs
                } else {
                    AppConfig.alertDebounceMs
                }
                val cSince = clearSince ?: now
                clearSince = cSince
                if (now - cSince >= requiredClearMs) {
                    clearSince = null
                    rawCandidate
                } else {
                    currentLevel
                }
            } else {
                clearSince = null
                currentLevel
            }
        } else {
            // Level stable
            pendingLevel = null
            pendingSince = null
            clearSince = null
            currentLevel
        }

        val nextAlert = when (resolvedLevel) {
            AlertLevel.NOTICE -> ActiveAlert(
                level = AlertLevel.NOTICE,
                reasons = candidates,
                title = "Notice",
                message = "Plan to return soon",
                sinceMs = currentAlert?.takeIf { it.level == AlertLevel.NOTICE }?.sinceMs ?: now,
                dismissible = false
            )
            AlertLevel.WARNING -> ActiveAlert(
                level = AlertLevel.WARNING,
                reasons = candidates,
                title = "Warning",
                message = "Warning, low battery",
                sinceMs = currentAlert?.takeIf { it.level == AlertLevel.WARNING }?.sinceMs ?: now,
                dismissible = false
            )
            AlertLevel.CRITICAL -> ActiveAlert(
                level = AlertLevel.CRITICAL,
                reasons = candidates,
                title = "Return now",
                message = "Mandatory RTL recommended!",
                sinceMs = currentAlert?.takeIf { it.level == AlertLevel.CRITICAL }?.sinceMs ?: now,
                dismissible = false
            )
            AlertLevel.EMERGENCY -> ActiveAlert(
                level = AlertLevel.EMERGENCY,
                reasons = candidates,
                title = "Land immediately",
                message = "Land immediately",
                sinceMs = currentAlert?.takeIf { it.level == AlertLevel.EMERGENCY }?.sinceMs ?: now,
                dismissible = false
            )
            AlertLevel.NONE -> null
        }

        return publish(nextAlert, currentCellFault, now)
    }

    private fun publish(next: ActiveAlert?, cellFault: Boolean, now: Long): AlertState {
        val old = _state.value.active
        val oldFault = _state.value.cellFault

        if (old?.level != next?.level || old?.reasons != next?.reasons) {
            output.onAlertChanged(old, next)
        }
        if (oldFault != cellFault) {
            output.onCellFaultChanged(cellFault, if (cellFault) "Cell voltage imbalance - land & inspect battery" else null)
        }

        val history = if (old?.level != next?.level) {
            (_state.value.history + (now to (next?.level ?: AlertLevel.NONE))).takeLast(20)
        } else {
            _state.value.history
        }

        val newState = AlertState(next, cellFault, history)
        _state.value = newState
        return newState
    }

    fun reset() {
        pendingLevel = null
        pendingSince = null
        clearSince = null
        currentCellFault = false
        cellFaultClearSince = null
        val old = _state.value.active
        _state.value = AlertState()
        if (old != null) {
            output.onAlertChanged(old, null)
        }
    }
}
