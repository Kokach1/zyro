package com.exodia.batteryalert.core.transport

import com.exodia.batteryalert.core.config.RealConnectionConfig
import com.exodia.batteryalert.core.config.TransportConfig
import com.exodia.batteryalert.core.config.TransportKind
import com.exodia.batteryalert.core.model.ConnectionState
import com.exodia.batteryalert.core.model.SessionSource
import com.exodia.batteryalert.core.model.TelemetryFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Transport interface. All implementations must:
 *  - Be idempotent on start()/stop() calls.
 *  - Expose [sessionSource] reflecting the real underlying transport type.
 *  - Emit only TelemetryFrame values.
 *  - Set ConnectionState.Error with an actionable message on unrecoverable failure.
 *  - Never set Connected state from socket-open alone; require valid telemetry.
 */
interface TelemetryTransport {
    val id: String
    val displayName: String
    val sessionSource: SessionSource
    val connectionState: StateFlow<ConnectionState>
    val frames: Flow<TelemetryFrame>
    suspend fun start()
    suspend fun stop()
}

/**
 * Pluggable stream source provider for platform adapters (USB serial, internal serial).
 */
interface StreamSourceProvider {
    fun provideUsbSerialStream(config: RealConnectionConfig.UsbSerial): ByteStreamSource?
    fun provideInternalSerialStream(config: RealConnectionConfig.InternalSerial): ByteStreamSource?
}

/**
 * Exhaustive transport factory driven by typed [RealConnectionConfig] or [TransportConfig].
 */
object TransportFactory {
    var streamSourceProvider: StreamSourceProvider? = null

    /**
     * Creates a real transport for [config]. Strongly-typed; cannot compile with simulator.
     */
    fun createReal(config: RealConnectionConfig): Result<TelemetryTransport> {
        val validated = config.validate()
        if (validated.isFailure) return Result.failure(validated.exceptionOrNull()!!)
        val transport: TelemetryTransport = when (config) {
            is RealConnectionConfig.Udp ->
                UdpTransport(
                    bindAddress = config.bindAddress,
                    bindPort = config.bindPort,
                    vehicleSystemId = config.vehicleSystemId,
                )
            is RealConnectionConfig.UsbSerial ->
                UsbSerialTransport(
                    baudRate = config.baudRate,
                    dataBits = config.dataBits,
                    stopBits = config.stopBits,
                    vehicleSystemId = config.vehicleSystemId,
                    streamSource = streamSourceProvider?.provideUsbSerialStream(config)
                )
            is RealConnectionConfig.InternalSerial ->
                InternalSerialTransport(
                    devicePath = config.devicePath,
                    baudRate = config.baudRate,
                    vehicleSystemId = config.vehicleSystemId,
                    streamSource = streamSourceProvider?.provideInternalSerialStream(config)
                )
            is RealConnectionConfig.Replay ->
                ReplayTransport(
                    filePath = config.filePath,
                    vehicleSystemId = config.vehicleSystemId,
                    speedMultiplier = config.speedMultiplier,
                )
        }
        return Result.success(transport)
    }

    /**
     * Legacy adapter from [TransportConfig]. Rejects SIMULATOR defensively.
     */
    fun create(config: TransportConfig): Result<TelemetryTransport> {
        if (config.kind == TransportKind.SIMULATOR) {
            return Result.success(createSimulator())
        }
        return createReal(config.toRealConfig())
    }

    /**
     * Creates the Simulator transport directly (from explicit Simulator button only).
     */
    fun createSimulator(): SimulatorTransport = SimulatorTransport()
}
