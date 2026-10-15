package com.meshcore.wearos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.meshcore.wearos.data.model.Contact
import com.meshcore.wearos.data.model.NodeType
import com.meshcore.wearos.ui.MainViewModel
import com.meshcore.wearos.ui.theme.MeshGray
import com.meshcore.wearos.ui.theme.MeshGreen
import com.meshcore.wearos.ui.theme.MeshOrange

/**
 * Contact list. Chat nodes first; repeaters / rooms / sensors can be shown with the filter
 * chip (they cannot receive plain text DMs anyway).
 */
@Composable
fun ContactsScreen(
    viewModel: MainViewModel,
    onContactSelected: (String) -> Unit
) {
    val contacts by viewModel.contacts.collectAsState()
    val isSyncing by viewModel.isSyncingContacts.collectAsState()
    var showAll by remember { mutableStateOf(false) }
    val listState = rememberScalingLazyListState()

    val chatContacts = contacts.filter { it.nodeType == NodeType.CHAT || it.nodeType == NodeType.UNKNOWN }
    val shown = if (showAll) contacts else chatContacts

    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item { ScreenTitle("Contacts") }
            if (shown.isEmpty()) {
                item {
                    Text(
                        text = if (isSyncing) "Syncing…" else "No contacts yet.\nSend an advert so others can find you.",
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            items(shown, key = { it.publicKeyHex }) { contact ->
                ContactChip(contact) { onContactSelected(contact.publicKeyHex) }
            }
            if (contacts.size != chatContacts.size) {
                item {
                    CompactChip(
                        onClick = { showAll = !showAll },
                        label = { Text(if (showAll) "Chat nodes only" else "Show all (${contacts.size})") },
                        colors = ChipDefaults.secondaryChipColors()
                    )
                }
            }
            item {
                CompactChip(
                    onClick = { viewModel.syncContacts() },
                    enabled = !isSyncing,
                    label = { Text(if (isSyncing) "Syncing…" else "Refresh") },
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
        }
    }
}

@Composable
private fun ContactChip(contact: Contact, onClick: () -> Unit) {
    val typeColor = when (contact.nodeType) {
        NodeType.CHAT -> MeshGreen
        NodeType.REPEATER -> MeshOrange
        NodeType.ROOM -> MaterialTheme.colors.secondary
        else -> MeshGray
    }
    Chip(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        onClick = onClick,
        label = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(if (contact.isRecentlySeen) MeshGreen else MeshGray, CircleShape)
                )
                Text(
                    text = (if (contact.isFavorite) "★ " else "") + contact.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                UnreadBadge(contact.unreadCount)
            }
        },
        secondaryLabel = {
            Text(
                text = if (contact.lastMessageText.isNotBlank()) contact.lastMessageText
                       else listOf(contact.nodeType.displayName, contact.routeLabel, formatAgo(contact.lastSeen))
                           .filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.caption2,
                color = if (contact.lastMessageText.isNotBlank()) MaterialTheme.colors.onSurface.copy(alpha = 0.7f) else typeColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        colors = ChipDefaults.chipColors(backgroundColor = MaterialTheme.colors.surface)
    )
}
