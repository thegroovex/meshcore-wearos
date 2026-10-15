package com.meshcore.wearos.ui.screens

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.wear.input.RemoteInputIntentHelper
import com.meshcore.wearos.ble.BleConnectionState
import com.meshcore.wearos.data.model.Message
import com.meshcore.wearos.data.model.MessageDirection
import com.meshcore.wearos.data.model.MessageStatus
import com.meshcore.wearos.data.model.NodeType
import com.meshcore.wearos.ui.MainViewModel
import com.meshcore.wearos.ui.theme.MeshGreen
import com.meshcore.wearos.ui.theme.MeshSurface

private const val REMOTE_INPUT_KEY = "meshcore_message"
private val QUICK_REPLIES = listOf("OK 👍", "Yes", "No", "On my way", "Later", "Call me")

/** Direct chat with one contact. */
@Composable
fun ChatScreen(viewModel: MainViewModel, pubKeyHex: String) {
    val contact by viewModel.observeContact(pubKeyHex).collectAsState(initial = null)
    val messages by remember(pubKeyHex) { viewModel.getMessagesForContact(pubKeyHex) }.collectAsState(initial = emptyList())
    val connection by viewModel.connectionState.collectAsState()
    val conversationKey = Message.contactKey(pubKeyHex)

    DisposableEffect(conversationKey) {
        viewModel.openConversation(conversationKey)
        onDispose { viewModel.openConversation(null) }
    }
    LaunchedEffect(messages.size) { viewModel.clearUnreadCount(pubKeyHex) }

    val c = contact
    val subtitle = c?.let { listOf(it.nodeType.displayName, it.routeLabel).joinToString(" · ") }
    Conversation(
        title = c?.name ?: "Node ${pubKeyHex.take(8)}",
        subtitle = subtitle,
        messages = messages,
        showSender = false,
        canSend = c != null && connection is BleConnectionState.Connected &&
            (c.nodeType == NodeType.CHAT || c.nodeType == NodeType.UNKNOWN || c.nodeType == NodeType.ROOM),
        onSend = { text -> c?.let { viewModel.sendDirectMessage(it, text) } },
        onRetry = { msg -> c?.let { viewModel.retryMessage(msg, it) } },
        extraActions = {
            if (c != null) {
                CompactChip(
                    onClick = { viewModel.toggleFavorite(c.publicKeyHex) },
                    label = { Text(if (c.isFavorite) "★ Unfavorite" else "☆ Favorite") },
                    colors = ChipDefaults.secondaryChipColors()
                )
                if (c.outPathLen >= 0) {
                    CompactChip(
                        onClick = { viewModel.resetPath(c) },
                        label = { Text("Reset route") },
                        colors = ChipDefaults.secondaryChipColors()
                    )
                }
            }
        }
    )
}

/** Shared message list + composer used by direct and channel chats. */
@Composable
fun Conversation(
    title: String,
    subtitle: String?,
    messages: List<Message>,
    showSender: Boolean,
    canSend: Boolean,
    onSend: (String) -> Unit,
    onRetry: ((Message) -> Unit)? = null,
    extraActions: @Composable () -> Unit = {}
) {
    val listState = rememberScalingLazyListState()
    var showQuick by remember { mutableStateOf(false) }

    val inputLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data?.let { RemoteInput.getResultsFromIntent(it) }
                ?.getCharSequence(REMOTE_INPUT_KEY)?.toString()
            if (!text.isNullOrBlank()) onSend(text)
        }
    }

    // index: title(0) + messages; keep newest message in view
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size)
    }

    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            autoCentering = null
        ) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ScreenTitle(title)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.caption3,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f))
                }
            }
            if (messages.isEmpty()) {
                item {
                    Text("No messages yet", style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(8.dp))
                }
            }
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg, showSender, onRetry)
            }
            if (canSend) {
                item {
                    Chip(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        onClick = {
                            val intent: Intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                            RemoteInputIntentHelper.putRemoteInputsExtra(
                                intent,
                                listOf(RemoteInput.Builder(REMOTE_INPUT_KEY).setLabel("Message").build())
                            )
                            inputLauncher.launch(intent)
                        },
                        label = { Text("Write…", color = Color.Black) },
                        colors = ChipDefaults.primaryChipColors(backgroundColor = MeshGreen)
                    )
                }
                item {
                    CompactChip(
                        onClick = { showQuick = !showQuick },
                        label = { Text(if (showQuick) "Hide quick replies" else "Quick reply") },
                        colors = ChipDefaults.secondaryChipColors()
                    )
                }
                if (showQuick) {
                    items(QUICK_REPLIES) { reply ->
                        CompactChip(
                            onClick = { onSend(reply); showQuick = false },
                            label = { Text(reply) },
                            colors = ChipDefaults.chipColors(backgroundColor = MaterialTheme.colors.surface)
                        )
                    }
                }
            } else {
                item {
                    Text("Not connected", style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.error, modifier = Modifier.padding(4.dp))
                }
            }
            item { extraActions() }
        }
    }
}

@Composable
private fun MessageBubble(message: Message, showSender: Boolean, onRetry: ((Message) -> Unit)?) {
    val outgoing = message.direction == MessageDirection.OUTGOING
    val bubble = if (outgoing) MeshGreen else MeshSurface
    val fg = if (outgoing) Color.Black else MaterialTheme.colors.onSurface
    val failed = outgoing && message.status == MessageStatus.FAILED

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 150.dp)
                .background(
                    if (failed) MaterialTheme.colors.error.copy(alpha = 0.6f) else bubble,
                    RoundedCornerShape(12.dp, 12.dp, if (outgoing) 12.dp else 3.dp, if (outgoing) 3.dp else 12.dp)
                )
                .then(if (failed && onRetry != null) Modifier.clickable { onRetry(message) } else Modifier)
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            if (showSender && !outgoing && message.senderName.isNotBlank()) {
                Text(message.senderName, style = MaterialTheme.typography.caption3, color = MeshGreen)
            }
            Text(message.text, style = MaterialTheme.typography.caption1, color = fg)
            val meta = buildString {
                append(formatTime(if (outgoing) message.timestamp else message.receivedAt / 1000))
                if (!outgoing) {
                    message.snr?.let { append(" · %.0f dB".format(it)) }
                    if (message.pathLen in 0..63) append(" · ${message.pathLen}h")
                }
                if (outgoing) append(
                    when (message.status) {
                        MessageStatus.SENDING -> " · …"
                        MessageStatus.SENT -> " · ✓"
                        MessageStatus.DELIVERED -> " · ✓✓"
                        MessageStatus.FAILED -> if (onRetry != null) " · ✗ tap to retry" else " · ✗"
                    }
                )
            }
            Text(meta, style = MaterialTheme.typography.caption3, color = fg.copy(alpha = 0.65f))
        }
    }
}
