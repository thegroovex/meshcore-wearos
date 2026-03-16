package com.meshcore.wearos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.meshcore.wearos.data.model.Message
import com.meshcore.wearos.data.model.MessageDirection
import com.meshcore.wearos.data.model.MessageStatus
import com.meshcore.wearos.ui.MainViewModel
import com.meshcore.wearos.ui.theme.MeshGreen
import com.meshcore.wearos.ui.theme.MeshSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ChatScreen displays the message history with a specific contact and provides
 * a text input for sending new messages.
 *
 * On Wear OS, text input is handled via the system's voice input or emoji keyboard,
 * accessed through the standard RemoteInput mechanism.
 */
@Composable
fun ChatScreen(
    viewModel: MainViewModel,
    pubKeyHex: String
) {
    val contacts by viewModel.contacts.collectAsState()
    val contact = contacts.firstOrNull { it.publicKeyHex == pubKeyHex }
    val messages by viewModel.getMessagesForContact(pubKeyHex).collectAsState(initial = emptyList())
    val listState = rememberScalingLazyListState()

    // Clear unread count when chat is opened
    LaunchedEffect(pubKeyHex) {
        viewModel.clearUnreadCount(pubKeyHex)
    }

    // Scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
    ) {
        // Contact name header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = contact?.name?.ifEmpty { pubKeyHex.take(8) } ?: pubKeyHex.take(8),
                style = MaterialTheme.typography.title3,
                color = MeshGreen,
                textAlign = TextAlign.Center
            )
        }

        // Message list
        ScalingLazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        text = "No messages yet",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            items(messages) { message ->
                MessageBubble(message = message)
            }

            // Send button at the bottom
            item {
                SendMessageButton(
                    onSend = { text ->
                        if (contact != null) {
                            viewModel.sendDirectMessage(contact, text)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    val isOutgoing = message.direction == MessageDirection.OUTGOING
    val bubbleColor = if (isOutgoing) MeshGreen else MeshSurface
    val textColor = if (isOutgoing) Color.Black else MaterialTheme.colors.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 140.dp)
                .background(
                    color = bubbleColor,
                    shape = RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (isOutgoing) 12.dp else 4.dp,
                        bottomEnd = if (isOutgoing) 4.dp else 12.dp
                    )
                )
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.caption1,
                color = textColor
            )

            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = formatMessageTime(message.timestamp),
                    style = MaterialTheme.typography.caption2,
                    color = textColor.copy(alpha = 0.6f)
                )

                // Status indicator for outgoing messages
                if (isOutgoing) {
                    Text(
                        text = when (message.status) {
                            MessageStatus.SENDING -> " ·"
                            MessageStatus.SENT -> " ✓"
                            MessageStatus.DELIVERED -> " ✓✓"
                            MessageStatus.FAILED -> " ✗"
                        },
                        style = MaterialTheme.typography.caption2,
                        color = when (message.status) {
                            MessageStatus.DELIVERED -> MeshGreen
                            MessageStatus.FAILED -> MaterialTheme.colors.error
                            else -> textColor.copy(alpha = 0.6f)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SendMessageButton(onSend: (String) -> Unit) {
    // Predefined quick replies for Wear OS
    val quickReplies = listOf("OK", "Yes", "No", "On my way", "Later")
    var showQuickReplies by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showQuickReplies) {
            quickReplies.forEach { reply ->
                Button(
                    onClick = {
                        onSend(reply)
                        showQuickReplies = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.surface
                    )
                ) {
                    Text(reply, style = MaterialTheme.typography.caption1)
                }
            }
            Button(
                onClick = { showQuickReplies = false },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.error.copy(alpha = 0.3f)
                )
            ) {
                Text("Cancel", style = MaterialTheme.typography.caption1)
            }
        } else {
            Button(
                onClick = { showQuickReplies = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(backgroundColor = MeshGreen)
            ) {
                Text("Reply", color = Color.Black, style = MaterialTheme.typography.caption1)
            }
        }
    }
}

private fun formatMessageTime(epochSecs: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochSecs * 1000))
}
