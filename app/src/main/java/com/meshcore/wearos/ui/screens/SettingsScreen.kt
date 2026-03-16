package com.meshcore.wearos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.meshcore.wearos.ui.MainViewModel
import com.meshcore.wearos.ui.theme.MeshGreen

/**
 * SettingsScreen provides access to app configuration and device management options.
 *
 * From here, users can send a self-advertisement, sync data, and disconnect from the device.
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onDisconnect: () -> Unit
) {
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.title3,
                color = MeshGreen,
                modifier = Modifier.padding(top = 24.dp, bottom = 4.dp)
            )
        }

        // Send Self Advert (flood mode)
        item {
            Chip(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                onClick = { viewModel.sendSelfAdvert(/* floodMode = */ ) },
                label = { Text("Send Advert", style = MaterialTheme.typography.body2) },
                secondaryLabel = {
                    Text(
                        text = "Announce to nearby nodes",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                },
                colors = ChipDefaults.chipColors(backgroundColor = MaterialTheme.colors.surface)
            )
        }

        // Sync All
        item {
            Chip(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                onClick = {
                    viewModel.syncContacts()
                    viewModel.syncMessages()
                    viewModel.fetchBatteryStatus()
                },
                label = { Text("Sync All", style = MaterialTheme.typography.body2) },
                secondaryLabel = {
                    Text(
                        text = "Refresh contacts & messages",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                },
                colors = ChipDefaults.chipColors(backgroundColor = MaterialTheme.colors.surface)
            )
        }

        // App version info
        item {
            Chip(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                onClick = {},
                label = { Text("Version", style = MaterialTheme.typography.body2) },
                secondaryLabel = {
                    Text(
                        text = "1.0.0 (Protocol v3)",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                },
                colors = ChipDefaults.chipColors(backgroundColor = MaterialTheme.colors.surface)
            )
        }

        // Disconnect button
        item {
            Button(
                onClick = onDisconnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.error
                )
            ) {
                Text("Disconnect", color = Color.White)
            }
        }
    }
}
