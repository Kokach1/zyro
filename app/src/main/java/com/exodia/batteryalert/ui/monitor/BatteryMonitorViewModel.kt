package com.exodia.batteryalert.ui.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exodia.batteryalert.core.alert.AlertEngine
import com.exodia.batteryalert.core.analysis.*
import com.exodia.batteryalert.core.config.BatteryProfiles
import com.exodia.batteryalert.core.model.*
import com.exodia.batteryalert.core.telemetry.TelemetryRepository
import com.exodia.batteryalert.core.transport.SimulatorScenario
import com.exodia.batteryalert.core.transport.SimulatorTransport
import com.exodia.batteryalert.core.transport.TelemetryTransport
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.FlowPreview::class)
class BatteryMonitorViewModel(private val transport: TelemetryTransport) : ViewModel() {
    private var profile = BatteryProfiles.default
    private var analyzer = BatteryAnalyzer(profile); private val history = ConsumptionHistory(30); private val detector = ChemistryDetector(profile.chemistry); private val flightClock = FlightClock(); private var latestAnalysis: BatteryAnalysis? = null
    private val repository = TelemetryRepository(transport); private val alertEngine = AlertEngine()
    private val _uiState = MutableStateFlow(BatteryUiState(profile = profile)); val uiState: StateFlow<BatteryUiState> = _uiState.asStateFlow()
    init { viewModelScope.launch { repository.start(this) }; viewModelScope.launch { repository.state.sample(250).collect { map(it) } } }
    private fun map(state: com.exodia.batteryalert.core.telemetry.TelemetryState) {
        val frame = state.battery
        if (frame != null) { val pack = detector.detect(frame); latestAnalysis = analyzer.analyze(frame, pack, history, flightClock.onFrame(frame.timestampMs, frame.currentA)) }
        val distance = if (state.home != null && state.position != null) GeoMath.haversineMeters(state.home.latDeg, state.home.lonDeg, state.position.latDeg, state.position.lonDeg).toFloat() else null
        val rtl = latestAnalysis?.let { RtlCalculator(profile).assess(distance, it.consumptionMahPerMin, it.remainingPercent, it.minutesRemaining) }
        val alert = alertEngine.evaluate(latestAnalysis, rtl, state.connection, frame?.timestampMs ?: System.currentTimeMillis())
        _uiState.value = BatteryUiState(state.connection, latestAnalysis, rtl, alert.active, alert.cellFault, profile, distance)
    }
    fun scenario(s: SimulatorScenario) { (transport as? SimulatorTransport)?.setScenario(s) }
    fun speed(value: Int) { (transport as? SimulatorTransport)?.setSpeedMultiplier(value) }
    fun pause(paused: Boolean) { val simulator = transport as? SimulatorTransport; if (paused) simulator?.pause() else simulator?.resume(); _uiState.update { it.copy(isPaused = paused) } }
    fun reset() { (transport as? SimulatorTransport)?.resetToFull(); history.clear(); flightClock.reset(); detector.reset() }
    fun profile(id: String) { (transport as? SimulatorTransport)?.setProfile(id); profile = BatteryProfiles.byId(id); analyzer = BatteryAnalyzer(profile); history.clear(); detector.reset(); flightClock.reset(); _uiState.update { it.copy(profile = profile) } }
    override fun onCleared() { viewModelScope.launch { repository.stop() }; super.onCleared() }
}
