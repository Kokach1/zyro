package com.exodia.batteryalert.ui.monitor

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.*
import com.exodia.batteryalert.core.analysis.RingGeometry
import com.exodia.batteryalert.core.config.AppConfig
import com.exodia.batteryalert.core.config.TransportConfig
import com.exodia.batteryalert.core.config.TransportKind
import com.exodia.batteryalert.core.model.*
import com.exodia.batteryalert.core.transport.SimulatorScenario
import com.exodia.batteryalert.ui.debug.SimulatorControlSheet
import com.exodia.batteryalert.ui.setup.ConnectionSetupSheet
import com.exodia.batteryalert.ui.theme.*

@Composable fun BatteryMonitorScreen(viewModel: BatteryMonitorViewModel) {
    val state by viewModel.uiState.collectAsState()
    val sessionActive by viewModel.sessionActive.collectAsState()
    val isSimulator by viewModel.isSimulator.collectAsState()
    val setupError by viewModel.setupError.collectAsState()
    BatteryDashboard(
        state = state,
        sessionActive = sessionActive,
        isSimulator = isSimulator,
        setupError = setupError,
        onStartReal = viewModel::startRealTransport,
        onStartSimulator = viewModel::startSimulator,
        onStopSession = viewModel::stopSession,
        scenario = viewModel::scenario,
        speed = viewModel::speed,
        pause = viewModel::pause,
        reset = viewModel::reset,
        profile = viewModel::profile,
    )
}

@Composable fun BatteryDashboard(
    state: BatteryUiState,
    sessionActive: Boolean = false,
    isSimulator: Boolean = false,
    setupError: String? = null,
    onStartReal: (TransportConfig) -> Unit = {},
    onStartSimulator: () -> Unit = {},
    onStopSession: () -> Unit = {},
    scenario: (SimulatorScenario) -> Unit = {},
    speed: (Int) -> Unit = {},
    pause: (Boolean) -> Unit = {},
    reset: () -> Unit = {},
    profile: (String) -> Unit = {},
) {
    var showSimSheet by remember { mutableStateOf(false) }
    var showSetupSheet by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(AlertColors.Background)) {
        Column(Modifier.fillMaxSize()) {
            StatusBar(
                state = state,
                sessionActive = sessionActive,
                isSimulator = isSimulator,
                openSetup = { showSetupSheet = true },
                openSimulator = { showSimSheet = true },
                onStop = onStopSession,
            )

            if (!sessionActive) {
                // Cold launch: show real connection setup on the monitoring screen
                ConnectionSetupInline(
                    error = setupError,
                    onStartReal = onStartReal,
                    onStartSimulator = onStartSimulator,
                )
            } else {
                Row(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Hero(state, Modifier.weight(.46f).fillMaxHeight())
                    CellPanel(state, Modifier.weight(.54f).fillMaxHeight())
                }
            }
        }

        // FULLSCREEN_FRD alert overlays (company-exact default, Step 14 will complete visuals)
        state.alert?.let { alert ->
            if (sessionActive) {
                FrdAlertOverlay(alert = alert, cellFault = state.cellFault, analysis = state.analysis)
            }
        }
    }

    if (showSimSheet && isSimulator) {
        SimulatorControlSheet(state, { showSimSheet = false }, scenario, speed, pause, reset, profile)
    }
    if (showSetupSheet) {
        ConnectionSetupSheet(
            onDismiss = { showSetupSheet = false },
            onStartReal = { config -> onStartReal(config); showSetupSheet = false },
            onStartSimulator = { onStartSimulator(); showSetupSheet = false },
        )
    }
}

// ───────────────────────────── Connection Setup Inline ──────────────────────

@Composable private fun ConnectionSetupInline(
    error: String?,
    onStartReal: (TransportConfig) -> Unit,
    onStartSimulator: () -> Unit,
) {
    var selectedKind by remember { mutableStateOf(TransportKind.UDP) }
    var udpPort by remember { mutableStateOf("14550") }
    var serialPath by remember { mutableStateOf("") }
    var replayPath by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Connect to Drone", color = AlertColors.Primary, style = Tabular.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(6.dp))
        Text("Select a telemetry source to begin monitoring.", color = AlertColors.Secondary, fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))

        // Source type selector
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                TransportKind.UDP to "UDP",
                TransportKind.USB_SERIAL to "USB",
                TransportKind.INTERNAL_SERIAL to "Internal",
                TransportKind.REPLAY to "Replay",
            ).forEach { (kind, label) ->
                val selected = selectedKind == kind
                Box(
                    Modifier
                        .background(if (selected) AlertColors.Accent.copy(alpha = .18f) else AlertColors.Surface, RoundedCornerShape(8.dp))
                        .border(1.dp, if (selected) AlertColors.Accent else AlertColors.Outline, RoundedCornerShape(8.dp))
                        .clickable { selectedKind = kind }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = if (selected) AlertColors.Accent else AlertColors.Secondary, style = Tabular.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Source-specific fields
        when (selectedKind) {
            TransportKind.UDP ->
                SetupNote("Listening on 0.0.0.0:$udpPort (labelled generic default — not a confirmed G20 endpoint). Point your GCS sender to this device's IP.")
            TransportKind.USB_SERIAL ->
                SetupNote("Connect via USB OTG cable. App will enumerate compatible USB serial devices. G20 USB VID/PID unconfirmed — NOT_TESTED.")
            TransportKind.INTERNAL_SERIAL ->
                SetupNote("Requires vendor-granted access to /dev/ttySx. G20 serial node and baud unconfirmed. NOT_TESTED until bench validation.")
            TransportKind.REPLAY ->
                SetupNote("Select a JSONL replay file. No hardware writes during replay.")
            else -> {}
        }

        Spacer(Modifier.height(8.dp))
        if (error != null) {
            Text("⚠ $error", color = AlertColors.Warning, style = Tabular.copy(fontSize = 12.sp), modifier = Modifier.fillMaxWidth(0.7f))
            Spacer(Modifier.height(8.dp))
        }

        // Connect button
        Box(
            Modifier
                .background(AlertColors.Accent, RoundedCornerShape(10.dp))
                .clickable {
                    val config = when (selectedKind) {
                        TransportKind.UDP -> TransportConfig(kind = TransportKind.UDP, udpBindPort = udpPort.toIntOrNull() ?: 14550)
                        TransportKind.USB_SERIAL -> TransportConfig(kind = TransportKind.USB_SERIAL)
                        TransportKind.INTERNAL_SERIAL -> TransportConfig(kind = TransportKind.INTERNAL_SERIAL, serialDevicePath = serialPath.ifBlank { "/dev/ttyS3" })
                        TransportKind.REPLAY -> TransportConfig(kind = TransportKind.REPLAY, replayFilePath = replayPath)
                        else -> TransportConfig(kind = selectedKind)
                    }
                    onStartReal(config)
                }
                .padding(horizontal = 40.dp, vertical = 12.dp),
        ) {
            Text("Start Monitoring", color = Color.White, style = Tabular.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold))
        }

        Spacer(Modifier.height(14.dp))

        // Small explicit Simulator button — visually quieter
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier
                .background(AlertColors.Raised, RoundedCornerShape(8.dp))
                .border(1.dp, AlertColors.Outline, RoundedCornerShape(8.dp))
                .clickable { onStartSimulator() }
                .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text("▶ Simulator", color = AlertColors.Secondary, style = Tabular.copy(fontSize = 13.sp))
            }
            Text("Demo only — not a real drone connection", color = AlertColors.Disabled, fontSize = 11.sp)
        }
    }
}

@Composable private fun SetupNote(text: String) {
    Text(text, color = AlertColors.Disabled, style = Tabular.copy(fontSize = 11.sp), modifier = Modifier.fillMaxWidth(0.7f))
}

// ───────────────────────────── FRD Alert Overlays ───────────────────────────
// Step 14 will complete exact company matrix visuals; these are structural stubs.

@Composable private fun FrdAlertOverlay(alert: ActiveAlert, cellFault: Boolean, analysis: BatteryAnalysis?) {
    when (alert.level) {
        AlertLevel.EMERGENCY -> FlashingRedOverlay(alert, analysis)
        AlertLevel.CRITICAL -> FullScreenRedModal(alert, analysis)
        AlertLevel.WARNING -> AmberOverlay(alert, cellFault, analysis)
        AlertLevel.NOTICE -> YellowTopBanner(alert)
        else -> if (cellFault) OrangeOverlay(analysis)
    }
}

@Composable private fun YellowTopBanner(alert: ActiveAlert) {
    // FRD NOTICE: yellow banner on top bar
    Box(Modifier.fillMaxWidth().background(AlertColors.Notice.copy(alpha = .9f)).padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text("⚠  ${alert.message}", color = Color.Black, style = Tabular.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
        }
    }
}

@Composable private fun AmberOverlay(alert: ActiveAlert, cellFault: Boolean, analysis: BatteryAnalysis?) {
    // FRD WARNING: amber overlay + pulsing border (Step 14 adds pulsing animation)
    val alpha by rememberInfiniteTransition(label = "warn_pulse").animateFloat(
        initialValue = 0.3f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "warn_alpha"
    )
    Box(Modifier.fillMaxSize().background(AlertColors.Warning.copy(alpha = 0.12f)).border(3.dp, AlertColors.Warning.copy(alpha = alpha))) {
        Box(Modifier.fillMaxWidth().background(AlertColors.Warning.copy(alpha = .8f)).padding(10.dp)) {
            Text("⚠  ${alert.message}", color = Color.Black, style = Tabular.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), modifier = Modifier.align(Alignment.Center))
        }
        analysis?.let {
            Text("%.0f A  |  %.2f V min".format(it.currentA, it.minCellV),
                color = AlertColors.Warning, style = Tabular.copy(fontSize = 13.sp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp))
        }
    }
}

@Composable private fun FullScreenRedModal(alert: ActiveAlert, analysis: BatteryAnalysis?) {
    // FRD CRITICAL: full-screen red modal, no dismiss
    Box(Modifier.fillMaxSize().background(AlertColors.Critical.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("🔴 CRITICAL", color = Color.White, style = Tabular.copy(fontSize = 32.sp, fontWeight = FontWeight.ExtraBold))
            Text(alert.message, color = Color.White, style = Tabular.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
            analysis?.let { a ->
                Text("${a.remainingPercent}%  |  %.2f V min  |  %.0f A".format(a.minCellV, a.currentA),
                    color = Color.White.copy(alpha = .85f), style = Tabular.copy(fontSize = 16.sp))
            }
            Text("(Un-dismissable — pilot must initiate RTL)", color = Color.White.copy(alpha = .6f), fontSize = 12.sp)
        }
    }
}

@Composable private fun FlashingRedOverlay(alert: ActiveAlert, analysis: BatteryAnalysis?) {
    // FRD EMERGENCY: flashing red screen (bounded 2Hz max, reduced-motion: static)
    val alpha by rememberInfiniteTransition(label = "emg_flash").animateFloat(
        initialValue = 0.75f, targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "emg_alpha"
    )
    Box(Modifier.fillMaxSize().background(AlertColors.Emergency.copy(alpha = alpha)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("🆘 EMERGENCY", color = Color.White, style = Tabular.copy(fontSize = 34.sp, fontWeight = FontWeight.ExtraBold))
            Text(alert.message, color = Color.White, style = Tabular.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
            analysis?.let { a ->
                Text("%.2f V min  |  %.0f A".format(a.minCellV, a.currentA),
                    color = Color.White.copy(alpha = .9f), style = Tabular.copy(fontSize = 16.sp))
            }
        }
    }
}

@Composable private fun OrangeOverlay(analysis: BatteryAnalysis?) {
    // FRD CELL_FAULT: orange overlay
    Box(Modifier.fillMaxWidth().background(AlertColors.CellFault.copy(alpha = .15f)).border(2.dp, AlertColors.CellFault).padding(8.dp)) {
        Text("⚡ Cell voltage imbalance — land & inspect battery  Δ=%.3f V".format(analysis?.cellDeltaV ?: 0f),
            color = AlertColors.CellFault, style = Tabular.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.align(Alignment.Center))
    }
}

// ───────────────────────────── Status Bar ────────────────────────────────────

@Composable private fun StatusBar(
    state: BatteryUiState,
    sessionActive: Boolean,
    isSimulator: Boolean,
    openSetup: () -> Unit,
    openSimulator: () -> Unit,
    onStop: () -> Unit,
) {
    val sourceLabel = when {
        isSimulator -> "SIMULATOR"
        else -> when (state.connection) {
            is ConnectionState.Connected -> "LIVE"
            is ConnectionState.Connecting -> "Connecting…"
            is ConnectionState.LinkLost -> "Link lost"
            is ConnectionState.Error -> "Error"
            else -> "—"
        }
    }
    val sourceColor = when {
        isSimulator -> AlertColors.Notice
        state.connection is ConnectionState.Connected -> AlertColors.Normal
        state.connection is ConnectionState.LinkLost -> AlertColors.LinkLost
        state.connection is ConnectionState.Error -> AlertColors.Warning
        else -> AlertColors.Secondary
    }
    Row(
        Modifier.fillMaxWidth().height(56.dp)
            .background(AlertColors.Background.copy(alpha = .72f))
            .border(1.dp, AlertColors.Outline)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("○  $sourceLabel", color = sourceColor,
            style = Tabular.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))

        state.analysis?.takeIf { it.flightElapsedSec > 0 }?.let {
            Text("◷  ${formatFlightTime(it.flightElapsedSec)}", color = AlertColors.Primary,
                style = Tabular.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
        }

        Spacer(Modifier.weight(1f))

        state.distanceM?.let {
            Text("⌂  ${formatDistance(it)}", color = AlertColors.Primary,
                style = Tabular.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
        }
        state.rtl?.returnEtaSec?.let {
            Text("↩  ${formatSeconds(it)}",
                color = if (state.rtl.returnEtaExceedsTimeLeft) AlertColors.Warning else AlertColors.Primary,
                style = Tabular.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
        }

        if (sessionActive) {
            // Simulator controls button
            if (isSimulator) {
                Box(Modifier.size(48.dp).clickable { openSimulator() }, contentAlignment = Alignment.Center) {
                    Text("⋮", color = AlertColors.Secondary, fontSize = 26.sp)
                }
            }
            // Stop / disconnect
            Box(
                Modifier.background(AlertColors.Raised, RoundedCornerShape(6.dp))
                    .border(1.dp, AlertColors.Outline, RoundedCornerShape(6.dp))
                    .clickable { onStop() }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) { Text("Stop", color = AlertColors.Secondary, style = Tabular.copy(fontSize = 13.sp)) }
        } else {
            // Setup button
            Box(Modifier.size(48.dp).clickable { openSetup() }, contentAlignment = Alignment.Center) {
                Text("⋮", color = AlertColors.Secondary, fontSize = 26.sp)
            }
        }
    }
}

// ───────────────────────────── Hero & Ring ───────────────────────────────────

@Composable private fun Hero(state: BatteryUiState, modifier: Modifier) {
    val analysis = state.analysis
    val percent = analysis?.remainingPercent ?: 0
    val presentation = alertPresentation(state.alert, state.cellFault, state.connection)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        HeroRingReadout(percent, analysis != null, state.rtl?.requiredPercent, presentation)
        Spacer(Modifier.height(18.dp))
        Row {
            Metric("TIME LEFT", analysis?.minutesRemaining?.let { "${it.toInt()} min" } ?: "--", Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(58.dp).background(AlertColors.Outline))
            Metric("RETURN IN", formatSeconds(state.rtl?.returnEtaSec), Modifier.weight(1f))
        }
    }
}

@Composable private fun HeroRingReadout(percent: Int, hasData: Boolean, required: Float?, presentation: AlertPresentation?) {
    val ringDiameter = 420.dp
    val fontSize = HeroNumberLayout.fontSizeSp(ringDiameter.value, LocalDensity.current.density).sp
    Box(Modifier.size(ringDiameter), contentAlignment = Alignment.Center) {
        BatteryRing(percent, required, presentation?.suppressRingFill == true)
        required?.let { ReturnTickLabel(it, ringDiameter) }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                val numberColor = presentation?.color ?: AlertColors.Primary
                val glow = presentation?.color?.let { Shadow(color = it.copy(alpha = .55f), offset = Offset.Zero, blurRadius = 18f) }
                Text(if (hasData) "$percent" else "–", color = numberColor,
                    style = Tabular.copy(fontSize = fontSize, lineHeight = fontSize, fontWeight = FontWeight.SemiBold, shadow = glow))
                Text("%", color = numberColor,
                    style = Tabular.copy(fontSize = (fontSize.value * .30f).sp, fontWeight = FontWeight.Medium, shadow = glow))
            }
            Text(presentation?.label ?: "REMAINING", color = presentation?.color ?: AlertColors.Secondary,
                style = Tabular.copy(fontSize = 14.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium,
                    shadow = presentation?.color?.let { Shadow(color = it.copy(alpha = .45f), blurRadius = 10f) }))
        }
    }
}

@Composable private fun ReturnTickLabel(requiredPercent: Float, ringDiameter: Dp) {
    val radius = ringDiameter.value / 2f - HeroNumberLayout.ringStrokeDp / 2f + 14f
    val radians = Math.toRadians(RingGeometry.angleFor(requiredPercent).toDouble())
    val x = kotlin.math.cos(radians).toFloat() * radius
    val y = kotlin.math.sin(radians).toFloat() * radius
    Text("RTL", color = AlertColors.Secondary, style = Tabular.copy(fontSize = 10.sp), modifier = Modifier.offset(x.dp, y.dp))
}

@Composable private fun BatteryRing(percent: Int, required: Float?, suppressFill: Boolean) {
    val sweep by animateFloatAsState(percent / 100f * 270f, tween(400), label = "ring")
    Canvas(Modifier.fillMaxSize()) {
        val stroke = HeroNumberLayout.ringStrokeDp.dp.toPx()
        val zoneStroke = 6.dp.toPx()
        val mainSize = size.minDimension - stroke
        val mainTop = Offset((this.size.width - mainSize) / 2, (this.size.height - mainSize) / 2)
        val zoneSize = mainSize + stroke + zoneStroke * 2
        val zoneTop = Offset((this.size.width - zoneSize) / 2, (this.size.height - zoneSize) / 2)
        drawArc(AlertColors.Outline, RingGeometry.startAngle, RingGeometry.totalSweep, false, mainTop, Size(mainSize, mainSize), style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(AlertColors.Critical.copy(.35f), RingGeometry.angleFor(0f), RingGeometry.fillSweep(AppConfig.warningPercent.toFloat()), false, zoneTop, Size(zoneSize, zoneSize), style = Stroke(zoneStroke, cap = StrokeCap.Butt))
        if (required != null && required > AppConfig.warningPercent)
            drawArc(AlertColors.Notice.copy(.35f), RingGeometry.angleFor(AppConfig.warningPercent.toFloat()), RingGeometry.fillSweep(required - AppConfig.warningPercent), false, zoneTop, Size(zoneSize, zoneSize), style = Stroke(zoneStroke, cap = StrokeCap.Butt))
        if (!suppressFill)
            drawArc(AlertColors.Normal, RingGeometry.startAngle, sweep, false, mainTop, Size(mainSize, mainSize), style = Stroke(stroke, cap = StrokeCap.Round))
        if (required != null) {
            val radians = Math.toRadians(RingGeometry.angleFor(required).toDouble())
            val center = Offset(this.size.width / 2, this.size.height / 2)
            val radius = mainSize / 2 + stroke / 2
            val outer = Offset(center.x + kotlin.math.cos(radians).toFloat() * (radius + 5.dp.toPx()), center.y + kotlin.math.sin(radians).toFloat() * (radius + 5.dp.toPx()))
            val inner = Offset(center.x + kotlin.math.cos(radians).toFloat() * (radius - 5.dp.toPx()), center.y + kotlin.math.sin(radians).toFloat() * (radius - 5.dp.toPx()))
            drawLine(Color.White, inner, outer, 3.dp.toPx())
        }
    }
}

// ───────────────────────────── Cell Panel ────────────────────────────────────

@Composable private fun CellPanel(state: BatteryUiState, modifier: Modifier) {
    val a = state.analysis
    Column(
        modifier.background(AlertColors.Surface, RoundedCornerShape(20.dp))
            .border(1.dp, AlertColors.Outline, RoundedCornerShape(20.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CELLS", color = AlertColors.Secondary, style = Tabular.copy(fontSize = 14.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium))
            Spacer(Modifier.width(10.dp))
            Text("${a?.cellCount ?: "–"}S", color = AlertColors.Primary, style = Tabular.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
            if (state.simulatorMode) {
                Spacer(Modifier.width(8.dp))
                Text("DEMO", color = AlertColors.Notice, style = Tabular.copy(fontSize = 11.sp))
            }
            Spacer(Modifier.weight(1f))
            Text("Avg ${a?.avgCellV?.let { "%.2f V".format(it) } ?: "–"}", color = AlertColors.Secondary)
            Spacer(Modifier.width(18.dp))
            Text("Delta ${a?.cellDeltaV?.let { "%.2f V".format(it) } ?: "–"}", color = if (state.cellFault) AlertColors.CellFault else AlertColors.Secondary)
        }
        val columns = when (a?.cellCount) { 6 -> 3; 12 -> 6; else -> 7 }
        a?.cellVoltagesV?.chunked(columns)?.forEachIndexed { row, cells ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cells.forEachIndexed { col, voltage ->
                    CellTile(row * columns + col + 1, voltage, a.minCellV, a.maxCellV, state.cellFault, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth()) {
            Footer("PACK", a?.packVoltageV?.let { "%.1f V".format(it) } ?: "–", Modifier.weight(1f))
            Footer("CURRENT", a?.currentA?.let { "%.0f A".format(it) } ?: "–", Modifier.weight(1f))
            Footer("TEMP", a?.temperatureC?.let { "%.0f C".format(it) } ?: "–", Modifier.weight(1f))
        }
    }
}

@Composable private fun CellTile(index: Int, voltage: Float, minimum: Float, maximum: Float, cellFault: Boolean, modifier: Modifier) {
    val warning = voltage <= AppConfig.warningCellVoltageV
    val imbalanceCell = cellFault && (voltage == minimum || voltage == maximum)
    val orange = warning || imbalanceCell
    Column(
        modifier.background(AlertColors.Raised, RoundedCornerShape(14.dp))
            .border(if (orange) 1.dp else 0.dp, if (orange) AlertColors.CellFault else AlertColors.Secondary, RoundedCornerShape(14.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("$index", modifier = Modifier.fillMaxWidth(), color = AlertColors.Secondary, fontSize = 12.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        Text("%.2f".format(voltage), color = if (orange) AlertColors.CellFault else AlertColors.Primary,
            style = Tabular.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold))
        Box(Modifier.fillMaxWidth().height(5.dp).background(if (orange) AlertColors.CellFault else AlertColors.Secondary, RoundedCornerShape(99.dp)))
    }
}

@Composable private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = AlertColors.Secondary, style = Tabular.copy(fontSize = 14.sp, letterSpacing = 1.2.sp))
        Text(value, color = AlertColors.Primary, style = Tabular.copy(fontSize = 36.sp, fontWeight = FontWeight.SemiBold))
    }
}

@Composable private fun Footer(label: String, value: String, modifier: Modifier) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(label, color = AlertColors.Secondary, fontSize = 12.sp)
    Text(value, color = AlertColors.Primary, style = Tabular.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold))
}

// ───────────────────────────── Previews ──────────────────────────────────────

@Preview(widthDp = 960, heightDp = 540) @Composable private fun PreviewSetup() = BatteryAlertTheme { BatteryDashboard(BatteryUiState(), sessionActive = false) }
@Preview(widthDp = 960, heightDp = 540) @Composable private fun PreviewNormal() = BatteryAlertTheme { BatteryDashboard(BatteryUiState(), sessionActive = true, isSimulator = true) }
@Preview @Composable private fun PreviewHero100() = BatteryAlertTheme { HeroRingReadout(100, true, null, null) }
@Preview @Composable private fun PreviewHero99() = BatteryAlertTheme { HeroRingReadout(99, true, null, null) }
@Preview @Composable private fun PreviewHero50() = BatteryAlertTheme { HeroRingReadout(50, true, null, null) }
@Preview @Composable private fun PreviewHero9() = BatteryAlertTheme { HeroRingReadout(9, true, null, null) }
@Preview @Composable private fun PreviewHero0() = BatteryAlertTheme { HeroRingReadout(0, true, null, null) }
