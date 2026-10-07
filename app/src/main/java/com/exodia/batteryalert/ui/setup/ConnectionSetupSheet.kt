package com.exodia.batteryalert.ui.setup

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.exodia.batteryalert.core.config.RealConnectionConfig
import com.exodia.batteryalert.core.config.TransportConfig
import com.exodia.batteryalert.ui.theme.AlertColors
import com.exodia.batteryalert.ui.theme.dashboardThemeColors

/**
 * Bottom-sheet version of connection setup — reuses shared [ConnectionSetupContent].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ConnectionSetupSheet(
    onDismiss: () -> Unit,
    onStartReal: (RealConnectionConfig) -> Unit,
    onStartSimulator: () -> Unit,
) {
    val themeColors = dashboardThemeColors(null)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AlertColors.Surface,
        contentColor = AlertColors.Primary,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        ConnectionSetupContent(
            themeColors = themeColors,
            onStartReal = { config ->
                onStartReal(config)
                onDismiss()
            },
            onStartSimulator = {
                onStartSimulator()
                onDismiss()
            }
        )
    }
}
