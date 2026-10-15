package com.meshcore.wearos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.meshcore.wearos.ui.MainViewModel
import com.meshcore.wearos.ui.theme.MeshGreen

@Composable
fun DeviceInfoScreen(viewModel: MainViewModel) {
    val info by viewModel.deviceInfo.collectAsState()
    val state by viewModel.connectionState.collectAsState()
    val listState = rememberScalingLazyListState()

    LaunchedEffect(Unit) { viewModel.fetchBatteryStatus() }

    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { ScreenTitle("Device") }
            item { InfoRow("Status", state.label()) }
            val d = info
            if (d == null) {
                item { InfoRow("Radio", "No session") }
            } else {
                item { InfoRow("Name", d.name) }
                item { InfoRow("Public key", d.publicKeyHex.take(16) + "…") }
                if (d.batteryMillivolts > 0) item {
                    InfoRow("Battery", "${d.batteryPercent}% (${"%.2f".format(d.batteryMillivolts / 1000f)} V)")
                }
                if (d.totalStorage > 0) item { InfoRow("Storage", "${d.usedStorage} / ${d.totalStorage} KB") }
                if (d.radioSummary.isNotBlank()) item { InfoRow("Radio", d.radioSummary) }
                if (d.txPowerDbm != 0) item { InfoRow("TX power", "${d.txPowerDbm} dBm") }
                if (d.firmwareVersion.isNotBlank()) item { InfoRow("Firmware", "${d.firmwareVersion} (${d.firmwareBuildDate})") }
                if (d.manufacturer.isNotBlank()) item { InfoRow("Hardware", d.manufacturer) }
                if (d.maxContacts > 0) item { InfoRow("Limits", "${d.maxContacts} contacts · ${d.maxChannels} channels") }
            }
            item {
                CompactChip(
                    onClick = { viewModel.fetchBatteryStatus() },
                    label = { Text("Refresh") },
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, style = MaterialTheme.typography.caption3, color = MeshGreen)
        Text(value, style = MaterialTheme.typography.caption1, color = MaterialTheme.colors.onSurface)
    }
}
