package com.exodia.batteryalert.core.transport

import com.exodia.batteryalert.core.config.AppConfig
import com.exodia.batteryalert.core.model.ConnectionState
import com.exodia.batteryalert.core.model.TelemetryFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

/**
 * Internal serial transport for /dev/ttySx on the Skydroid G20.
 *
 * CON-01/CON-02 / FR-1.1 implementation:
 *   - Supports [ByteStreamSource] bridge when available.
 *   - Verifies device node presence and readability without root/su/chmod.
 *   - Feeds incoming bytes through [MavlinkCodec].
 *   - If firmware denies access, produces ACCESS_DENIED error with documentation hint.
 *   - Physical hardware status: NOT_TESTED — FR-1.1 physical PASS blocked until bench test.
 */
class InternalSerialTransport(
    val devicePath: String,
    val baudRate: Int = 921600,
    val vehicleSystemId: Int = 1,
    private val streamSource: ByteStreamSource? = null,
) : TelemetryTransport {

    override val id = "internal_serial"
    override val displayName = "Internal Serial ($devicePath)"

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _frames = MutableSharedFlow<TelemetryFrame>(replay = 1, extraBufferCapacity = 64)
    override val frames: Flow<TelemetryFrame> = _frames.asSharedFlow()

    val codec = MavlinkCodec(MavlinkCodecConfig(targetSystemId = vehicleSystemId))

    private var job: Job? = null
    private var lastBatteryFrameTimeMs: Long = 0L

    override suspend fun start() {
        if (job?.isActive == true) return

        if (streamSource != null) {
            _connectionState.value = ConnectionState.Connecting
            job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runReceiveLoop()
            }
            return
        }

        // Check node on filesystem
        val file = File(devicePath)
        if (!file.exists()) {
            _connectionState.value = ConnectionState.Error(
                "Internal serial: device $devicePath not found. " +
                    "Verify G20 firmware exposes a serial node for app access. " +
                    "See docs/TRANSPORTS_DAY2.md. Physical FR-1.1 NOT_TESTED."
            )
            return
        }

        if (!file.canRead()) {
            _connectionState.value = ConnectionState.Error(
                "ACCESS_DENIED: Cannot read $devicePath. " +
                    "A vendor permission grant or USB serial route may be required. " +
                    "Physical FR-1.1 is hardware-unverified."
            )
            return
        }

        _connectionState.value = ConnectionState.Error(
            "Internal serial: $devicePath exists but requires termios baud configuration ($baudRate). " +
                "Physical hardware NOT_TESTED until bench validation."
        )
    }

    private suspend fun runReceiveLoop() {
        withContext(Dispatchers.IO) {
            try {
                streamSource?.open()
                val buf = ByteArray(1024)

                while (isActive && streamSource?.isOpen == true) {
                    val n = try {
                        streamSource.read(buf, 0, buf.size)
                    } catch (e: Exception) {
                        if (isActive) {
                            _connectionState.value = ConnectionState.Error("Internal serial read error: ${e.message}")
                        }
                        break
                    }

                    val nowMs = System.currentTimeMillis()
                    if (n > 0) {
                        val decodedFrames = codec.feedBytes(buf, n, nowMs)
                        for (frame in decodedFrames) {
                            if (frame is TelemetryFrame.Battery) {
                                lastBatteryFrameTimeMs = nowMs
                                if (_connectionState.value !is ConnectionState.Connected) {
                                    _connectionState.value = ConnectionState.Connected
                                }
                            }
                            _frames.emit(frame)
                        }
                    } else if (n < 0) {
                        break
                    }

                    if (_connectionState.value is ConnectionState.Connected && lastBatteryFrameTimeMs > 0) {
                        if (nowMs - lastBatteryFrameTimeMs > AppConfig.batteryFieldMaxAgeMs) {
                            _connectionState.value = ConnectionState.LinkLost(sinceMs = lastBatteryFrameTimeMs)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    _connectionState.value = ConnectionState.Error("Internal serial error: ${e.message}")
                }
            } finally {
                streamSource?.close()
            }
        }
    }

    override suspend fun stop() {
        streamSource?.close()
        job?.cancel()
        job = null
        _connectionState.value = ConnectionState.Disconnected
    }
}
