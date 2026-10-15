package com.meshcore.wearos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.meshcore.wearos.ble.BleConnectionState
import com.meshcore.wearos.data.model.Message
import com.meshcore.wearos.ui.MainViewModel

/** Group channels configured on the radio (index 0 is usually "Public"). */
@Composable
fun ChannelsScreen(viewModel: MainViewModel, onChannelSelected: (Int) -> Unit) {
    val channels by viewModel.channels.collectAsState()
    val listState = rememberScalingLazyListState()

    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item { ScreenTitle("Channels") }
            if (channels.isEmpty()) {
                item {
                    Text(
                        "No channels yet. They are loaded from the radio after connecting.",
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            items(channels, key = { it.index }) { ch ->
                Chip(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    onClick = { onChannelSelected(ch.index) },
                    label = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("# ${ch.name}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            UnreadBadge(ch.unreadCount)
                        }
                    },
                    secondaryLabel = {
                        Text(
                            ch.lastMessageText.ifBlank { "Channel ${ch.index}" },
                            style = MaterialTheme.typography.caption2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = ChipDefaults.chipColors(backgroundColor = MaterialTheme.colors.surface)
                )
            }
        }
    }
}

@Composable
fun ChannelChatScreen(viewModel: MainViewModel, channelIndex: Int) {
    val channel by viewModel.observeChannel(channelIndex).collectAsState(initial = null)
    val messages by remember(channelIndex) { viewModel.getMessagesForChannel(channelIndex) }.collectAsState(initial = emptyList())
    val connection by viewModel.connectionState.collectAsState()
    val key = Message.channelKey(channelIndex)

    DisposableEffect(key) {
        viewModel.openConversation(key)
        onDispose { viewModel.openConversation(null) }
    }
    LaunchedEffect(messages.size) { viewModel.clearChannelUnread(channelIndex) }

    Conversation(
        title = "# " + (channel?.name ?: "Channel $channelIndex"),
        subtitle = "Flood · no delivery receipts",
        messages = messages,
        showSender = true,
        canSend = connection is BleConnectionState.Connected,
        onSend = { viewModel.sendChannelMessage(channelIndex, it) }
    )
}
