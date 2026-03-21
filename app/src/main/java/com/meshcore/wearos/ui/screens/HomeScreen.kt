package com.meshcore.wearos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.meshcore.wearos.ble.BleConnectionState
import com.meshcore.wearos.ui.MainViewModel
import com.meshcore.wearos.ui.theme.MeshGreen
import com.meshcore.wearos.ui.theme.MeshOrange

/**
 * HomeScreen serves as the main navigation hub after connecting to a MeshCore device.
 *
 * It displays the device name, battery status, and quick-access buttons for
 * contacts, channels, device info, and settings.
 */
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToContacts: () -> Unit,
    onNavigateToDeviceInfo: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onDisconnected: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val deviceInfo by viewModel.deviceInfo.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val batteryMillivolts by viewModel.batteryMillivolts.collectAsState()
    val isSyncingContacts by viewModel.isSyncingContacts.collectAsState()

    // Navigate away if disconnected
    LaunchedEffect(connectionState) {
        if (connectionState is BleConnectionState.Disconnected) {
            onDisconnected()
        }
    }

    // Refresh battery status periodically
    LaunchedEffect(Unit) {
        viewModel.fetchBatteryStatus()
        viewModel.syncMessages()
    }

    val connectedDevice = connectionState as? BleConnectionState.Connected
    val unreadCount = contacts.sumOf { it.unreadCount }

    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Header
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 24.dp, bottom = 4.dp)
            ) {
                Text(
                    text = deviceInfo?.name ?: connectedDevice?.deviceName ?: "MeshCore",
                    style = MaterialTheme.typography.title3,
                    color = MeshGreen,
                    textAlign = TextAlign.Center
                )

                // Battery indicator
                if (batteryMillivolts > 0) {
                    val batteryPercent = deviceInfo?.batteryPercent ?: 0
                    val batteryColor = when {
                        batteryPercent > 50 -> MeshGreen
                        batteryPercent > 20 -> MeshOrange
                        else -> MaterialTheme.colors.error
                    }
                    Text(
                        text = "Battery: $batteryPercent%",
                        style = MaterialTheme.typography.caption2,
                        color = batteryColor
                    )
                }
            }
        }

        // Contacts button with unread badge
        item {
            Chip(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                onClick = onNavigateToContacts,
                label = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Contacts", style = MaterialTheme.typography.body2)
                        if (unreadCount > 0) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(
                                        color = MeshGreen,
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                                    style = MaterialTheme.typography.caption2,
                                    color = Color.Black
                                )
                            }
                        }
                    }
                },
                secondaryLabel = {
                    Text(
                        text = "${contacts.size} contacts",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                },
                colors = ChipDefaults.chipColors(backgroundColor = MaterialTheme.colors.surface)
            )
        }

        // Device Info button
        item {
            Chip(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                onClick = onNavigateToDeviceInfo,
                label = { Text("Device Info", style = MaterialTheme.typography.body2) },
                secondaryLabel = {
                    Text(
                        text = deviceInfo?.nodeType?.displayName ?: "Unknown",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                },
                colors = ChipDefaults.chipColors(backgroundColor = MaterialTheme.colors.surface)
            )
        }

        // Send Advert button
        item {
            Chip(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                onClick = { viewModel.sendSelfAdvert() },
                label = { Text("Send Advert", style = MaterialTheme.typography.body2) },
                secondaryLabel = {
                    Text(
                        text = "Announce presence",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                },
                colors = ChipDefaults.chipColors(backgroundColor = MaterialTheme.colors.surface)
            )
        }

        // Settings button
        item {
            Chip(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                onClick = onNavigateToSettings,
                label = { Text("Settings", style = MaterialTheme.typography.body2) },
                colors = ChipDefaults.chipColors(backgroundColor = MaterialTheme.colors.surface)
            )
        }

        // Sync button
        item {
            Button(
                onClick = {
                    viewModel.syncContacts()
                    viewModel.syncMessages()
                    viewModel.fetchBatteryStatus()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = MeshGreen),
                enabled = !isSyncingContacts
            ) {
                if (isSyncingContacts) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.Black,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Sync", color = Color.Black)
                }
            }
        }
    }
}
