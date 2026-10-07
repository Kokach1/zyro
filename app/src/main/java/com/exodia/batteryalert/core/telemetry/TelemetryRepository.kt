package com.exodia.batteryalert.core.telemetry

import com.exodia.batteryalert.core.config.AppConfig
import com.exodia.batteryalert.core.model.*
import com.exodia.batteryalert.core.transport.TelemetryTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class TelemetryState(val battery: BatteryFrame? = null, val position: PositionFrame? = null, val home: PositionFrame? = null, val connection: ConnectionState = ConnectionState.Disconnected, val stale: Boolean = false)
class TelemetryRepository(private val transport: TelemetryTransport, private val now: () -> Long = { System.currentTimeMillis() }) {
    private val _state = MutableStateFlow(TelemetryState()); val state: StateFlow<TelemetryState> = _state.asStateFlow()
    private var job: Job? = null
    suspend fun start(scope: CoroutineScope) {
        transport.start()
        job = scope.launch {
            launch {
                transport.connectionState.collect {
                    _state.update { state -> state.copy(connection = it, stale = it is ConnectionState.LinkLost) }
                }
            }
            transport.frames.collect { frame ->
                _state.update { old ->
                    when (frame) {
                        is TelemetryFrame.Battery ->
                            old.copy(battery = frame.value, stale = false, connection = ConnectionState.Connected)
                        is TelemetryFrame.Position ->
                            old.copy(position = frame.value, home = old.home ?: frame.value, stale = false, connection = ConnectionState.Connected)
                        is TelemetryFrame.Home ->
                            old.copy(home = PositionFrame(frame.value.timestampMs, frame.value.latDeg, frame.value.lonDeg, frame.value.altitudeM, 0f, 0f))
                        is TelemetryFrame.VehicleState ->
                            old
                        is TelemetryFrame.CommandResult ->
                            old
                    }
                }
            }
        }
    }
    suspend fun stop() { job?.cancel(); transport.stop() }
}
