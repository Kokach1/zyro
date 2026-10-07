package com.exodia.batteryalert.core.config

/**
 * Typed transport selection. Cold launch has no implicit active transport:
 * user must choose a source and press Start, or press the Simulator button.
 */
enum class TransportKind {
    SIMULATOR,
    UDP,
    USB_SERIAL,
    INTERNAL_SERIAL,
    REPLAY
}

/**
 * Validated, typed transport configuration.
 * Invalid/unconfigured paths produce actionable failure, never simulator fallback.
 */
data class TransportConfig(
    val kind: TransportKind = TransportKind.SIMULATOR,
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
    fun validate(): Result<TransportConfig> {
        return when (kind) {
            TransportKind.SIMULATOR -> Result.success(this)
            TransportKind.UDP -> {
                if (udpBindPort !in 1..65535) return Result.failure(IllegalArgumentException("UDP port must be 1-65535, got $udpBindPort"))
                Result.success(this)
            }
            TransportKind.USB_SERIAL -> {
                if (usbBaudRate <= 0) return Result.failure(IllegalArgumentException("USB baud rate must be positive, got $usbBaudRate"))
                Result.success(this)
            }
            TransportKind.INTERNAL_SERIAL -> {
                if (serialDevicePath.isBlank()) return Result.failure(IllegalArgumentException("Serial device path must not be blank"))
                if (serialBaudRate <= 0) return Result.failure(IllegalArgumentException("Serial baud rate must be positive, got $serialBaudRate"))
                Result.success(this)
            }
            TransportKind.REPLAY -> {
                if (replayFilePath.isBlank()) return Result.failure(IllegalArgumentException("Replay file path must not be blank"))
                Result.success(this)
            }
        }
    }
}
