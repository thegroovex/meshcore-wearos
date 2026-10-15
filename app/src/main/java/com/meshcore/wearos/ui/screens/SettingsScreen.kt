package com.meshcore.wearos.ui.screens

import android.app.Activity
import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.input.RemoteInputIntentHelper
import com.meshcore.wearos.BuildConfig
import com.meshcore.wearos.ble.BleConnectionState
import com.meshcore.wearos.ui.MainViewModel

private const val PIN_KEY = "meshcore_pin"

@Composable
fun SettingsScreen(viewModel: MainViewModel, onForgetDevice: () -> Unit) {
    val state by viewModel.connectionState.collectAsState()
    val sessionReady by viewModel.sessionReady.collectAsState()
    val pin by viewModel.pairingPin.collectAsState()
    val listState = rememberScalingLazyListState()
    val connected = state is BleConnectionState.Connected

    val pinLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { RemoteInput.getResultsFromIntent(it) }
                ?.getCharSequence(PIN_KEY)?.toString()?.let { viewModel.setPairingPin(it) }
        }
    }

    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item { ScreenTitle("Settings") }
            item {
                SettingChip("Flood advert", "Announce across repeaters", enabled = sessionReady) {
                    viewModel.sendSelfAdvert(flood = true)
                }
            }
            item {
                SettingChip("Radio BLE PIN", pin) {
                    val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                    RemoteInputIntentHelper.putRemoteInputsExtra(
                        intent,
                        listOf(
                            RemoteInput.Builder(PIN_KEY)
                                .setLabel("6-digit PIN")
                                .build()
                        )
                    )
                    pinLauncher.launch(intent)
                }
            }
            item {
                if (connected) SettingChip("Disconnect", null) { viewModel.disconnect() }
                else SettingChip("Reconnect", viewModel.savedDeviceName) { viewModel.reconnect() }
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    onClick = onForgetDevice,
                    label = { Text("Forget radio") },
                    secondaryLabel = { Text("Choose a different device", style = MaterialTheme.typography.caption2) },
                    colors = ChipDefaults.chipColors(backgroundColor = MaterialTheme.colors.error.copy(alpha = 0.35f))
                )
            }
            item {
                Text(
                    "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingChip(label: String, secondary: String?, enabled: Boolean = true, onClick: () -> Unit) {
    Chip(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        secondaryLabel = secondary?.let { { Text(it, style = MaterialTheme.typography.caption2) } },
        colors = ChipDefaults.chipColors(backgroundColor = MaterialTheme.colors.surface)
    )
}
