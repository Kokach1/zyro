package com.exodia.batteryalert

import android.app.Application
import com.exodia.batteryalert.core.config.TransportConfig
import com.exodia.batteryalert.core.config.TransportKind
import com.exodia.batteryalert.core.transport.SimulatorTransport
import com.exodia.batteryalert.core.transport.TelemetryTransport
import com.exodia.batteryalert.core.transport.TransportFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BatteryAlertApp : Application() {
    val container by lazy { AppContainer() }
}

/**
 * Application-level session owner.
 *
 * Cold launch: no active transport, no synthetic data.
 * Startup behaviour per spec: real Connection/Setup UI is shown.
 * Simulator starts only from the explicit Simulator button.
 *
 * [activeTransport] is null until the user presses Start (real) or Simulator button.
 * Activity reopens/rotations observe the existing session via this container.
 */
class AppContainer {
    private val _activeTransport = MutableStateFlow<TelemetryTransport?>(null)
    val activeTransport: StateFlow<TelemetryTransport?> = _activeTransport.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Current active transport (nullable — null on cold launch). */
    val currentTransport: TelemetryTransport? get() = _activeTransport.value

    /** Convenience for UI that needs simulator-specific controls. */
    val activeSimulator: SimulatorTransport? get() = _activeTransport.value as? SimulatorTransport

    /**
     * Start a real transport from validated config.
     * Stops any existing session first. Does NOT silently fall back to simulator.
     * Returns Result.failure with an actionable message on config/transport error.
     */
    suspend fun startTransport(config: TransportConfig): Result<TelemetryTransport> {
        stopTransport()
        val result = TransportFactory.create(config)
        if (result.isFailure) {
            _lastError.value = result.exceptionOrNull()?.message
            return result
        }
        val transport = result.getOrThrow()
        _activeTransport.value = transport
        _lastError.value = null
        transport.start()
        return Result.success(transport)
    }

    /** Start the Simulator transport explicitly (from Simulator button). */
    suspend fun startSimulator(): SimulatorTransport {
        stopTransport()
        val sim = TransportFactory.createSimulator()
        _activeTransport.value = sim
        _lastError.value = null
        sim.start()
        return sim
    }

    /** Stop the current transport and clear session. */
    suspend fun stopTransport() {
        _activeTransport.value?.stop()
        _activeTransport.value = null
    }
}

