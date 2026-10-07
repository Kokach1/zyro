package com.exodia.batteryalert.ui.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exodia.batteryalert.AppContainer
import com.exodia.batteryalert.core.alert.AlertEngine
import com.exodia.batteryalert.core.analysis.*
import com.exodia.batteryalert.core.config.BatteryProfiles
import com.exodia.batteryalert.core.config.TransportConfig
import com.exodia.batteryalert.core.config.TransportKind
import com.exodia.batteryalert.core.model.*
import com.exodia.batteryalert.core.telemetry.TelemetryRepository
import com.exodia.batteryalert.core.transport.SimulatorScenario
import com.exodia.batteryalert.core.transport.SimulatorTransport
import com.exodia.batteryalert.core.transport.TelemetryTransport
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.FlowPreview::class)
class BatteryMonitorViewModel(private val container: AppContainer) : ViewModel() {

    private var profile = BatteryProfiles.default
    private var analyzer = BatteryAnalyzer(profile)
    private val history = ConsumptionHistory(30)
    private val detector = ChemistryDetector(profile.chemistry)
    private val flightClock = FlightClock()
    private var latestAnalysis: BatteryAnalysis? = null
    private val alertEngine = AlertEngine()

    private var repository: TelemetryRepository? = null
    private var repositoryJob: kotlinx.coroutines.Job? = null

    /** True when we have an active transport session (real or simulator). */
    private val _sessionActive = MutableStateFlow(false)
    val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    /** True when the active session is the simulator. */
    private val _isSimulator = MutableStateFlow(false)
    val isSimulator: StateFlow<Boolean> = _isSimulator.asStateFlow()

    private val _uiState = MutableStateFlow(
        BatteryUiState(connection = ConnectionState.Disconnected, simulatorMode = false)
    )
    val uiState: StateFlow<BatteryUiState> = _uiState.asStateFlow()

    /** Error message from transport/config failure. */
    private val _setupError = MutableStateFlow<String?>(null)
    val setupError: StateFlow<String?> = _setupError.asStateFlow()

    init {
        // Observe pre-existing active transport (e.g. rotation of activity)
        val existing = container.currentTransport
        if (existing != null) {
            attachTransport(existing, container.activeSimulator != null)
        }
    }

    /** Called from Connection/Setup UI when user presses Start with real config. */
    fun startRealTransport(config: TransportConfig) {
        viewModelScope.launch {
            _setupError.value = null
            val result = container.startTransport(config)
            if (result.isFailure) {
                _setupError.value = result.exceptionOrNull()?.message
                return@launch
            }
            attachTransport(result.getOrThrow(), isSimulator = false)
        }
    }

    /** Called from the explicit Simulator button. */
    fun startSimulator() {
        viewModelScope.launch {
            _setupError.value = null
            resetAnalysisState()
            val sim = container.startSimulator()
            attachTransport(sim, isSimulator = true)
        }
    }

    /** Stop the current session and return to setup. */
    fun stopSession() {
        viewModelScope.launch {
            repositoryJob?.cancel()
            repository?.stop()
            repository = null
            repositoryJob = null
            container.stopTransport()
            _sessionActive.value = false
            _isSimulator.value = false
            resetAnalysisState()
            _uiState.value = BatteryUiState(connection = ConnectionState.Disconnected, simulatorMode = false)
        }
    }

    private fun attachTransport(transport: TelemetryTransport, isSimulator: Boolean) {
        // Cancel old repository if any (different transport)
        repositoryJob?.cancel()
        repository?.let { viewModelScope.launch { it.stop() } }

        val repo = TelemetryRepository(transport)
        repository = repo
        _sessionActive.value = true
        _isSimulator.value = isSimulator

        repositoryJob = viewModelScope.launch {
            repo.start(this)
            repo.state.sample(250).collect { map(it) }
        }
    }

    private fun map(state: com.exodia.batteryalert.core.telemetry.TelemetryState) {
        val frame = state.battery
        if (frame != null) {
            val pack = detector.detect(frame)
            latestAnalysis = analyzer.analyze(frame, pack, history, flightClock.onFrame(frame.timestampMs, frame.currentA))
        }
        val distance = if (state.home != null && state.position != null)
            GeoMath.haversineMeters(state.home.latDeg, state.home.lonDeg, state.position.latDeg, state.position.lonDeg).toFloat()
        else null
        val rtl = latestAnalysis?.let {
            RtlCalculator(profile).assess(distance, it.consumptionMahPerMin, it.remainingPercent, it.minutesRemaining)
        }
        val alert = alertEngine.evaluate(latestAnalysis, rtl, state.connection, frame?.timestampMs ?: System.currentTimeMillis())
        _uiState.value = BatteryUiState(
            connection = state.connection,
            analysis = latestAnalysis,
            rtl = rtl,
            alert = alert.active,
            cellFault = alert.cellFault,
            profile = profile,
            distanceM = distance,
            simulatorMode = _isSimulator.value,
        )
    }

    private fun resetAnalysisState() {
        history.clear()
        flightClock.reset()
        detector.reset()
        latestAnalysis = null
        analyzer = BatteryAnalyzer(profile)
    }

    // Simulator controls — only active when session is a SimulatorTransport
    fun scenario(s: SimulatorScenario) {
        container.activeSimulator?.setScenario(s)
    }
    fun speed(value: Int) {
        container.activeSimulator?.setSpeedMultiplier(value)
    }
    fun pause(paused: Boolean) {
        val simulator = container.activeSimulator
        if (paused) simulator?.pause() else simulator?.resume()
        _uiState.update { it.copy(isPaused = paused) }
    }
    fun reset() {
        container.activeSimulator?.resetToFull()
        resetAnalysisState()
    }
    fun profile(id: String) {
        container.activeSimulator?.setProfile(id)
        profile = BatteryProfiles.byId(id)
        analyzer = BatteryAnalyzer(profile)
        history.clear()
        detector.reset()
        flightClock.reset()
        _uiState.update { it.copy(profile = profile) }
    }

    override fun onCleared() {
        // Stop the repository job. Do NOT stop the container transport here —
        // the transport belongs to the container (application-level), not the ViewModel.
        // Stopping transport happens explicitly via stopSession() or AppContainer lifecycle.
        repositoryJob?.cancel()
        super.onCleared()
    }
}
