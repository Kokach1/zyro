package com.exodia.batteryalert.core.transport

import com.exodia.batteryalert.core.analysis.CellVoltageCurve
import com.exodia.batteryalert.core.config.BatteryProfiles
import com.exodia.batteryalert.core.config.BatteryProfile
import com.exodia.batteryalert.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*
import kotlin.random.Random

enum class SimulatorScenario(val label: String) { NORMAL_FLIGHT("Normal flight"), FAST_DISCHARGE("Fast discharge"), NOTICE_30("30 percent"), WARNING_20("20 percent"), CRITICAL_RTL("Dynamic RTL"), LOW_CELL("Low cell"), EMERGENCY("Emergency"), CELL_IMBALANCE("Cell fault"), LINK_LOST("Link lost") }
interface Clock { fun nowMs(): Long }
object SystemClock : Clock { override fun nowMs(): Long = System.currentTimeMillis() }

class SimulatorTransport(private val clock: Clock = SystemClock) : TelemetryTransport {
    override val id = "simulator"; override val displayName = "Simulated"
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    private val _frames = MutableSharedFlow<TelemetryFrame>(extraBufferCapacity = 16)
    override val frames: Flow<TelemetryFrame> = _frames.asSharedFlow()
    private var scope: CoroutineScope? = null
    private var profile: BatteryProfile = BatteryProfiles.default
    private var scenario = SimulatorScenario.NORMAL_FLIGHT
    private var multiplier = 1
    private var paused = false
    private var consumed = 0f; private var simulatedMs = 0L; private var distanceM = 0f; private var homePublished = false
    override suspend fun start() {
        if (scope != null) return
        _connectionState.value = ConnectionState.Connecting
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { worker -> worker.launch {
            delay(800); _connectionState.value = ConnectionState.Connected
            while (isActive) { emitTick(); delay(200) }
        } }
    }
    override suspend fun stop() { scope?.cancel(); scope = null; _connectionState.value = ConnectionState.Disconnected }
    fun setScenario(value: SimulatorScenario) { scenario = value; resetToFull(); when (value) { SimulatorScenario.NOTICE_30 -> consumed = profile.capacityMah * .7f; SimulatorScenario.WARNING_20 -> consumed = profile.capacityMah * .8f; SimulatorScenario.CRITICAL_RTL -> { consumed = profile.capacityMah * .7f; distanceM = 900f }; else -> Unit } }
    fun setSpeedMultiplier(value: Int) { multiplier = value.coerceIn(1, 20) }
    fun setProfile(id: String) { profile = BatteryProfiles.byId(id); resetToFull() }
    fun pause() { paused = true }; fun resume() { paused = false }
    fun resetToFull() { consumed = 0f; simulatedMs = 0L; distanceM = 0f; homePublished = false; paused = false }
    private suspend fun emitTick() {
        if (paused) return
        if (scenario == SimulatorScenario.LINK_LOST && simulatedMs in 2_000..10_000) { _connectionState.value = ConnectionState.LinkLost(clock.nowMs()); simulatedMs += 200L * multiplier; return }
        _connectionState.value = ConnectionState.Connected
        val dt = 0.2f * multiplier; simulatedMs += (dt * 1_000).toLong()
        val baseCurrent = when (scenario) { SimulatorScenario.CRITICAL_RTL -> 110f; SimulatorScenario.EMERGENCY -> 140f; else -> 90f }
        val current = if (scenario == SimulatorScenario.CRITICAL_RTL) baseCurrent else baseCurrent + sin(simulatedMs / 3_000.0).toFloat() * 4f
        consumed = (consumed + current * dt / 3.6f).coerceAtMost(profile.capacityMah.toFloat())
        if (scenario != SimulatorScenario.CRITICAL_RTL) distanceM += 5f * dt
        val percent = (100f - consumed / profile.capacityMah * 100f).coerceIn(0f, 100f)
        val open = CellVoltageCurve.voltageForPercent(percent)
        val cells = List(profile.cellCount) { index -> open - current * .002f + ((index % 5) - 2) * .006f + Random.nextFloat() * .006f - .003f }.toMutableList()
        when (scenario) { SimulatorScenario.LOW_CELL -> cells[0] = 3.48f; SimulatorScenario.EMERGENCY -> cells[0] = 3.38f; SimulatorScenario.CELL_IMBALANCE -> { cells[0] = open - .11f; cells[1] = open }; else -> Unit }
        val timestamp = clock.nowMs() + simulatedMs
        val battery = BatteryFrame(timestamp, cells, cells.sum(), current, consumed, 28f + (100f - percent) * .2f, percent.toInt())
        val homeLat = 10.0159; val homeLon = 76.3419; val lon = homeLon + distanceM / 111_195.0
        if (!homePublished) { _frames.emit(TelemetryFrame.Position(PositionFrame(timestamp, homeLat, homeLon, 0f, 0f, 0f))); homePublished = true }
        _frames.emit(TelemetryFrame.Battery(battery)); _frames.emit(TelemetryFrame.Position(PositionFrame(timestamp, homeLat, lon, 0f, 0f, 5f)))
    }
}
