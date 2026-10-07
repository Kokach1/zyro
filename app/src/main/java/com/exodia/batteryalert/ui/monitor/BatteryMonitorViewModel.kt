package com.exodia.batteryalert.ui.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exodia.batteryalert.AppContainer
import com.exodia.batteryalert.core.alert.AlertEngine
import com.exodia.batteryalert.core.analysis.*
import com.exodia.batteryalert.core.config.BatteryProfiles
import com.exodia.batteryalert.core.config.RealConnectionConfig
import com.exodia.batteryalert.core.config.TransportConfig
import com.exodia.batteryalert.core.model.*
import com.exodia.batteryalert.core.telemetry.TelemetryRepository
import com.exodia.batteryalert.core.transport.SimulatorScenario
import com.exodia.batteryalert.core.transport.SimulatorTransport
import com.exodia.batteryalert.core.transport.TelemetryTransport
import kotlinx.coroutines.Job
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
    private var repositoryJob: Job? = null

    /** True when we have an active transport session (real or simulator). */
    private val _sessionActive = MutableStateFlow(false)
    val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    /** Intrinsic source of active session. Derived from transport metadata. */
    private val _sessionSource = MutableStateFlow<SessionSource?>(null)
    val sessionSource: StateFlow<SessionSource?> = _sessionSource.asStateFlow()

    val isSimulator: StateFlow<Boolean> = _sessionSource.map { it == SessionSource.SIMULATOR }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _uiState = MutableStateFlow(
        BatteryUiState(connection = ConnectionState.Disconnected)
    )
    val uiState: StateFlow<BatteryUiState> = _uiState.asStateFlow()

    /** Error message from transport/config failure. */
    private val _setupError = MutableStateFlow<String?>(null)
    val setupError: StateFlow<String?> = _setupError.asStateFlow()

    init {
        // Observe pre-existing active transport (e.g. rotation of activity)
        val existing = container.currentTransport
        if (existing != null) {
            attachTransport(existing)
        }
    }

    /** Called from Connection/Setup UI when user presses Start with typed real config. */
    fun startRealConnection(config: RealConnectionConfig) {
        viewModelScope.launch {
            _setupError.value = null
            val result = container.startRealTransport(config)
            if (result.isFailure) {
                _setupError.value = result.exceptionOrNull()?.message
                return@launch
            }
            attachTransport(result.getOrThrow())
        }
    }

    /** Legacy adapter for TransportConfig. Rejects SIMULATOR defensively. */
    fun startRealTransport(config: TransportConfig) {
        viewModelScope.launch {
            _setupError.value = null
            val result = container.startTransport(config)
            if (result.isFailure) {
                _setupError.value = result.exceptionOrNull()?.message
                return@launch
            }
            attachTransport(result.getOrThrow())
        }
    }

    /** Called from the explicit Simulator button only. */
    fun startSimulator() {
        viewModelScope.launch {
            _setupError.value = null
            resetAnalysisState()
            val sim = container.startSimulator()
            attachTransport(sim)
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
            _sessionSource.value = null
            resetAnalysisState()
            _uiState.value = BatteryUiState(connection = ConnectionState.Disconnected, sessionSource = null)
        }
    }

    private fun attachTransport(transport: TelemetryTransport) {
        // Clean up previous repository if any
        repositoryJob?.cancel()
        val oldRepo = repository
        repository = null

        val repo = TelemetryRepository(transport)
        repository = repo
        _sessionActive.value = true
        _sessionSource.value = transport.sessionSource

        repositoryJob = viewModelScope.launch {
            oldRepo?.stop()
            repo.start(this)
            repo.state.sample(250).collect { map(it) }
        }
    }

    private fun map(state: com.exodia.batteryalert.core.telemetry.TelemetryState) {
        val frame = state.battery
        if (frame != null) {
            val pack = detector.detect(frame)
            latestAnalysis = analyzer.analyze(frame, pack, history, flightClock.onFrame(frame.timestampMs, frame.currentA, state.vehicleState?.isArmed))
        }
        val distance = if (state.home != null && state.position != null && !state.positionStale)
            GeoMath.haversineMeters(state.home.latDeg, state.home.lonDeg, state.position.latDeg, state.position.lonDeg).toFloat()
        else null
        val rtl = if (distance != null && latestAnalysis != null) {
            RtlCalculator(profile).assess(distance, latestAnalysis?.consumptionMahPerMin, latestAnalysis?.remainingPercent ?: 0, latestAnalysis?.minutesRemaining)
        } else null
        val alert = alertEngine.evaluate(latestAnalysis, rtl, state.connection, frame?.timestampMs ?: System.currentTimeMillis())
        _uiState.value = BatteryUiState(
            connection = state.connection,
            sessionSource = state.sessionSource,
            analysis = latestAnalysis,
            rtl = rtl,
            alert = alert.active,
            cellFault = alert.cellFault,
            profile = profile,
            distanceM = distance,
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
        resetAnalysisState()
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
        repositoryJob?.cancel()
        super.onCleared()
    }
}
