package com.exodia.batteryalert.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exodia.batteryalert.core.config.TransportConfig
import com.exodia.batteryalert.ui.theme.AlertColors
import com.exodia.batteryalert.ui.theme.Tabular

/**
 * Bottom-sheet version of connection setup — reachable from ⋮ while not in session.
 * Full config options: source selection, endpoint, profile, audio, logging, interlock.
 * (Step 14 will expand to full configuration sections.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ConnectionSetupSheet(
    onDismiss: () -> Unit,
    onStartReal: (TransportConfig) -> Unit,
    onStartSimulator: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AlertColors.Surface,
        contentColor = AlertColors.Primary,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(Modifier.padding(24.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connection / Setup", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Select source and press Start Monitoring. Simulator is for demo only.",
                color = AlertColors.Secondary, style = Tabular.copy(fontSize = 13.sp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { onStartReal(TransportConfig()); onDismiss() },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertColors.Accent)
                ) { Text("Start Monitoring") }
                OutlinedButton(
                    onClick = { onStartSimulator(); onDismiss() },
                    border = androidx.compose.foundation.BorderStroke(1.dp, AlertColors.Outline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AlertColors.Secondary)
                ) { Text("▶ Simulator") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
