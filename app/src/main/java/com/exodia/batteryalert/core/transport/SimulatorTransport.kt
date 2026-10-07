package com.exodia.batteryalert.core.transport

import com.exodia.batteryalert.core.analysis.CellVoltageCurve
import com.exodia.batteryalert.core.config.BatteryProfiles
import com.exodia.batteryalert.core.config.BatteryProfile
import com.exodia.batteryalert.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*

enum class SimulatorScenario(val label: String) {
    NORMAL_FLIGHT("Normal flight"),
    FAST_DISCHARGE("Fast discharge"),
    NOTICE_30("30 percent"),
    WARNING_20("20 percent"),
    CRITICAL_RTL("Dynamic RTL"),
    LOW_CELL("Low cell"),
    EMERGENCY("Emergency"),
    CELL_IMBALANCE("Cell fault"),
    LINK_LOST("Link lost")
}

interface Clock { fun nowMs(): Long }
object SystemClock : Clock { override fun nowMs(): Long = System.currentTimeMillis() }

class SimulatorTransport(private val clock: Clock = SystemClock) : TelemetryTransport {
    override val id = "simulator"
    override val displayName = "Simulator"
    override val sessionSource = SessionSource.SIMULATOR

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _frames = MutableSharedFlow<TelemetryFrame>(replay = 10, extraBufferCapacity = 64)
    override val frames: Flow<TelemetryFrame> = _frames.asSharedFlow()

    private var scope: CoroutineScope? = null
    var profile: BatteryProfile = BatteryProfiles.default
        private set
    var scenario = SimulatorScenario.NORMAL_FLIGHT
        private set
    var speedMultiplier = 1
        private set
    var isPaused = false
        private set

    // Virtual timeline state
    private val virtualBaseTimestampMs = 1_700_000_000_000L
    var simulatedVirtualMs = 0L
        private set
    var consumedMah = 0f
        private set
    var distanceM = 0f
        private set
    private var homePublished = false
    private var vehicleStatePublished = false

    // Simulated spraying pump cutoff interlock (FIX-16 / FIX-20)
    var simulatePumpCutoff: Boolean = false
    var isPumpCutoffActive: Boolean = false
        private set

    companion object {
        const val HOME_LAT = 10.0159
        const val HOME_LON = 76.3419
        // 1 degree latitude = 111,194.9266 meters on spherical Earth (radius = 6,371,000m)
        const val METERS_PER_DEGREE_LAT = 111_194.9266
    }

    override suspend fun start() {
        if (scope != null) return
        _connectionState.value = ConnectionState.Connecting
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { worker ->
            worker.launch {
                delay(300)
                _connectionState.value = ConnectionState.Connected
                while (isActive) {
                    emitTick()
                    delay(200)
                }
            }
        }
    }

    override suspend fun stop() {
        scope?.cancel()
        scope = null
        _connectionState.value = ConnectionState.Disconnected
    }

    fun setScenario(value: SimulatorScenario) {
        scenario = value
        resetToFull()
        when (value) {
            SimulatorScenario.NOTICE_30 -> {
                consumedMah = profile.capacityMah * 0.70f
                distanceM = 100f
            }
            SimulatorScenario.WARNING_20 -> {
                consumedMah = profile.capacityMah * 0.80f
                distanceM = 100f
            }
            SimulatorScenario.CRITICAL_RTL -> {
                // Golden RTL scenario: 30% remaining, 900m distance, 110A load, equal 3.80V cells
                consumedMah = profile.capacityMah * 0.70f
                distanceM = 900f
            }
            SimulatorScenario.LOW_CELL -> {
                consumedMah = profile.capacityMah * 0.50f
                distanceM = 100f
            }
            SimulatorScenario.EMERGENCY -> {
                consumedMah = profile.capacityMah * 0.60f
                distanceM = 100f
            }
            SimulatorScenario.CELL_IMBALANCE -> {
                consumedMah = profile.capacityMah * 0.40f
                distanceM = 100f
            }
            else -> Unit
        }
    }

    fun setSpeedMultiplier(value: Int) {
        speedMultiplier = value.coerceIn(1, 20)
    }

    fun setProfile(id: String) {
        profile = BatteryProfiles.byId(id)
        resetToFull()
    }

    fun pause() { isPaused = true }
    fun resume() { isPaused = false }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun resetToFull() {
        consumedMah = 0f
        simulatedVirtualMs = 0L
        distanceM = 0f
        homePublished = false
        vehicleStatePublished = false
        isPaused = false
        isPumpCutoffActive = false
        _frames.resetReplayCache()
    }

    internal suspend fun emitTick() {
        if (isPaused) return

        if (scenario == SimulatorScenario.LINK_LOST && simulatedVirtualMs in 1_000..10_000) {
            _connectionState.value = ConnectionState.LinkLost(clock.nowMs())
            simulatedVirtualMs += 200L * speedMultiplier
            return
        }
        _connectionState.value = ConnectionState.Connected

        val dtSeconds = 0.2f * speedMultiplier
        simulatedVirtualMs += (dtSeconds * 1000).toLong()

        // Base current by scenario
        val baseCurrent = when (scenario) {
            SimulatorScenario.CRITICAL_RTL -> 110f
            SimulatorScenario.EMERGENCY -> 140f
            SimulatorScenario.FAST_DISCHARGE -> 150f
            else -> 90f
        }

        // Check simulated pump cutoff (FIX-16 / FIX-20): reduces load after <= 20%
        val currentPercent = (100f - consumedMah / profile.capacityMah * 100f).coerceIn(0f, 100f)
        if (simulatePumpCutoff && currentPercent <= 20f) {
            isPumpCutoffActive = true
        }
        val pumpReduction = if (isPumpCutoffActive) 30f else 0f

        val currentA = if (scenario == SimulatorScenario.CRITICAL_RTL) {
            baseCurrent // Exact 110A with zero noise for golden test
        } else {
            (baseCurrent - pumpReduction + sin(simulatedVirtualMs / 3_000.0).toFloat() * 3f).coerceAtLeast(10f)
        }

        consumedMah = (consumedMah + currentA * dtSeconds / 3.6f).coerceAtMost(profile.capacityMah.toFloat())

        if (scenario != SimulatorScenario.CRITICAL_RTL && scenario != SimulatorScenario.NOTICE_30 &&
            scenario != SimulatorScenario.WARNING_20 && scenario != SimulatorScenario.LOW_CELL &&
            scenario != SimulatorScenario.EMERGENCY && scenario != SimulatorScenario.CELL_IMBALANCE
        ) {
            distanceM += 5f * dtSeconds
        }

        val percent = (100f - consumedMah / profile.capacityMah * 100f).coerceIn(0f, 100f)
        val openV = CellVoltageCurve.voltageForPercent(percent)

        val cells = when (scenario) {
            SimulatorScenario.CRITICAL_RTL -> {
                // Exact equal 3.80V cells for all series slots — zero low-cell or imbalance contamination
                List(profile.cellCount) { 3.80f }
            }
            SimulatorScenario.LOW_CELL -> {
                // Min cell <= 3.50V (3.48V), others 3.52V. Delta = 0.04V <= 0.08V (no imbalance)
                List(profile.cellCount) { index -> if (index == 0) 3.48f else 3.52f }
            }
            SimulatorScenario.EMERGENCY -> {
                // Min cell <= 3.40V (3.38V), others 3.42V. Delta = 0.04V <= 0.08V
                List(profile.cellCount) { index -> if (index == 0) 3.38f else 3.42f }
            }
            SimulatorScenario.CELL_IMBALANCE -> {
                // Delta = 3.82 - 3.70 = 0.12V > 0.08V immediate cell fault
                List(profile.cellCount) { index -> if (index == 0) 3.70f else 3.82f }
            }
            SimulatorScenario.NOTICE_30 -> {
                // Healthy cells ~3.75V (above 3.65V Warning threshold)
                List(profile.cellCount) { 3.75f }
            }
            SimulatorScenario.WARNING_20 -> {
                // Warning cells: min cell 3.62V (<= 3.65V), but > 3.50V
                List(profile.cellCount) { 3.62f }
            }
            else -> {
                // Normal loaded voltage curve with small deterministic series spread
                List(profile.cellCount) { index ->
                    openV - currentA * 0.002f + ((index % 5) - 2) * 0.002f
                }
            }
        }

        val frameTimestamp = virtualBaseTimestampMs + simulatedVirtualMs
        val monotonicReceiveTime = clock.nowMs()

        // 1. Explicit HomeFrame
        if (!homePublished) {
            _frames.emit(
                TelemetryFrame.Home(
                    HomeFrame(
                        timestampMs = frameTimestamp,
                        latDeg = HOME_LAT,
                        lonDeg = HOME_LON,
                        altitudeM = 0f,
                        source = HomeSource.SIMULATOR_HOME,
                        isValid = true
                    )
                )
            )
            homePublished = true
        }

        // 2. Explicit VehicleStateFrame
        if (!vehicleStatePublished) {
            _frames.emit(
                TelemetryFrame.VehicleState(
                    VehicleStateFrame(
                        timestampMs = frameTimestamp,
                        systemId = 1,
                        componentId = 1,
                        isArmed = true
                    )
                )
            )
            vehicleStatePublished = true
        }

        // 3. Geodesic PositionFrame
        val currentLat = HOME_LAT + (distanceM / METERS_PER_DEGREE_LAT)
        val currentLon = HOME_LON
        val groundSpeed = if (scenario == SimulatorScenario.CRITICAL_RTL) 5f else 5f

        _frames.emit(
            TelemetryFrame.Position(
                PositionFrame(
                    timestampMs = frameTimestamp,
                    latDeg = currentLat,
                    lonDeg = currentLon,
                    altitudeM = 50f,
                    relativeAltitudeM = 50f,
                    groundSpeedMps = groundSpeed,
                    systemId = 1,
                    componentId = 1
                )
            )
        )

        // 4. BatteryFrame
        val battery = BatteryFrame(
            timestampMs = frameTimestamp,
            cellVoltagesV = cells,
            packVoltageV = cells.sum(),
            currentA = currentA,
            consumedMah = consumedMah,
            temperatureC = 30f + (100f - percent) * 0.15f,
            remainingPercent = percent.roundToInt(),
            batteryId = 0,
            systemId = 1,
            componentId = 1,
            stale = false,
            receivedAtMonotonicMs = monotonicReceiveTime
        )
        _frames.emit(TelemetryFrame.Battery(battery))
    }
}
