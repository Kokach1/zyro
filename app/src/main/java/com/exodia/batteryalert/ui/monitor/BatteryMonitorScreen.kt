package com.exodia.batteryalert.ui.monitor

import androidx.compose.animation.animateColorAsState
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
import com.exodia.batteryalert.core.model.*
import com.exodia.batteryalert.core.transport.SimulatorScenario
import com.exodia.batteryalert.ui.debug.SimulatorControlSheet
import com.exodia.batteryalert.ui.theme.*
import kotlinx.coroutines.flow.StateFlow

@Composable fun BatteryMonitorScreen(viewModel: BatteryMonitorViewModel) { val state by viewModel.uiState.collectAsState(); BatteryDashboard(state, viewModel::scenario, viewModel::speed, viewModel::pause, viewModel::reset, viewModel::profile) }

@Composable fun BatteryDashboard(state: BatteryUiState, scenario: (SimulatorScenario) -> Unit = {}, speed: (Int) -> Unit = {}, pause: (Boolean) -> Unit = {}, reset: () -> Unit = {}, profile: (String) -> Unit = {}) {
    var sheet by remember { mutableStateOf(false) }; val emergency = state.alert?.level == AlertLevel.EMERGENCY
    val background by animateColorAsState(if (emergency) AlertColors.Emergency else AlertColors.Background, tween(500), label = "emergency")
    Box(Modifier.fillMaxSize().background(background)) {
        Column(Modifier.fillMaxSize()) {
            StatusBar(state, { sheet = true })
            state.alert?.takeIf { it.level in setOf(AlertLevel.NOTICE, AlertLevel.WARNING) }?.let { AlertBanner(it) }
            Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Hero(state, Modifier.weight(.46f).fillMaxHeight())
                CellPanel(state, Modifier.weight(.54f).fillMaxHeight())
            }
        }
        when (state.alert?.level) { AlertLevel.CRITICAL -> CriticalModal(state); AlertLevel.EMERGENCY -> EmergencyModal(state); else -> Unit }
    }
    if (sheet) SimulatorControlSheet(state, { sheet = false }, scenario, speed, pause, reset, profile)
}

@Composable private fun StatusBar(state: BatteryUiState, openSimulator: () -> Unit) {
    val connection = when (state.connection) { is ConnectionState.Connecting -> "Connecting"; is ConnectionState.LinkLost -> "Link lost"; is ConnectionState.Error -> "Error"; else -> "Simulated" }
    Row(Modifier.fillMaxWidth().height(56.dp).background(AlertColors.Background.copy(alpha = .72f)).border(1.dp, AlertColors.Outline).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("○  $connection", color = if (connection == "Link lost") AlertColors.LinkLost else AlertColors.Secondary, style = Tabular.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
        state.analysis?.takeIf { it.flightElapsedSec > 0 }?.let { Text("◷  ${formatFlightTime(it.flightElapsedSec)}", color = AlertColors.Primary, style = Tabular.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)) }
        Spacer(Modifier.weight(1f))
        state.distanceM?.let { Text("⌂  ${formatDistance(it)}", color = AlertColors.Primary, style = Tabular.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)) }
        state.rtl?.returnEtaSec?.let { Text("↩  ${formatSeconds(it)}", color = if (state.rtl.returnEtaExceedsTimeLeft) AlertColors.Warning else AlertColors.Primary, style = Tabular.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)) }
        Box(Modifier.size(48.dp).clickable { openSimulator() }, contentAlignment = Alignment.Center) { Text("⋮", color = AlertColors.Secondary, fontSize = 26.sp) }
    }
}

@Composable private fun Hero(state: BatteryUiState, modifier: Modifier) {
    val analysis = state.analysis; val percent = analysis?.remainingPercent ?: 0; val level = state.alert?.level ?: AlertLevel.NONE
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        HeroRingReadout(percent, analysis != null, state.rtl?.requiredPercent, level, state.connection is ConnectionState.LinkLost)
        Spacer(Modifier.height(18.dp)); Row { Metric("TIME LEFT", analysis?.minutesRemaining?.let { "${it.toInt()} min" } ?: "–", Modifier.weight(1f)); Box(Modifier.width(1.dp).height(58.dp).background(AlertColors.Outline)); Metric("RETURN NEEDS", state.rtl?.requiredPercent?.let { "${kotlin.math.ceil(it).toInt()}%" } ?: "–", Modifier.weight(1f)) }
    }
}

@Composable private fun HeroRingReadout(percent: Int, hasData: Boolean, required: Float?, level: AlertLevel, stale: Boolean) {
    val ringDiameter = 420.dp
    val fontSize = HeroNumberLayout.fontSizeSp(ringDiameter.value, LocalDensity.current.density).sp
    Box(Modifier.size(ringDiameter), contentAlignment = Alignment.Center) {
        BatteryRing(percent, required, level, stale)
        required?.let { ReturnTickLabel(it, ringDiameter) }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text(if (hasData) "$percent" else "–", color = if (level >= AlertLevel.WARNING) colorFor(level) else AlertColors.Primary, style = Tabular.copy(fontSize = fontSize, lineHeight = fontSize, fontWeight = FontWeight.SemiBold))
                Text("%", color = AlertColors.Secondary, style = Tabular.copy(fontSize = (fontSize.value * .30f).sp, fontWeight = FontWeight.Medium))
            }
            Text("REMAINING", color = AlertColors.Secondary, style = Tabular.copy(fontSize = 14.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium))
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

@Composable private fun BatteryRing(percent: Int, required: Float?, level: AlertLevel, stale: Boolean) {
    val sweep by animateFloatAsState(percent / 100f * 270f, tween(400), label = "ring")
    Canvas(Modifier.fillMaxSize()) { val stroke = HeroNumberLayout.ringStrokeDp.dp.toPx(); val zoneStroke = 6.dp.toPx(); val mainSize = size.minDimension - stroke; val mainTop = Offset((this.size.width - mainSize) / 2, (this.size.height - mainSize) / 2); val zoneSize = mainSize + stroke + zoneStroke * 2; val zoneTop = Offset((this.size.width - zoneSize) / 2, (this.size.height - zoneSize) / 2)
        drawArc(AlertColors.Outline, RingGeometry.startAngle, RingGeometry.totalSweep, false, mainTop, Size(mainSize, mainSize), style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(AlertColors.Critical.copy(.35f), RingGeometry.angleFor(0f), RingGeometry.fillSweep(AppConfig.warningPercent.toFloat()), false, zoneTop, Size(zoneSize, zoneSize), style = Stroke(zoneStroke, cap = StrokeCap.Butt))
        if (required != null && required > AppConfig.warningPercent) drawArc(AlertColors.Notice.copy(.35f), RingGeometry.angleFor(AppConfig.warningPercent.toFloat()), RingGeometry.fillSweep(required - AppConfig.warningPercent), false, zoneTop, Size(zoneSize, zoneSize), style = Stroke(zoneStroke, cap = StrokeCap.Butt))
        if (level == AlertLevel.NONE && !stale) drawArc(AlertColors.Normal, RingGeometry.startAngle, sweep, false, mainTop, Size(mainSize, mainSize), style = Stroke(stroke, cap = StrokeCap.Round))
        if (required != null) { val radians = Math.toRadians(RingGeometry.angleFor(required).toDouble()); val center = Offset(this.size.width / 2, this.size.height / 2); val radius = mainSize / 2 + stroke / 2; val outer = Offset(center.x + kotlin.math.cos(radians).toFloat() * (radius + 5.dp.toPx()), center.y + kotlin.math.sin(radians).toFloat() * (radius + 5.dp.toPx())); val inner = Offset(center.x + kotlin.math.cos(radians).toFloat() * (radius - 5.dp.toPx()), center.y + kotlin.math.sin(radians).toFloat() * (radius - 5.dp.toPx())); drawLine(Color.White, inner, outer, 3.dp.toPx()) }
    }
}
@Composable private fun CellPanel(state: BatteryUiState, modifier: Modifier) { val a = state.analysis
    Column(modifier.background(AlertColors.Surface, RoundedCornerShape(20.dp)).border(1.dp, AlertColors.Outline, RoundedCornerShape(20.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("CELLS", color = AlertColors.Secondary, style = Tabular.copy(fontSize = 14.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium)); Spacer(Modifier.width(10.dp)); Text("${a?.cellCount ?: "–"}S", color = AlertColors.Primary, style = Tabular.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)); Spacer(Modifier.weight(1f)); Text("Avg ${a?.avgCellV?.let { "%.2f V".format(it) } ?: "–"}", color = AlertColors.Secondary); Spacer(Modifier.width(18.dp)); Text("Delta ${a?.cellDeltaV?.let { "%.2f V".format(it) } ?: "–"}", color = if (state.cellFault) AlertColors.CellFault else AlertColors.Secondary) }
        val columns = when (a?.cellCount) { 6 -> 3; 12 -> 6; else -> 7 }
        a?.cellVoltagesV?.chunked(columns)?.forEachIndexed { row, cells -> Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) { cells.forEachIndexed { col, voltage -> CellTile(row * columns + col + 1, voltage, a.minCellV, a.maxCellV, state.cellFault, Modifier.weight(1f).fillMaxHeight()) } } }
        Spacer(Modifier.weight(1f)); Row(Modifier.fillMaxWidth()) { Footer("PACK", a?.packVoltageV?.let { "%.1f V".format(it) } ?: "–", Modifier.weight(1f)); Footer("CURRENT", a?.currentA?.let { "%.0f A".format(it) } ?: "–", Modifier.weight(1f)); Footer("TEMP", a?.temperatureC?.let { "%.0f C".format(it) } ?: "–", Modifier.weight(1f)) }
    }
}
@Composable private fun CellTile(index: Int, voltage: Float, minimum: Float, maximum: Float, cellFault: Boolean, modifier: Modifier) { val warning = voltage <= AppConfig.warningCellVoltageV; val imbalanceCell = cellFault && (voltage == minimum || voltage == maximum); val orange = warning || imbalanceCell; Column(modifier.background(AlertColors.Raised, RoundedCornerShape(14.dp)).border(if (orange) 1.dp else if (voltage == minimum) 1.dp else 0.dp, if (orange) AlertColors.CellFault else AlertColors.Secondary, RoundedCornerShape(14.dp)).padding(8.dp)) { Text("$index", color = AlertColors.Secondary, fontSize = 12.sp); Spacer(Modifier.weight(1f)); Text("%.2f".format(voltage), color = if (orange) AlertColors.CellFault else AlertColors.Primary, style = Tabular.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)); Box(Modifier.fillMaxWidth().height(5.dp).background(if (orange) AlertColors.CellFault else AlertColors.Secondary, RoundedCornerShape(99.dp))) } }
@Composable private fun Metric(label: String, value: String, modifier: Modifier = Modifier) { Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) { Text(label, color = AlertColors.Secondary, style = Tabular.copy(fontSize = 14.sp, letterSpacing = 1.2.sp)); Text(value, color = AlertColors.Primary, style = Tabular.copy(fontSize = 36.sp, fontWeight = FontWeight.SemiBold)) } }
@Composable private fun Footer(label: String, value: String, modifier: Modifier) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) { Text(label, color = AlertColors.Secondary, fontSize = 12.sp); Text(value, color = AlertColors.Primary, style = Tabular.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold)) }
@Composable private fun AlertBanner(alert: ActiveAlert) { Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) { Row(Modifier.widthIn(max = 760.dp).heightIn(min = 64.dp).background(colorFor(alert.level), RoundedCornerShape(14.dp)).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(alert.title.uppercase(), color = AlertColors.Background, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text(alert.message, color = AlertColors.Background, fontSize = 18.sp) }; if (alert.dismissible) Text("Got it", color = AlertColors.Background, fontWeight = FontWeight.Bold) } } }
@Composable private fun CriticalModal(state: BatteryUiState) { Box(Modifier.fillMaxSize().background(AlertColors.Critical.copy(.96f)), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("RETURN NOW", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Bold); Text("Mandatory RTL recommended!", color = Color.White, fontSize = 28.sp); Spacer(Modifier.height(28.dp)); Text("REMAINING ${state.analysis?.remainingPercent ?: "–"}%    NEEDED ${state.rtl?.requiredPercent?.toInt() ?: "–"}%", color = Color.White, style = Tabular.copy(fontSize = 32.sp, fontWeight = FontWeight.SemiBold)) } } }
@Composable private fun EmergencyModal(state: BatteryUiState) { Box(Modifier.fillMaxSize().background(AlertColors.Emergency.copy(.96f)), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("LAND NOW", color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Bold); Text("Land immediately", color = Color.White, fontSize = 28.sp); Text(state.analysis?.minCellV?.let { "%.2f V".format(it) } ?: "–", color = AlertColors.Emergency, modifier = Modifier.padding(20.dp).background(Color.White, RoundedCornerShape(99.dp)).padding(horizontal = 24.dp, vertical = 8.dp), fontSize = 32.sp) } } }
private fun colorFor(level: AlertLevel) = when (level) { AlertLevel.NOTICE -> AlertColors.Notice; AlertLevel.WARNING -> AlertColors.Warning; AlertLevel.CRITICAL -> AlertColors.Critical; AlertLevel.EMERGENCY -> AlertColors.Emergency; else -> AlertColors.Normal }
@Preview(widthDp = 960, heightDp = 540) @Composable private fun PreviewNormal() = BatteryAlertTheme { BatteryDashboard(BatteryUiState()) }
@Preview @Composable private fun PreviewHero100() = BatteryAlertTheme { HeroRingReadout(100, true, null, AlertLevel.NONE, false) }
@Preview @Composable private fun PreviewHero99() = BatteryAlertTheme { HeroRingReadout(99, true, null, AlertLevel.NONE, false) }
@Preview @Composable private fun PreviewHero50() = BatteryAlertTheme { HeroRingReadout(50, true, null, AlertLevel.NONE, false) }
@Preview @Composable private fun PreviewHero9() = BatteryAlertTheme { HeroRingReadout(9, true, null, AlertLevel.NONE, false) }
@Preview @Composable private fun PreviewHero0() = BatteryAlertTheme { HeroRingReadout(0, true, null, AlertLevel.NONE, false) }
