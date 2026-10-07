package com.exodia.batteryalert.ui.monitor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exodia.batteryalert.core.analysis.RingGeometry
import com.exodia.batteryalert.core.config.AppConfig
import com.exodia.batteryalert.core.config.RealConnectionConfig
import com.exodia.batteryalert.core.model.*
import com.exodia.batteryalert.core.transport.SimulatorScenario
import com.exodia.batteryalert.ui.debug.SimulatorControlSheet
import com.exodia.batteryalert.ui.setup.ConnectionSetupContent
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
        onStartReal = viewModel::startRealConnection,
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
    onStartReal: (RealConnectionConfig) -> Unit = {},
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

    val themeColors = dashboardThemeColors(state.alert?.level)
    val isWarning = state.alert?.level == AlertLevel.WARNING

    Box(
        Modifier
            .fillMaxSize()
            .background(themeColors.background)
            .then(if (isWarning) Modifier.border(2.dp, AlertColors.Warning) else Modifier)
    ) {
        Column(Modifier.fillMaxSize()) {
            StatusBar(
                state = state,
                sessionActive = sessionActive,
                isSimulator = isSimulator,
                themeColors = themeColors,
                openSetup = { showSetupSheet = true },
                openSimulator = { showSimSheet = true },
                onStop = onStopSession,
            )

            // Compact yellow top banner for NOTICE alert
            if (sessionActive && state.alert?.level == AlertLevel.NOTICE) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(AlertColors.Notice.copy(alpha = 0.9f))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = state.alert.message.ifBlank { "Plan to return soon" },
                        color = Color.Black,
                        style = Tabular.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            if (!sessionActive) {
                ConnectionSetupContent(
                    themeColors = themeColors,
                    errorMessage = setupError,
                    onStartReal = onStartReal,
                    onStartSimulator = onStartSimulator,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Hero(state, themeColors, Modifier.weight(0.46f).fillMaxHeight())
                    CellPanel(state, themeColors, Modifier.weight(0.54f).fillMaxHeight())
                }
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

// ───────────────────────────── Status Bar ────────────────────────────────────

@Composable private fun StatusBar(
    state: BatteryUiState,
    sessionActive: Boolean,
    isSimulator: Boolean,
    themeColors: DashboardThemeColors,
    openSetup: () -> Unit,
    openSimulator: () -> Unit,
    onStop: () -> Unit,
) {
    val sourceLabel = when (state.sessionSource) {
        SessionSource.SIMULATOR -> "SIMULATOR"
        SessionSource.REPLAY -> "REPLAY"
        SessionSource.LIVE_UDP, SessionSource.LIVE_USB, SessionSource.LIVE_INTERNAL -> {
            when (state.connection) {
                is ConnectionState.Connected -> "LIVE"
                is ConnectionState.Connecting -> "Connecting"
                is ConnectionState.LinkLost -> "Link lost"
                is ConnectionState.Error -> "Error"
                else -> "Ready"
            }
        }
        null -> when (state.connection) {
            is ConnectionState.Connected -> "LIVE"
            is ConnectionState.Connecting -> "Connecting"
            is ConnectionState.LinkLost -> "Link lost"
            is ConnectionState.Error -> "Error"
            else -> "Ready"
        }
    }
    val sourceColor = when (state.sessionSource) {
        SessionSource.SIMULATOR -> AlertColors.Notice
        SessionSource.REPLAY -> AlertColors.Secondary
        else -> when (state.connection) {
            is ConnectionState.Connected -> AlertColors.Normal
            is ConnectionState.LinkLost -> AlertColors.LinkLost
            is ConnectionState.Error -> AlertColors.Warning
            else -> themeColors.secondaryText
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(themeColors.surface)
            .border(1.dp, themeColors.outline)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            sourceLabel,
            color = sourceColor,
            style = Tabular.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        )

        state.analysis?.takeIf { it.flightElapsedSec > 0 }?.let {
            Text(
                "FLIGHT ${formatFlightTime(it.flightElapsedSec)}",
                color = themeColors.primaryText,
                style = Tabular.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            )
        }

        Spacer(Modifier.weight(1f))

        state.distanceM?.let {
            Text(
                "DIST ${formatDistance(it)}",
                color = themeColors.primaryText,
                style = Tabular.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            )
        }
        state.rtl?.returnEtaSec?.let {
            Text(
                "RTL ${formatSeconds(it)}",
                color = if (state.rtl.returnEtaExceedsTimeLeft) AlertColors.Warning else themeColors.primaryText,
                style = Tabular.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            )
        }

        if (sessionActive) {
            if (isSimulator) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clickable { openSimulator() },
                    contentAlignment = Alignment.Center
                ) {
                    MoreVertIcon(themeColors.secondaryText)
                }
            }
            Box(
                Modifier
                    .background(themeColors.raised, RoundedCornerShape(6.dp))
                    .border(1.dp, themeColors.outline, RoundedCornerShape(6.dp))
                    .clickable { onStop() }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Stop",
                    color = themeColors.secondaryText,
                    style = Tabular.copy(fontSize = 13.sp)
                )
            }
        } else {
            Box(
                Modifier
                    .size(48.dp)
                    .clickable { openSetup() },
                contentAlignment = Alignment.Center
            ) {
                MoreVertIcon(themeColors.secondaryText)
            }
        }
    }
}

@Composable private fun MoreVertIcon(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val cx = size.width / 2f
        val r = 2.dp.toPx()
        drawCircle(tint, r, Offset(cx, size.height * 0.2f))
        drawCircle(tint, r, Offset(cx, size.height * 0.5f))
        drawCircle(tint, r, Offset(cx, size.height * 0.8f))
    }
}

// ───────────────────────────── Hero & Ring ───────────────────────────────────

@Composable private fun Hero(state: BatteryUiState, themeColors: DashboardThemeColors, modifier: Modifier) {
    val analysis = state.analysis
    val percent = analysis?.remainingPercent ?: 0
    val presentation = alertPresentation(state.alert, state.cellFault, state.connection)

    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        HeroRingReadout(percent, analysis != null, state.rtl?.requiredPercent, presentation, themeColors)
        Spacer(Modifier.height(18.dp))
        Row {
            Metric(
                label = "TIME LEFT",
                value = analysis?.minutesRemaining?.let { "${it.toInt()} min" } ?: "--",
                themeColors = themeColors,
                modifier = Modifier.weight(1f)
            )
            Box(Modifier.width(1.dp).height(58.dp).background(themeColors.outline))
            Metric(
                label = "RETURN IN",
                value = formatSeconds(state.rtl?.returnEtaSec),
                themeColors = themeColors,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable private fun HeroRingReadout(
    percent: Int,
    hasData: Boolean,
    required: Float?,
    presentation: AlertPresentation?,
    themeColors: DashboardThemeColors,
) {
    val ringDiameter = 420.dp
    val fontSize = HeroNumberLayout.fontSizeSp(ringDiameter.value, LocalDensity.current.density).sp
    val numberColor = presentation?.color ?: themeColors.primaryText

    Box(Modifier.size(ringDiameter), contentAlignment = Alignment.Center) {
        BatteryRing(percent, required, presentation?.suppressRingFill == true, themeColors)
        required?.let { ReturnTickLabel(it, ringDiameter, themeColors) }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (hasData) "$percent" else "–",
                    color = numberColor,
                    style = Tabular.copy(fontSize = fontSize, lineHeight = fontSize, fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = "%",
                    color = numberColor,
                    style = Tabular.copy(fontSize = (fontSize.value * 0.30f).sp, fontWeight = FontWeight.Medium)
                )
            }
            Text(
                text = presentation?.label ?: "REMAINING",
                color = presentation?.color ?: themeColors.secondaryText,
                style = Tabular.copy(fontSize = 14.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium)
            )
            presentation?.actionPrompt?.let { prompt ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = prompt,
                    color = presentation.color,
                    style = Tabular.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable private fun ReturnTickLabel(requiredPercent: Float, ringDiameter: Dp, themeColors: DashboardThemeColors) {
    val radius = ringDiameter.value / 2f - HeroNumberLayout.ringStrokeDp / 2f + 14f
    val radians = Math.toRadians(RingGeometry.angleFor(requiredPercent).toDouble())
    val x = kotlin.math.cos(radians).toFloat() * radius
    val y = kotlin.math.sin(radians).toFloat() * radius
    Text(
        "RTL",
        color = themeColors.secondaryText,
        style = Tabular.copy(fontSize = 10.sp),
        modifier = Modifier.offset(x.dp, y.dp)
    )
}

@Composable private fun BatteryRing(
    percent: Int,
    required: Float?,
    suppressFill: Boolean,
    themeColors: DashboardThemeColors,
) {
    val sweep by animateFloatAsState(percent / 100f * 270f, tween(400), label = "ring")
    Canvas(Modifier.fillMaxSize()) {
        val stroke = HeroNumberLayout.ringStrokeDp.dp.toPx()
        val zoneStroke = 6.dp.toPx()
        val mainSize = size.minDimension - stroke
        val mainTop = Offset((this.size.width - mainSize) / 2, (this.size.height - mainSize) / 2)
        val zoneSize = mainSize + stroke + zoneStroke * 2
        val zoneTop = Offset((this.size.width - zoneSize) / 2, (this.size.height - zoneSize) / 2)

        drawArc(
            color = themeColors.outline,
            startAngle = RingGeometry.startAngle,
            sweepAngle = RingGeometry.totalSweep,
            useCenter = false,
            topLeft = mainTop,
            size = Size(mainSize, mainSize),
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
        drawArc(
            color = AlertColors.Critical.copy(alpha = 0.35f),
            startAngle = RingGeometry.angleFor(0f),
            sweepAngle = RingGeometry.fillSweep(AppConfig.warningPercent.toFloat()),
            useCenter = false,
            topLeft = zoneTop,
            size = Size(zoneSize, zoneSize),
            style = Stroke(zoneStroke, cap = StrokeCap.Butt)
        )
        if (required != null && required > AppConfig.warningPercent) {
            drawArc(
                color = AlertColors.Notice.copy(alpha = 0.35f),
                startAngle = RingGeometry.angleFor(AppConfig.warningPercent.toFloat()),
                sweepAngle = RingGeometry.fillSweep(required - AppConfig.warningPercent),
                useCenter = false,
                topLeft = zoneTop,
                size = Size(zoneSize, zoneSize),
                style = Stroke(zoneStroke, cap = StrokeCap.Butt)
            )
        }
        if (!suppressFill) {
            drawArc(
                color = AlertColors.Normal,
                startAngle = RingGeometry.startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = mainTop,
                size = Size(mainSize, mainSize),
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
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

@Composable private fun CellPanel(
    state: BatteryUiState,
    themeColors: DashboardThemeColors,
    modifier: Modifier
) {
    val a = state.analysis
    Column(
        modifier
            .background(themeColors.surface, RoundedCornerShape(20.dp))
            .border(1.dp, themeColors.outline, RoundedCornerShape(20.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "CELLS",
                color = themeColors.secondaryText,
                style = Tabular.copy(fontSize = 14.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "${a?.cellCount ?: "–"}S",
                color = themeColors.primaryText,
                style = Tabular.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            )
            if (state.simulatorMode) {
                Spacer(Modifier.width(8.dp))
                Text("DEMO", color = AlertColors.Notice, style = Tabular.copy(fontSize = 11.sp))
            }
            Spacer(Modifier.weight(1f))
            Text("Avg ${a?.avgCellV?.let { "%.2f V".format(it) } ?: "–"}", color = themeColors.secondaryText)
            Spacer(Modifier.width(18.dp))
            Text(
                "Delta ${a?.cellDeltaV?.let { "%.2f V".format(it) } ?: "–"}",
                color = if (state.cellFault) AlertColors.CellFault else themeColors.secondaryText
            )
        }

        // In-place orange cell fault banner coexisting with red Critical/Emergency
        if (state.cellFault) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(AlertColors.CellFault.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .border(1.dp, AlertColors.CellFault, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                val deltaText = a?.cellDeltaV?.let { "Delta %.3f V".format(it) } ?: ""
                Text(
                    text = "Cell voltage imbalance - land & inspect battery  $deltaText",
                    color = AlertColors.CellFault,
                    style = Tabular.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        val columns = when (a?.cellCount) { 6 -> 3; 12 -> 6; else -> 7 }
        a?.cellVoltagesV?.chunked(columns)?.forEachIndexed { row, cells ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cells.forEachIndexed { col, voltage ->
                    CellTile(
                        index = row * columns + col + 1,
                        voltage = voltage,
                        minimum = a.minCellV ?: 0f,
                        maximum = a.maxCellV ?: 0f,
                        cellFault = state.cellFault,
                        themeColors = themeColors,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth()) {
            Footer("PACK", a?.packVoltageV?.let { "%.1f V".format(it) } ?: "–", themeColors, Modifier.weight(1f))
            Footer("CURRENT", a?.currentA?.let { "%.0f A".format(it) } ?: "–", themeColors, Modifier.weight(1f))
            Footer("TEMP", a?.temperatureC?.let { "%.0f C".format(it) } ?: "–", themeColors, Modifier.weight(1f))
        }
    }
}

@Composable private fun CellTile(
    index: Int,
    voltage: Float,
    minimum: Float,
    maximum: Float,
    cellFault: Boolean,
    themeColors: DashboardThemeColors,
    modifier: Modifier
) {
    val isMissing = voltage.isNaN()
    val warning = !isMissing && voltage <= AppConfig.warningCellVoltageV
    val imbalanceCell = !isMissing && cellFault && (voltage == minimum || voltage == maximum)
    val orange = warning || imbalanceCell

    Column(
        modifier
            .background(themeColors.raised, RoundedCornerShape(14.dp))
            .border(
                if (orange) 1.dp else 0.dp,
                if (orange) AlertColors.CellFault else themeColors.outline,
                RoundedCornerShape(14.dp)
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "$index",
            modifier = Modifier.fillMaxWidth(),
            color = themeColors.secondaryText,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.weight(1f))
        Text(
            if (isMissing) "–" else "%.2f".format(voltage),
            color = if (orange) AlertColors.CellFault else themeColors.primaryText,
            style = Tabular.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .background(if (orange) AlertColors.CellFault else themeColors.outline, RoundedCornerShape(99.dp))
        )
    }
}

@Composable private fun Metric(
    label: String,
    value: String,
    themeColors: DashboardThemeColors,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = themeColors.secondaryText, style = Tabular.copy(fontSize = 14.sp, letterSpacing = 1.2.sp))
        Text(value, color = themeColors.primaryText, style = Tabular.copy(fontSize = 36.sp, fontWeight = FontWeight.SemiBold))
    }
}

@Composable private fun Footer(
    label: String,
    value: String,
    themeColors: DashboardThemeColors,
    modifier: Modifier
) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(label, color = themeColors.secondaryText, fontSize = 12.sp)
    Text(value, color = themeColors.primaryText, style = Tabular.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold))
}

// ───────────────────────────── Previews ──────────────────────────────────────

@Preview(widthDp = 960, heightDp = 540) @Composable private fun PreviewSetup() = BatteryAlertTheme { BatteryDashboard(BatteryUiState(), sessionActive = false) }
@Preview(widthDp = 960, heightDp = 540) @Composable private fun PreviewNormal() = BatteryAlertTheme { BatteryDashboard(BatteryUiState(), sessionActive = true, isSimulator = true) }
@Preview @Composable private fun PreviewHero100() = BatteryAlertTheme { HeroRingReadout(100, true, null, null, dashboardThemeColors(null)) }
@Preview @Composable private fun PreviewHero99() = BatteryAlertTheme { HeroRingReadout(99, true, null, null, dashboardThemeColors(null)) }
@Preview @Composable private fun PreviewHero50() = BatteryAlertTheme { HeroRingReadout(50, true, null, null, dashboardThemeColors(null)) }
@Preview @Composable private fun PreviewHero9() = BatteryAlertTheme { HeroRingReadout(9, true, null, null, dashboardThemeColors(null)) }
@Preview @Composable private fun PreviewHero0() = BatteryAlertTheme { HeroRingReadout(0, true, null, null, dashboardThemeColors(null)) }
