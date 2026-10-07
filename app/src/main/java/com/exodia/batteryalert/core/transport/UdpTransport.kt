package com.exodia.batteryalert.core.transport

import com.exodia.batteryalert.core.config.AppConfig
import com.exodia.batteryalert.core.model.ConnectionState
import com.exodia.batteryalert.core.model.TelemetryFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * UDP receive-only transport with integrated [MavlinkCodec].
 *
 * CON-01/CON-02 and Step 04 implementation:
 *   - Binds DatagramSocket on configured address and port.
 *   - Bind success = LISTENING (Connecting state), NOT Connected.
 *   - Only valid BATTERY_STATUS / valid battery frame sets Connected.
 *   - Feeds all incoming bytes through [MavlinkCodec].
 *   - Watchdog detects link loss if battery frames cease for > batteryFieldMaxAgeMs.
 *   - Clean IO cancellation on stop().
 */
class UdpTransport(
    private val bindAddress: String = "0.0.0.0",
    private val bindPort: Int = 14550,
    private val vehicleSystemId: Int = 1,
) : TelemetryTransport {

    override val id = "udp"
    override val displayName = "UDP (port $bindPort)"

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _frames = MutableSharedFlow<TelemetryFrame>(replay = 1, extraBufferCapacity = 64)
    override val frames: Flow<TelemetryFrame> = _frames.asSharedFlow()

    val codec = MavlinkCodec(MavlinkCodecConfig(targetSystemId = vehicleSystemId))

    private var job: Job? = null
    private var socket: DatagramSocket? = null
    private var lastBatteryFrameTimeMs: Long = 0L

    override suspend fun start() {
        if (job?.isActive == true) return
        _connectionState.value = ConnectionState.Connecting
        lastBatteryFrameTimeMs = 0L

        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runReceiveLoop()
        }
    }

    private suspend fun runReceiveLoop() {
        withContext(Dispatchers.IO) {
            try {
                val s = DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 500 // 500ms timeout to allow cancellation and watchdog checks
                    bind(InetSocketAddress(bindAddress, bindPort))
                }
                socket = s

                // Bind success = LISTENING / Connecting, not yet Connected
                _connectionState.value = ConnectionState.Connecting

                val buf = ByteArray(2048)
                val packet = DatagramPacket(buf, buf.size)

                while (isActive) {
                    val nowMs = System.currentTimeMillis()
                    try {
                        packet.length = buf.size
                        s.receive(packet)

                        val decodedFrames = codec.feedBytes(packet.data, packet.length, nowMs)
                        for (frame in decodedFrames) {
                            if (frame is TelemetryFrame.Battery) {
                                lastBatteryFrameTimeMs = nowMs
                                if (_connectionState.value !is ConnectionState.Connected) {
                                    _connectionState.value = ConnectionState.Connected
                                }
                            }
                            _frames.emit(frame)
                        }
                    } catch (e: SocketTimeoutException) {
                        // Receive timed out — check watchdog
                    }

                    // Watchdog: If we were connected and haven't received a battery frame for > batteryFieldMaxAgeMs
                    if (_connectionState.value is ConnectionState.Connected && lastBatteryFrameTimeMs > 0) {
                        if (nowMs - lastBatteryFrameTimeMs > AppConfig.batteryFieldMaxAgeMs) {
                            _connectionState.value = ConnectionState.LinkLost(sinceMs = lastBatteryFrameTimeMs)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    _connectionState.value = ConnectionState.Error(
                        "UDP bind failed on $bindAddress:$bindPort — ${e.message}"
                    )
                }
            } finally {
                socket?.close()
                socket = null
            }
        }
    }

    override suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        socket?.close()
        socket = null
        _connectionState.value = ConnectionState.Disconnected
    }
}
