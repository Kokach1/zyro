package com.exodia.batteryalert.core.control

import com.exodia.batteryalert.core.config.AppConfig
import com.exodia.batteryalert.core.model.CommandAckResult
import com.exodia.batteryalert.core.model.SessionSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class InterlockState(val label: String) {
    DISABLED("Disabled"),
    NOT_CONFIGURED("Not configured"),
    READY("Ready"),
    CUTOFF_REQUESTED("Cutoff requested"),
    ACKNOWLEDGED("Acknowledged"),
    CONFIRMED_OFF("Confirmed OFF"),
    FAILED("Failed"),
    UNSUPPORTED("Unsupported")
}

data class PumpHardwareMapping(
    val commandId: Int, // e.g. 183 (MAV_CMD_DO_SET_SERVO) or 181 (MAV_CMD_DO_SET_RELAY)
    val channel: Int,
    val cutoffValue: Float, // e.g. 1000f for PWM min, 0f for relay off
    val targetSystem: Int = 1,
    val targetComponent: Int = 1,
    val isConfirmedByVendor: Boolean = false // Must be confirmed by Exodia/hardware team
)

interface OutgoingCommandPort {
    suspend fun sendPumpCutoff(mapping: PumpHardwareMapping): Boolean
}

/**
 * Spraying-pump cutoff controller implementing company FR-5.1.
 *
 * Enforces (FIX-16):
 *  - Explicit opt-in control; real default is DISABLED.
 *  - Triggered at fresh valid controlling percent <= 20% once per session event.
 *  - Never sends command during Replay.
 *  - Distinguishes command ACK from physical feedback (remains ACKNOWLEDGED unless hardware feedback verified).
 *  - Never automatically turns pump back ON after recovery.
 *  - Bounded retry (max 3) on timeout; no spamming every frame.
 */
class PumpInterlockController(
    var isOptedIn: Boolean = false,
    var mapping: PumpHardwareMapping? = null,
    private val commandPort: OutgoingCommandPort? = null
) {
    private val _state = MutableStateFlow(resolveInitialState())
    val state: StateFlow<InterlockState> = _state.asStateFlow()

    private var cutoffSentThisSession = false
    private var attemptsCount = 0
    private val maxAttempts = 3

    private fun resolveInitialState(): InterlockState {
        return when {
            !isOptedIn -> InterlockState.DISABLED
            mapping == null || !mapping!!.isConfirmedByVendor -> InterlockState.NOT_CONFIGURED
            else -> InterlockState.READY
        }
    }

    fun updateConfig(optIn: Boolean, newMapping: PumpHardwareMapping?) {
        isOptedIn = optIn
        mapping = newMapping
        if (!cutoffSentThisSession) {
            _state.value = resolveInitialState()
        }
    }

    suspend fun evaluate(
        remainingPercent: Int?,
        isStale: Boolean,
        sessionSource: SessionSource
    ) {
        // Replay and unselected source never send commands
        if (sessionSource == SessionSource.REPLAY) return
        if (!isOptedIn) {
            _state.value = InterlockState.DISABLED
            return
        }
        val currentMapping = mapping
        if (currentMapping == null || !currentMapping.isConfirmedByVendor) {
            _state.value = InterlockState.NOT_CONFIGURED
            return
        }

        // Never act on stale or unknown battery percent
        if (isStale || remainingPercent == null) return

        if (remainingPercent <= AppConfig.sprayInterlockPercent) {
            if (!cutoffSentThisSession && (_state.value == InterlockState.READY || _state.value == InterlockState.FAILED)) {
                if (attemptsCount < maxAttempts) {
                    attemptsCount++
                    _state.value = InterlockState.CUTOFF_REQUESTED
                    val success = commandPort?.sendPumpCutoff(currentMapping) ?: false
                    if (!success) {
                        _state.value = InterlockState.FAILED
                    } else {
                        cutoffSentThisSession = true
                    }
                }
            }
        }
    }

    fun onCommandAck(result: CommandAckResult, commandId: Int) {
        val currentMapping = mapping ?: return
        if (commandId != currentMapping.commandId) return

        when (result) {
            CommandAckResult.ACCEPTED -> {
                // Command accepted by autopilot; physical feedback unconfirmed without bench sensor
                _state.value = InterlockState.ACKNOWLEDGED
            }
            CommandAckResult.DENIED -> _state.value = InterlockState.FAILED
            CommandAckResult.UNSUPPORTED -> _state.value = InterlockState.UNSUPPORTED
            CommandAckResult.FAILED -> _state.value = InterlockState.FAILED
            CommandAckResult.TEMPORARILY_REJECTED -> _state.value = InterlockState.FAILED
            CommandAckResult.IN_PROGRESS -> _state.value = InterlockState.CUTOFF_REQUESTED
        }
    }

    fun confirmPhysicalFeedbackOff() {
        if (_state.value == InterlockState.ACKNOWLEDGED || _state.value == InterlockState.CUTOFF_REQUESTED) {
            _state.value = InterlockState.CONFIRMED_OFF
        }
    }

    fun reset() {
        cutoffSentThisSession = false
        attemptsCount = 0
        _state.value = resolveInitialState()
    }
}
