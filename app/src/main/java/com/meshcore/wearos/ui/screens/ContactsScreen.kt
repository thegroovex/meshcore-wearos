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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.meshcore.wearos.data.model.Contact
import com.meshcore.wearos.data.model.NodeType
import com.meshcore.wearos.ui.MainViewModel
import com.meshcore.wearos.ui.theme.MeshGreen
import com.meshcore.wearos.ui.theme.MeshGray
import com.meshcore.wearos.ui.theme.MeshOrange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ContactsScreen displays the list of known MeshCore contacts.
 *
 * Contacts are sorted by favorites, then by last message time.
 * Each contact shows their name, node type, online status, and unread message count.
 */
@Composable
fun ContactsScreen(
    viewModel: MainViewModel,
    onContactSelected: (String) -> Unit
) {
    val contacts by viewModel.contacts.collectAsState()
    val isSyncing by viewModel.isSyncingContacts.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.syncContacts()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
    ) {
        if (contacts.isEmpty() && isSyncing) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    indicatorColor = MeshGreen
                )
                Text(
                    text = "Syncing contacts...",
                    style = MaterialTheme.typography.caption2,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else if (contacts.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "No contacts yet",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = "Send an advert to be discovered",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        } else {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    Text(
                        text = "Contacts",
                        style = MaterialTheme.typography.title3,
                        color = MeshGreen,
                        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp)
                    )
                }

                if (isSyncing) {
                    item {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            indicatorColor = MeshGreen,
                            strokeWidth = 2.dp
                        )
                    }
                }

                items(contacts) { contact ->
                    ContactChip(
                        contact = contact,
                        onClick = { onContactSelected(contact.publicKeyHex) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactChip(
    contact: Contact,
    onClick: () -> Unit
) {
    val nodeTypeColor = when (contact.nodeType) {
        NodeType.CHAT -> MeshGreen
        NodeType.REPEATER -> MeshOrange
        NodeType.ROOM -> MaterialTheme.colors.secondary
        else -> MeshGray
    }

    Chip(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        onClick = onClick,
        label = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Online indicator dot
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                color = if (contact.isOnline) MeshGreen else MeshGray,
                                shape = CircleShape
                            )
                    )
                    Text(
                        text = contact.name.ifEmpty { contact.publicKeyHex.take(8) },
                        style = MaterialTheme.typography.body2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Unread count badge
                if (contact.unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(color = MeshGreen, shape = CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (contact.unreadCount > 9) "9+" else contact.unreadCount.toString(),
                            style = MaterialTheme.typography.caption2,
                            color = Color.Black
                        )
                    }
                }
            }
        },
        secondaryLabel = {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = contact.nodeType.displayName,
                    style = MaterialTheme.typography.caption2,
                    color = nodeTypeColor,
                    modifier = Modifier.weight(1f)
                )
                if (contact.lastSeen > 0) {
                    Text(
                        text = formatLastSeen(contact.lastSeen),
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        },
        colors = ChipDefaults.chipColors(backgroundColor = MaterialTheme.colors.surface)
    )
}

private fun formatLastSeen(epochSecs: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - epochSecs
    return when {
        diff < 60 -> "now"
        diff < 3600 -> "${diff / 60}m"
        diff < 86400 -> "${diff / 3600}h"
        else -> SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date(epochSecs * 1000))
    }
}
