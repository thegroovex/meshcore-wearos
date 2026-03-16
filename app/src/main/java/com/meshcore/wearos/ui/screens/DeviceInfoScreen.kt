package com.meshcore.wearos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.meshcore.wearos.ble.BleConnectionState
import com.meshcore.wearos.ui.MainViewModel
import com.meshcore.wearos.ui.theme.MeshGreen
import com.meshcore.wearos.ui.theme.MeshOrange

/**
 * DeviceInfoScreen displays detailed information about the connected MeshCore companion radio,
 * including the node name, public key, battery status, and storage usage.
 */
@Composable
fun DeviceInfoScreen(viewModel: MainViewModel) {
    val deviceInfo by viewModel.deviceInfo.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val batteryMillivolts by viewModel.batteryMillivolts.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.fetchBatteryStatus()
    }

    val connectedState = connectionState as? BleConnectionState.Connected

    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Text(
                text = "Device Info",
                style = MaterialTheme.typography.title3,
                color = MeshGreen,
                modifier = Modifier.padding(top = 24.dp, bottom = 4.dp)
            )
        }

        // Node Name
        item {
            InfoChip(
                label = "Name",
                value = deviceInfo?.name ?: connectedState?.deviceName ?: "Unknown"
            )
        }

        // Node Type
        item {
            InfoChip(
                label = "Type",
                value = deviceInfo?.nodeType?.displayName ?: "Unknown"
            )
        }

        // Public Key (abbreviated)
        if (deviceInfo != null) {
            item {
                InfoChip(
                    label = "Public Key",
                    value = deviceInfo!!.publicKeyHex.take(16) + "..."
                )
            }
        }

        // Battery
        if (batteryMillivolts > 0) {
            item {
                val batteryPercent = deviceInfo?.batteryPercent ?: 0
                val batteryColor = when {
                    batteryPercent > 50 -> MeshGreen
                    batteryPercent > 20 -> MeshOrange
                    else -> MaterialTheme.colors.error
                }
                Chip(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    onClick = { viewModel.fetchBatteryStatus() },
                    label = {
                        Text("Battery", style = MaterialTheme.typography.body2)
                    },
                    secondaryLabel = {
                        Text(
                            text = "$batteryPercent% (${batteryMillivolts}mV)",
                            style = MaterialTheme.typography.caption2,
                            color = batteryColor
                        )
                    },
                    colors = ChipDefaults.chipColors(backgroundColor = MaterialTheme.colors.surface)
                )
            }
        }

        // Storage
        if (deviceInfo != null && deviceInfo!!.totalStorage > 0) {
            item {
                InfoChip(
                    label = "Storage",
                    value = "${deviceInfo!!.usedStorage}/${deviceInfo!!.totalStorage} KB"
                )
            }
        }

        // BLE Address
        if (connectedState != null) {
            item {
                InfoChip(
                    label = "BLE Address",
                    value = connectedState.deviceAddress
                )
            }
        }
    }
}

@Composable
private fun InfoChip(label: String, value: String) {
    Chip(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        onClick = {},
        label = {
            Text(label, style = MaterialTheme.typography.body2)
        },
        secondaryLabel = {
            Text(
                text = value,
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Start
            )
        },
        colors = ChipDefaults.chipColors(backgroundColor = MaterialTheme.colors.surface)
    )
}
