package com.exodia.batteryalert.core.config

import com.exodia.batteryalert.core.model.BatteryChemistry
import com.exodia.batteryalert.core.model.SessionSource

/**
 * Validated, typed transport configuration.
 * Explicitly separates real transport configuration from Simulator.
 */
enum class RealTransportKind {
    UDP,
    USB_SERIAL,
    INTERNAL_SERIAL,
    REPLAY
}

enum class TransportKind {
    UDP,
    USB_SERIAL,
    INTERNAL_SERIAL,
    REPLAY,
    SIMULATOR
}

/**
 * Strongly-typed configuration hierarchy for real drone connections (or file replay).
 * Notice: Simulator does NOT exist in this hierarchy, so invalid source selection
 * cannot compile into a live session.
 */
sealed interface RealConnectionConfig {
    val vehicleSystemId: Int
    val vehicleComponentId: Int
    val confirmedCapacityMah: Int?
    val confirmedChemistry: BatteryChemistry?
    val confirmedCruisingSpeedMps: Float?
    val confirmedPerCellResistanceOhms: Float?

    fun validate(): Result<Unit>
    fun toSessionSource(): SessionSource

    data class Udp(
        val bindAddress: String = "0.0.0.0",
        val bindPort: Int = 14550,
        val allowedSenderAddress: String? = null,
        override val vehicleSystemId: Int = 1,
        override val vehicleComponentId: Int = 1,
        override val confirmedCapacityMah: Int? = null,
        override val confirmedChemistry: BatteryChemistry? = null,
        override val confirmedCruisingSpeedMps: Float? = null,
        override val confirmedPerCellResistanceOhms: Float? = null,
    ) : RealConnectionConfig {
        override fun validate(): Result<Unit> {
            if (bindPort !in 1..65535) {
                return Result.failure(IllegalArgumentException("Invalid UDP port ($bindPort). Must be 1-65535."))
            }
            if (bindAddress.isBlank()) {
                return Result.failure(IllegalArgumentException("Bind address must not be blank."))
            }
            return Result.success(Unit)
        }
        override fun toSessionSource(): SessionSource = SessionSource.LIVE_UDP
    }

    data class UsbSerial(
        val baudRate: Int = 57600,
        val dataBits: Int = 8,
        val stopBits: Int = 1,
        val parity: Int = 0,
        val deviceName: String? = null,
        val portIndex: Int = 0,
        override val vehicleSystemId: Int = 1,
        override val vehicleComponentId: Int = 1,
        override val confirmedCapacityMah: Int? = null,
        override val confirmedChemistry: BatteryChemistry? = null,
        override val confirmedCruisingSpeedMps: Float? = null,
        override val confirmedPerCellResistanceOhms: Float? = null,
    ) : RealConnectionConfig {
        override fun validate(): Result<Unit> {
            if (baudRate <= 0) {
                return Result.failure(IllegalArgumentException("USB baud rate must be positive, got $baudRate."))
            }
            return Result.success(Unit)
        }
        override fun toSessionSource(): SessionSource = SessionSource.LIVE_USB
    }

    data class InternalSerial(
        val devicePath: String,
        val baudRate: Int = 921600,
        override val vehicleSystemId: Int = 1,
        override val vehicleComponentId: Int = 1,
        override val confirmedCapacityMah: Int? = null,
        override val confirmedChemistry: BatteryChemistry? = null,
        override val confirmedCruisingSpeedMps: Float? = null,
        override val confirmedPerCellResistanceOhms: Float? = null,
    ) : RealConnectionConfig {
        override fun validate(): Result<Unit> {
            if (devicePath.isBlank()) {
                return Result.failure(IllegalArgumentException("Internal serial path must not be blank."))
            }
            if (baudRate <= 0) {
                return Result.failure(IllegalArgumentException("Baud rate must be positive, got $baudRate."))
            }
            return Result.success(Unit)
        }
        override fun toSessionSource(): SessionSource = SessionSource.LIVE_INTERNAL
    }

    data class Replay(
        val filePath: String,
        val fileUri: String? = null,
        val speedMultiplier: Int = 1,
        override val vehicleSystemId: Int = 1,
        override val vehicleComponentId: Int = 1,
        override val confirmedCapacityMah: Int? = null,
        override val confirmedChemistry: BatteryChemistry? = null,
        override val confirmedCruisingSpeedMps: Float? = null,
        override val confirmedPerCellResistanceOhms: Float? = null,
    ) : RealConnectionConfig {
        override fun validate(): Result<Unit> {
            if (filePath.isBlank() && fileUri.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("Replay document path or URI must be provided."))
            }
            return Result.success(Unit)
        }
        override fun toSessionSource(): SessionSource = SessionSource.REPLAY
    }
}

/**
 * Legacy/convenience wrapper for backward compatibility. Default kind is UDP (not simulator!).
 */
data class TransportConfig(
    val kind: TransportKind = TransportKind.UDP,
    // UDP
    val udpBindAddress: String = "0.0.0.0",
    val udpBindPort: Int = 14550,
    // USB Serial
    val usbBaudRate: Int = 57600,
    val usbDataBits: Int = 8,
    val usbStopBits: Int = 1,
    // Internal Serial
    val serialDevicePath: String = "",
    val serialBaudRate: Int = 921600,
    // Replay
    val replayFilePath: String = "",
    // Vehicle selection
    val vehicleSystemId: Int = 1,
    val vehicleComponentId: Int = 1,
) {
    fun toRealConfig(): RealConnectionConfig {
        return when (kind) {
            TransportKind.UDP -> RealConnectionConfig.Udp(
                bindAddress = udpBindAddress,
                bindPort = udpBindPort,
                vehicleSystemId = vehicleSystemId,
                vehicleComponentId = vehicleComponentId
            )
            TransportKind.USB_SERIAL -> RealConnectionConfig.UsbSerial(
                baudRate = usbBaudRate,
                dataBits = usbDataBits,
                stopBits = usbStopBits,
                vehicleSystemId = vehicleSystemId,
                vehicleComponentId = vehicleComponentId
            )
            TransportKind.INTERNAL_SERIAL -> RealConnectionConfig.InternalSerial(
                devicePath = serialDevicePath,
                baudRate = serialBaudRate,
                vehicleSystemId = vehicleSystemId,
                vehicleComponentId = vehicleComponentId
            )
            TransportKind.REPLAY -> RealConnectionConfig.Replay(
                filePath = replayFilePath,
                vehicleSystemId = vehicleSystemId,
                vehicleComponentId = vehicleComponentId
            )
            TransportKind.SIMULATOR ->
                throw IllegalArgumentException("Simulator cannot be converted to RealConnectionConfig")
        }
    }

    fun validate(): Result<TransportConfig> {
        if (kind == TransportKind.SIMULATOR) return Result.success(this)
        return try {
            toRealConfig().validate().map { this }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
