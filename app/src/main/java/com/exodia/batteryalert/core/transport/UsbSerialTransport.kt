package com.exodia.batteryalert.core.transport

import com.exodia.batteryalert.core.config.AppConfig
import com.exodia.batteryalert.core.model.ConnectionState
import com.exodia.batteryalert.core.model.TelemetryFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * USB Serial transport using [ByteStreamSource] abstraction.
 *
 * Keeps core.* 100% Android-free. The Android platform layer provides
 * the concrete [AndroidUsbSerialStream] backed by usb-serial-for-android.
 *
 * CON-01/CON-02 / FR-1.1 implementation:
 *   - Feeds all incoming serial bytes into [MavlinkCodec].
 *   - Only valid battery frames transition state to [ConnectionState.Connected].
 *   - Watchdog detects link loss if battery frames cease for > batteryFieldMaxAgeMs.
 *   - Clean cancellation on stop().
 *   - Physical hardware status: NOT_TESTED — G20 USB VID/PID and driver unconfirmed.
 */
class UsbSerialTransport(
    val baudRate: Int = 57600,
    val dataBits: Int = 8,
    val stopBits: Int = 1,
    val vehicleSystemId: Int = 1,
    private val streamSource: ByteStreamSource? = null,
) : TelemetryTransport {

    override val id = "usb_serial"
    override val displayName = "USB Serial ($baudRate baud)"
    override val sessionSource = com.exodia.batteryalert.core.model.SessionSource.LIVE_USB

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _frames = MutableSharedFlow<TelemetryFrame>(replay = 1, extraBufferCapacity = 64)
    override val frames: Flow<TelemetryFrame> = _frames.asSharedFlow()

    val codec = MavlinkCodec(MavlinkCodecConfig(targetSystemId = vehicleSystemId))

    private var job: Job? = null
    private var lastBatteryFrameTimeMs: Long = 0L

    override suspend fun start() {
        if (job?.isActive == true) return

        if (streamSource == null) {
            _connectionState.value = ConnectionState.Error(
                "USB Serial: No device connected or USB permission not granted. Connect USB OTG cable and grant permission."
            )
            return
        }

        _connectionState.value = ConnectionState.Connecting
        lastBatteryFrameTimeMs = 0L

        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runReceiveLoop()
        }
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
                            _connectionState.value = ConnectionState.Error("USB Serial read error: ${e.message}")
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
                        // EOF / Detached
                        _connectionState.value = ConnectionState.Error("USB device detached or stream closed")
                        break
                    }

                    // Watchdog: If connected and haven't received a battery frame for > batteryFieldMaxAgeMs
                    if (_connectionState.value is ConnectionState.Connected && lastBatteryFrameTimeMs > 0) {
                        if (nowMs - lastBatteryFrameTimeMs > AppConfig.batteryFieldMaxAgeMs) {
                            _connectionState.value = ConnectionState.LinkLost(sinceMs = lastBatteryFrameTimeMs)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    _connectionState.value = ConnectionState.Error("USB Serial open failed: ${e.message}")
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
