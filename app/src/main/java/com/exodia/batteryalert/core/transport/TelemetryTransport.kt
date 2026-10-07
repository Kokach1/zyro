package com.exodia.batteryalert.core.transport

import com.exodia.batteryalert.core.config.TransportConfig
import com.exodia.batteryalert.core.config.TransportKind
import com.exodia.batteryalert.core.model.ConnectionState
import com.exodia.batteryalert.core.model.TelemetryFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Transport interface. All implementations must:
 *  - Be idempotent on start()/stop() calls.
 *  - Emit only TelemetryFrame values (no raw MAVLink in any outer layer).
 *  - Set ConnectionState.Error with an actionable message on unrecoverable failure.
 *  - Never set Connected state from socket-open alone; require valid telemetry.
 */
interface TelemetryTransport {
    val id: String
    val displayName: String
    val connectionState: StateFlow<ConnectionState>
    val frames: Flow<TelemetryFrame>
    suspend fun start()
    suspend fun stop()
}

/**
 * Exhaustive transport factory driven by validated [TransportConfig].
 *
 * CON-01 fix: No simulator fallback. Invalid/unconfigured paths produce a
 * documented error rather than silently starting simulation data.
 *
 * Cold launch behaviour:
 *  - The app starts with no active transport (no implicit simulator).
 *  - User configures a source in the Connection/Setup UI and presses Start.
 *  - The Simulator button explicitly selects TransportKind.SIMULATOR.
 */
object TransportFactory {
    /**
     * Creates the transport for [config]. Returns Result.failure with an
     * actionable message if the config fails validation. No silent fallback.
     */
    fun create(config: TransportConfig): Result<TelemetryTransport> {
        val validated = config.validate()
        if (validated.isFailure) return Result.failure(validated.exceptionOrNull()!!)
        val transport: TelemetryTransport = when (config.kind) {
            TransportKind.SIMULATOR ->
                SimulatorTransport()
            TransportKind.UDP ->
                UdpTransport(
                    bindAddress = config.udpBindAddress,
                    bindPort = config.udpBindPort,
                    vehicleSystemId = config.vehicleSystemId,
                )
            TransportKind.USB_SERIAL ->
                UsbSerialTransport(
                    baudRate = config.usbBaudRate,
                    dataBits = config.usbDataBits,
                    stopBits = config.usbStopBits,
                    vehicleSystemId = config.vehicleSystemId,
                )
            TransportKind.INTERNAL_SERIAL ->
                InternalSerialTransport(
                    devicePath = config.serialDevicePath,
                    baudRate = config.serialBaudRate,
                    vehicleSystemId = config.vehicleSystemId,
                )
            TransportKind.REPLAY ->
                ReplayTransport(
                    filePath = config.replayFilePath,
                    vehicleSystemId = config.vehicleSystemId,
                )
        }
        return Result.success(transport)
    }

    /** Convenience: create the Simulator transport directly (from Simulator button). */
    fun createSimulator(): SimulatorTransport = SimulatorTransport()
}

