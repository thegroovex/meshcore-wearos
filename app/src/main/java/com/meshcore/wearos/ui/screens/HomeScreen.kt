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
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.meshcore.wearos.ble.BleConnectionState
import com.meshcore.wearos.ui.MainViewModel
import com.meshcore.wearos.ui.theme.MeshGreen
import com.meshcore.wearos.ui.theme.MeshOrange

/**
 * Main hub. Stays visible while the link drops and reconnects (the old version jumped back
 * to the scan screen on every disconnect, fighting the auto-reconnect).
 */
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToContacts: () -> Unit,
    onNavigateToChannels: () -> Unit,
    onNavigateToDeviceInfo: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onChooseDevice: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val deviceInfo by viewModel.deviceInfo.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val channels by viewModel.channels.collectAsState()
    val isSyncing by viewModel.isSyncingContacts.collectAsState()
    val sessionReady by viewModel.sessionReady.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val listState = rememberScalingLazyListState()

    val connected = connectionState is BleConnectionState.Connected
    val contactUnread = contacts.sumOf { it.unreadCount }
    val channelUnread = channels.sumOf { it.unreadCount }

    LaunchedEffect(sessionReady) {
        if (sessionReady) viewModel.fetchBatteryStatus()
    }

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 16.dp)) {
                    Text(
                        text = deviceInfo?.name ?: (connectionState as? BleConnectionState.Connected)?.deviceName
                            ?: viewModel.savedDeviceName ?: "MeshCore",
                        style = MaterialTheme.typography.title3,
                        color = MeshGreen,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                    val battery = deviceInfo?.takeIf { it.batteryMillivolts > 0 }
                    Text(
                        text = when {
                            !connected -> connectionState.label()
                            !sessionReady -> "Starting session…"
                            battery != null -> "Battery ${battery.batteryPercent}% · ${"%.2f".format(battery.batteryMillivolts / 1000f)} V"
                            else -> "Connected"
                        },
                        style = MaterialTheme.typography.caption2,
                        color = when {
                            connectionState is BleConnectionState.Error -> MaterialTheme.colors.error
                            !connected -> MeshOrange
                            battery != null && battery.batteryPercent < 20 -> MaterialTheme.colors.error
                            else -> MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                        }
                    )
                    lastError?.let {
                        Text(it, style = MaterialTheme.typography.caption3, color = MaterialTheme.colors.error,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }

            if (!connected) {
                item {
                    val busy = connectionState is BleConnectionState.Connecting || connectionState is BleConnectionState.Pairing
                    Chip(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        onClick = { if (!viewModel.reconnect()) onChooseDevice() },
                        enabled = !busy,
                        label = { Text(if (busy) "Connecting…" else "Reconnect", color = Color.Black) },
                        icon = if (busy) {
                            { CircularProgressIndicator(modifier = Modifier.size(20.dp), indicatorColor = Color.Black, strokeWidth = 2.dp) }
                        } else null,
                        colors = ChipDefaults.primaryChipColors(backgroundColor = MeshGreen)
                    )
                }
                item {
                    Chip(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        onClick = onChooseDevice,
                        label = { Text("Choose radio") },
                        colors = ChipDefaults.secondaryChipColors()
                    )
                }
            }

            item {
                NavChip("Contacts", "${contacts.size} contacts", contactUnread, onNavigateToContacts)
            }
            item {
                NavChip("Channels", if (channels.isEmpty()) "Public & groups" else "${channels.size} channels", channelUnread, onNavigateToChannels)
            }
            if (connected) {
                item {
                    Chip(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        onClick = { viewModel.syncAll() },
                        enabled = sessionReady && !isSyncing,
                        label = { Text(if (isSyncing) "Syncing…" else "Sync now") },
                        icon = if (isSyncing) {
                            { CircularProgressIndicator(modifier = Modifier.size(20.dp), indicatorColor = MeshGreen, strokeWidth = 2.dp) }
                        } else null,
                        colors = ChipDefaults.secondaryChipColors()
                    )
                }
                item {
                    Chip(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        onClick = { viewModel.sendSelfAdvert(flood = false) },
                        enabled = sessionReady,
                        label = { Text("Send advert") },
                        secondaryLabel = { Text("Zero hop", style = MaterialTheme.typography.caption2) },
                        colors = ChipDefaults.secondaryChipColors()
                    )
                }
            }
            item { NavChip("Device info", deviceInfo?.firmwareVersion?.takeIf { it.isNotBlank() } ?: "Radio details", 0, onNavigateToDeviceInfo) }
            item { NavChip("Settings", null, 0, onNavigateToSettings) }
        }
    }
}

@Composable
private fun NavChip(label: String, secondary: String?, unread: Int, onClick: () -> Unit) {
    Chip(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        onClick = onClick,
        label = {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, maxLines = 1)
                UnreadBadge(unread)
            }
        },
        secondaryLabel = secondary?.let { { Text(it, style = MaterialTheme.typography.caption2, maxLines = 1) } },
        colors = ChipDefaults.chipColors(backgroundColor = MaterialTheme.colors.surface)
    )
}
