package com.exodia.batteryalert.core.telemetry

import com.exodia.batteryalert.core.config.AppConfig
import com.exodia.batteryalert.core.model.*
import com.exodia.batteryalert.core.transport.TelemetryTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class TelemetryState(
    val battery: BatteryFrame? = null,
    val position: PositionFrame? = null,
    val home: PositionFrame? = null,
    val vehicleState: VehicleStateFrame? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val sessionSource: SessionSource = SessionSource.LIVE_UDP,
    val stale: Boolean = false,
    val batteryStale: Boolean = false,
    val positionStale: Boolean = false,
    val heartbeatStale: Boolean = false,
    val targetSystemId: Int = 1,
)

class TelemetryRepository(
    val transport: TelemetryTransport,
    val targetSystemId: Int = 1,
    val targetBatteryId: Int? = null,
    private val clockMonotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val clockWallMs: () -> Long = { System.currentTimeMillis() }
) {
    private val accumulator = BatteryStateAccumulator(targetSystemId, targetBatteryId)

    private val _state = MutableStateFlow(
        TelemetryState(
            connection = transport.connectionState.value,
            sessionSource = transport.sessionSource,
            targetSystemId = targetSystemId
        )
    )
    val state: StateFlow<TelemetryState> = _state.asStateFlow()

    private var job: Job? = null

    private var lastBatteryMonotonicMs = 0L
    private var lastPositionMonotonicMs = 0L
    private var lastHeartbeatMonotonicMs = 0L
    private var lastAnyMessageMonotonicMs = 0L
    private var hasReceivedValidBattery = false

    suspend fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return

        job = scope.launch {
            // Subscribe to connection state first
            launch {
                transport.connectionState.collect { conn ->
                    _state.update { state ->
                        state.copy(
                            connection = conn,
                            stale = conn is ConnectionState.LinkLost || state.batteryStale
                        )
                    }
                }
            }
            // Subscribe to frames
            launch {
                transport.frames.collect { frame ->
                    onFrame(frame)
                }
            }
            // Launch central watchdog loop
            launch {
                while (isActive) {
                    delay(250)
                    checkWatchdog(clockMonotonicMs())
                }
            }
            // Start transport after collectors are active
            transport.start()
        }
    }

    fun onFrame(frame: TelemetryFrame, nowMonotonic: Long = clockMonotonicMs()) {
        when (frame) {
            is TelemetryFrame.Battery -> {
                if (frame.value.systemId != targetSystemId) return
                if (targetBatteryId != null && frame.value.batteryId != targetBatteryId) return

                val consolidated = accumulator.onBatteryFrame(frame.value, nowMonotonic)
                if (consolidated != null) {
                    lastBatteryMonotonicMs = nowMonotonic
                    lastAnyMessageMonotonicMs = nowMonotonic
                    hasReceivedValidBattery = true

                    _state.update { old ->
                        old.copy(
                            battery = consolidated,
                            stale = consolidated.stale,
                            batteryStale = consolidated.stale,
                            connection = if (old.connection !is ConnectionState.Error) ConnectionState.Connected else old.connection
                        )
                    }
                }
            }
            is TelemetryFrame.Position -> {
                if (frame.value.systemId != targetSystemId) return
                lastPositionMonotonicMs = nowMonotonic
                lastAnyMessageMonotonicMs = nowMonotonic

                // Do NOT synthesize home from position (FIX-12)
                // Do NOT mark connection Connected from position alone (FIX-08 / FIX-23)
                _state.update { old ->
                    old.copy(
                        position = frame.value,
                        positionStale = false
                    )
                }
            }
            is TelemetryFrame.Home -> {
                lastAnyMessageMonotonicMs = nowMonotonic
                if (frame.value.isValid) {
                    _state.update { old ->
                        old.copy(
                            home = PositionFrame(
                                timestampMs = frame.value.timestampMs,
                                latDeg = frame.value.latDeg,
                                lonDeg = frame.value.lonDeg,
                                altitudeM = frame.value.altitudeM,
                                relativeAltitudeM = 0f,
                                groundSpeedMps = 0f,
                                systemId = targetSystemId
                            )
                        )
                    }
                }
            }
            is TelemetryFrame.VehicleState -> {
                if (frame.value.systemId != targetSystemId) return
                lastHeartbeatMonotonicMs = nowMonotonic
                lastAnyMessageMonotonicMs = nowMonotonic

                _state.update { old ->
                    old.copy(
                        vehicleState = frame.value,
                        heartbeatStale = false
                    )
                }
            }
            is TelemetryFrame.CommandResult -> {
                // Handled by command port / feedback
            }
        }
    }

    fun checkWatchdog(nowMonotonic: Long = clockMonotonicMs()) {
        val consolidatedBattery = accumulator.checkFreshness(nowMonotonic)
        val batteryStale = consolidatedBattery?.stale ?: (lastBatteryMonotonicMs == 0L || (nowMonotonic - lastBatteryMonotonicMs > AppConfig.batteryFieldMaxAgeMs))
        val positionStale = (lastPositionMonotonicMs > 0 && (nowMonotonic - lastPositionMonotonicMs > AppConfig.positionMaxAgeMs))
        val heartbeatStale = (lastHeartbeatMonotonicMs > 0 && (nowMonotonic - lastHeartbeatMonotonicMs > AppConfig.heartbeatTimeoutMs))
        val linkLost = (lastAnyMessageMonotonicMs > 0 && (nowMonotonic - lastAnyMessageMonotonicMs > AppConfig.heartbeatTimeoutMs))

        _state.update { old ->
            val newConn = if (linkLost && old.connection is ConnectionState.Connected) {
                ConnectionState.LinkLost(sinceMs = clockWallMs())
            } else if (old.connection is ConnectionState.LinkLost && !linkLost && hasReceivedValidBattery && !batteryStale) {
                ConnectionState.Connected
            } else {
                old.connection
            }

            old.copy(
                battery = consolidatedBattery ?: old.battery?.copy(stale = true, isCellsFresh = false),
                stale = batteryStale || linkLost || (newConn is ConnectionState.LinkLost),
                batteryStale = batteryStale,
                positionStale = positionStale,
                heartbeatStale = heartbeatStale,
                connection = newConn
            )
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        accumulator.reset()
        hasReceivedValidBattery = false
        lastBatteryMonotonicMs = 0L
        lastPositionMonotonicMs = 0L
        lastHeartbeatMonotonicMs = 0L
        lastAnyMessageMonotonicMs = 0L
        transport.stop()
    }
}
