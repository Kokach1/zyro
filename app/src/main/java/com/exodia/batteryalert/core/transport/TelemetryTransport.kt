package com.exodia.batteryalert.core.transport

import com.exodia.batteryalert.core.model.ConnectionState
import com.exodia.batteryalert.core.model.TelemetryFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface TelemetryTransport {
    val id: String
    val displayName: String
    val connectionState: StateFlow<ConnectionState>
    val frames: Flow<TelemetryFrame>
    suspend fun start()
    suspend fun stop()
}

object TransportFactory {
    fun createDefaultTransport(): TelemetryTransport = SimulatorTransport()
    // Day 2 registers UDP, serial-file, and USB serial implementations selected by AppConfig.transportId.
}
