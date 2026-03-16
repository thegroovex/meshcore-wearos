package com.meshcore.wearos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.meshcore.wearos.R
import com.meshcore.wearos.ble.BleConnectionState
import com.meshcore.wearos.ble.BleDevice
import com.meshcore.wearos.ui.MainViewModel
import com.meshcore.wearos.ui.theme.MeshGreen

/**
 * ScanScreen displays a list of nearby MeshCore BLE devices and allows the user
 * to initiate a connection. It automatically starts scanning when first displayed.
 */
@Composable
fun ScanScreen(
    viewModel: MainViewModel,
    onDeviceConnected: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val scannedDevices by viewModel.scannedDevices.collectAsState()

    // Auto-start scan when screen is shown
    LaunchedEffect(Unit) {
        viewModel.startScan()
    }

    // Navigate away when connected
    LaunchedEffect(connectionState) {
        if (connectionState is BleConnectionState.Connected) {
            onDeviceConnected()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
    ) {
        when (connectionState) {
            is BleConnectionState.Connecting -> {
                ConnectingContent(
                    deviceName = (connectionState as BleConnectionState.Connecting).deviceName
                )
            }
            is BleConnectionState.Error -> {
                ErrorContent(
                    message = (connectionState as BleConnectionState.Error).message,
                    onRetry = { viewModel.startScan() }
                )
            }
            else -> {
                DeviceListContent(
                    isScanning = connectionState is BleConnectionState.Scanning,
                    devices = scannedDevices,
                    onDeviceSelected = { device -> viewModel.connect(device.address) },
                    onScanToggle = {
                        if (connectionState is BleConnectionState.Scanning) {
                            viewModel.stopScan()
                        } else {
                            viewModel.startScan()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun DeviceListContent(
    isScanning: Boolean,
    devices: List<BleDevice>,
    onDeviceSelected: (BleDevice) -> Unit,
    onScanToggle: () -> Unit
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            ) {
                Text(
                    text = "MeshCore",
                    style = MaterialTheme.typography.title2,
                    color = MeshGreen
                )
                Text(
                    text = if (isScanning) "Scanning..." else "Tap to scan",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                )
            }
        }

        if (isScanning && devices.isEmpty()) {
            item {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(32.dp)
                        .padding(8.dp),
                    indicatorColor = MeshGreen
                )
            }
        }

        items(devices) { device ->
            DeviceChip(
                device = device,
                onClick = { onDeviceSelected(device) }
            )
        }

        item {
            Button(
                onClick = onScanToggle,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (isScanning) MaterialTheme.colors.error else MeshGreen
                )
            ) {
                Text(
                    text = if (isScanning) "Stop" else "Scan",
                    color = Color.Black
                )
            }
        }
    }
}

@Composable
private fun DeviceChip(
    device: BleDevice,
    onClick: () -> Unit
) {
    Chip(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        onClick = onClick,
        label = {
            Text(
                text = device.name,
                style = MaterialTheme.typography.body2,
                maxLines = 1
            )
        },
        secondaryLabel = {
            Text(
                text = "${device.address} · ${device.rssi} dBm",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                maxLines = 1
            )
        },
        colors = ChipDefaults.chipColors(
            backgroundColor = MaterialTheme.colors.surface
        )
    )
}

@Composable
private fun ConnectingContent(deviceName: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            indicatorColor = MeshGreen
        )
        Text(
            text = "Connecting to",
            style = MaterialTheme.typography.caption2,
            modifier = Modifier.padding(top = 12.dp)
        )
        Text(
            text = deviceName,
            style = MaterialTheme.typography.body2,
            color = MeshGreen,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Connection Error",
            style = MaterialTheme.typography.title3,
            color = MaterialTheme.colors.error
        )
        Text(
            text = message,
            style = MaterialTheme.typography.caption2,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(8.dp)
        )
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(backgroundColor = MeshGreen)
        ) {
            Text("Retry", color = Color.Black)
        }
    }
}
