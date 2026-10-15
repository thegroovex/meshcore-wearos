package com.meshcore.wearos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.meshcore.wearos.ble.BleConnectionState
import com.meshcore.wearos.ble.BleDevice
import com.meshcore.wearos.ui.MainViewModel
import com.meshcore.wearos.ui.theme.MeshGreen

/** Lists nearby MeshCore radios. Permissions are requested by the activity, once. */
@Composable
fun ScanScreen(
    viewModel: MainViewModel,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onDeviceConnected: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val devices by viewModel.scannedDevices.collectAsState()
    val listState = rememberScalingLazyListState()

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted && connectionState !is BleConnectionState.Connected &&
            connectionState !is BleConnectionState.Connecting && connectionState !is BleConnectionState.Pairing
        ) viewModel.startScan()
    }
    LaunchedEffect(connectionState) {
        if (connectionState is BleConnectionState.Connected) onDeviceConnected()
    }

    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        when (val state = connectionState) {
            is BleConnectionState.Connecting -> Busy("Connecting to", state.deviceName)
            is BleConnectionState.Pairing -> Busy("Pairing with ${state.deviceName}", "Enter the radio's BLE PIN (default 123456)")
            else -> ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item { ScreenTitle("MeshCore") }
                item {
                    Text(
                        text = when {
                            !permissionsGranted -> "Bluetooth permission needed"
                            state is BleConnectionState.Error -> state.message
                            state is BleConnectionState.Scanning -> "Searching radios…"
                            devices.isEmpty() -> "No radio found"
                            else -> "Choose your radio"
                        },
                        style = MaterialTheme.typography.caption2,
                        color = if (state is BleConnectionState.Error) MaterialTheme.colors.error
                                else MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                if (state is BleConnectionState.Scanning && devices.isEmpty()) {
                    item { CircularProgressIndicator(modifier = Modifier.size(28.dp), indicatorColor = MeshGreen) }
                }
                items(devices, key = { it.address }) { device ->
                    DeviceChip(device) { viewModel.connect(device.address) }
                }
                item {
                    val scanning = state is BleConnectionState.Scanning
                    Chip(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        onClick = {
                            when {
                                !permissionsGranted -> onRequestPermissions()
                                scanning -> viewModel.stopScan()
                                else -> viewModel.startScan()
                            }
                        },
                        label = {
                            Text(
                                when {
                                    !permissionsGranted -> "Grant permission"
                                    scanning -> "Stop"
                                    else -> "Scan again"
                                },
                                color = Color.Black
                            )
                        },
                        colors = ChipDefaults.primaryChipColors(backgroundColor = MeshGreen)
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceChip(device: BleDevice, onClick: () -> Unit) {
    Chip(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        onClick = onClick,
        label = { Text(device.name, maxLines = 1) },
        secondaryLabel = {
            Text(
                "${device.rssi} dBm · ${device.address.takeLast(5)}",
                style = MaterialTheme.typography.caption2,
                maxLines = 1
            )
        },
        colors = ChipDefaults.secondaryChipColors()
    )
}

@Composable
private fun Busy(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(40.dp), indicatorColor = MeshGreen)
        Text(title, style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp))
        Text(subtitle, style = MaterialTheme.typography.caption2, color = MeshGreen, textAlign = TextAlign.Center)
    }
}
