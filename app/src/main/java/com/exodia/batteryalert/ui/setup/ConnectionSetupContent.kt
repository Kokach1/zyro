package com.exodia.batteryalert.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exodia.batteryalert.core.config.RealConnectionConfig
import com.exodia.batteryalert.core.config.RealTransportKind
import com.exodia.batteryalert.ui.theme.AlertColors
import com.exodia.batteryalert.ui.theme.DashboardThemeColors
import com.exodia.batteryalert.ui.theme.Tabular

@Composable
fun ConnectionSetupContent(
    themeColors: DashboardThemeColors,
    errorMessage: String? = null,
    onStartReal: (RealConnectionConfig) -> Unit,
    onStartSimulator: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedKind by remember { mutableStateOf(RealTransportKind.UDP) }

    // UDP inputs
    var udpAddress by remember { mutableStateOf("0.0.0.0") }
    var udpPortText by remember { mutableStateOf("14550") }

    // USB inputs
    var usbBaudText by remember { mutableStateOf("57600") }

    // Internal Serial inputs
    var internalPath by remember { mutableStateOf("") }
    var internalBaudText by remember { mutableStateOf("921600") }

    // Replay inputs
    var replayPath by remember { mutableStateOf("") }

    // Validation
    val udpPort = udpPortText.toIntOrNull()
    val usbBaud = usbBaudText.toIntOrNull()
    val internalBaud = internalBaudText.toIntOrNull()

    val validationError: String? = when (selectedKind) {
        RealTransportKind.UDP -> {
            if (udpPort == null || udpPort !in 1..65535) "Invalid UDP port (must be 1-65535)"
            else if (udpAddress.isBlank()) "Bind address cannot be blank"
            else null
        }
        RealTransportKind.USB_SERIAL -> {
            if (usbBaud == null || usbBaud <= 0) "Invalid USB baud rate"
            else null
        }
        RealTransportKind.INTERNAL_SERIAL -> {
            if (internalPath.isBlank()) "Enter device node path (e.g. /dev/ttyS1)"
            else if (internalBaud == null || internalBaud <= 0) "Invalid serial baud rate"
            else null
        }
        RealTransportKind.REPLAY -> {
            if (replayPath.isBlank()) "Enter or select replay file path"
            else null
        }
    }

    val isValid = validationError == null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "Connect to Drone",
            color = themeColors.primaryText,
            style = Tabular.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold)
        )
        Text(
            "Select telemetry source to begin monitoring.",
            color = themeColors.secondaryText,
            style = Tabular.copy(fontSize = 13.sp)
        )

        // Source selector buttons
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                RealTransportKind.UDP to "UDP",
                RealTransportKind.USB_SERIAL to "USB",
                RealTransportKind.INTERNAL_SERIAL to "Internal",
                RealTransportKind.REPLAY to "Replay",
            ).forEach { (kind, label) ->
                val selected = selectedKind == kind
                Box(
                    Modifier
                        .background(
                            if (selected) themeColors.accent.copy(alpha = 0.18f) else themeColors.surface,
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            1.dp,
                            if (selected) themeColors.accent else themeColors.outline,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { selectedKind = kind }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (selected) themeColors.accent else themeColors.secondaryText,
                        style = Tabular.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }

        // Source-specific editable inputs
        when (selectedKind) {
            RealTransportKind.UDP -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth(0.7f)) {
                    OutlinedTextField(
                        value = udpAddress,
                        onValueChange = { udpAddress = it },
                        label = { Text("Bind Address") },
                        modifier = Modifier.weight(0.6f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = themeColors.accent,
                            unfocusedBorderColor = themeColors.outline,
                            focusedTextColor = themeColors.primaryText,
                            unfocusedTextColor = themeColors.primaryText
                        )
                    )
                    OutlinedTextField(
                        value = udpPortText,
                        onValueChange = { udpPortText = it },
                        label = { Text("Port") },
                        modifier = Modifier.weight(0.4f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = themeColors.accent,
                            unfocusedBorderColor = themeColors.outline,
                            focusedTextColor = themeColors.primaryText,
                            unfocusedTextColor = themeColors.primaryText
                        )
                    )
                }
                Text(
                    "Listening on $udpAddress:${udpPort ?: ""}. Direct MAVLink stream to this device IP.",
                    color = AlertColors.Disabled,
                    style = Tabular.copy(fontSize = 11.sp),
                    textAlign = TextAlign.Center
                )
            }
            RealTransportKind.USB_SERIAL -> {
                OutlinedTextField(
                    value = usbBaudText,
                    onValueChange = { usbBaudText = it },
                    label = { Text("Baud Rate") },
                    modifier = Modifier.fillMaxWidth(0.5f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = themeColors.accent,
                        unfocusedBorderColor = themeColors.outline,
                        focusedTextColor = themeColors.primaryText,
                        unfocusedTextColor = themeColors.primaryText
                    )
                )
                Text(
                    "Connect USB OTG cable. App will enumerate compatible serial adapters.",
                    color = AlertColors.Disabled,
                    style = Tabular.copy(fontSize = 11.sp),
                    textAlign = TextAlign.Center
                )
            }
            RealTransportKind.INTERNAL_SERIAL -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth(0.7f)) {
                    OutlinedTextField(
                        value = internalPath,
                        onValueChange = { internalPath = it },
                        label = { Text("Device Path (e.g. /dev/ttyS1)") },
                        modifier = Modifier.weight(0.65f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = themeColors.accent,
                            unfocusedBorderColor = themeColors.outline,
                            focusedTextColor = themeColors.primaryText,
                            unfocusedTextColor = themeColors.primaryText
                        )
                    )
                    OutlinedTextField(
                        value = internalBaudText,
                        onValueChange = { internalBaudText = it },
                        label = { Text("Baud") },
                        modifier = Modifier.weight(0.35f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = themeColors.accent,
                            unfocusedBorderColor = themeColors.outline,
                            focusedTextColor = themeColors.primaryText,
                            unfocusedTextColor = themeColors.primaryText
                        )
                    )
                }
                Text(
                    "Enter the internal serial device node and baud rate.",
                    color = AlertColors.Disabled,
                    style = Tabular.copy(fontSize = 11.sp),
                    textAlign = TextAlign.Center
                )
            }
            RealTransportKind.REPLAY -> {
                OutlinedTextField(
                    value = replayPath,
                    onValueChange = { replayPath = it },
                    label = { Text("Replay File Path (.jsonl / .tlog)") },
                    modifier = Modifier.fillMaxWidth(0.7f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = themeColors.accent,
                        unfocusedBorderColor = themeColors.outline,
                        focusedTextColor = themeColors.primaryText,
                        unfocusedTextColor = themeColors.primaryText
                    )
                )
                Text(
                    "Replay telemetry from a previously recorded flight. No hardware commands issued.",
                    color = AlertColors.Disabled,
                    style = Tabular.copy(fontSize = 11.sp),
                    textAlign = TextAlign.Center
                )
            }
        }

        // Validation / operational error message
        val displayError = errorMessage ?: validationError
        if (displayError != null) {
            Text(
                displayError,
                color = AlertColors.Warning,
                style = Tabular.copy(fontSize = 12.sp),
                textAlign = TextAlign.Center
            )
        }

        // Start Monitoring button — disabled if invalid
        Button(
            onClick = {
                if (!isValid) return@Button
                val config: RealConnectionConfig = when (selectedKind) {
                    RealTransportKind.UDP -> RealConnectionConfig.Udp(
                        bindAddress = udpAddress,
                        bindPort = udpPort ?: 14550,
                    )
                    RealTransportKind.USB_SERIAL -> RealConnectionConfig.UsbSerial(
                        baudRate = usbBaud ?: 57600,
                    )
                    RealTransportKind.INTERNAL_SERIAL -> RealConnectionConfig.InternalSerial(
                        devicePath = internalPath,
                        baudRate = internalBaud ?: 921600,
                    )
                    RealTransportKind.REPLAY -> RealConnectionConfig.Replay(
                        filePath = replayPath,
                    )
                }
                onStartReal(config)
            },
            enabled = isValid,
            modifier = Modifier.fillMaxWidth(0.5f).defaultMinSize(minHeight = 48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = themeColors.accent,
                disabledContainerColor = themeColors.raised
            )
        ) {
            Text(
                "Start Monitoring",
                color = if (isValid) Color.White else themeColors.secondaryText,
                style = Tabular.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold)
            )
        }

        // Centered plain Simulator button
        Box(
            Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            OutlinedButton(
                onClick = onStartSimulator,
                modifier = Modifier.defaultMinSize(minWidth = 140.dp, minHeight = 48.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, themeColors.outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = themeColors.secondaryText)
            ) {
                Text(
                    "Simulator",
                    color = themeColors.secondaryText,
                    style = Tabular.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium)
                )
            }
        }
    }
}
