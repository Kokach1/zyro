package com.exodia.batteryalert.ui.debug

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exodia.batteryalert.core.config.BatteryProfiles
import com.exodia.batteryalert.core.transport.SimulatorScenario
import com.exodia.batteryalert.ui.monitor.BatteryUiState
import com.exodia.batteryalert.ui.theme.AlertColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SimulatorControlSheet(state: BatteryUiState, close: () -> Unit, scenario: (SimulatorScenario) -> Unit, speed: (Int) -> Unit, pause: (Boolean) -> Unit, reset: () -> Unit, profile: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = close, containerColor = AlertColors.Surface, contentColor = AlertColors.Primary, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.padding(24.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Simulator", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            SheetRow("Scenario") { SimulatorScenario.entries.forEach { Choice(it.label) { scenario(it) } } }
            SheetRow("Speed") { listOf(1, 5, 20).forEach { Choice("x$it") { speed(it) } } }
            SheetRow("Battery") { BatteryProfiles.all.forEach { Choice(it.label) { profile(it.id) } } }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Button(onClick = { pause(!state.isPaused) }, colors = ButtonDefaults.buttonColors(containerColor = AlertColors.Raised)) { Text(if (state.isPaused) "Resume" else "Pause") }; Button(onClick = reset, colors = ButtonDefaults.buttonColors(containerColor = AlertColors.Raised)) { Text("Reset") } }
            Spacer(Modifier.height(20.dp))
        }
    }
}
@Composable private fun SheetRow(label: String, content: @Composable RowScope.() -> Unit) { Column { Text(label, color = AlertColors.Secondary); Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content) } }
@Composable private fun Choice(label: String, select: () -> Unit) { AssistChip(onClick = select, label = { Text(label) }, colors = AssistChipDefaults.assistChipColors(containerColor = AlertColors.Raised, labelColor = AlertColors.Primary), border = AssistChipDefaults.assistChipBorder(borderColor = AlertColors.Outline, enabled = true)) }
