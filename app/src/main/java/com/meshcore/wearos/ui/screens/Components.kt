package com.meshcore.wearos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.meshcore.wearos.ble.BleConnectionState
import com.meshcore.wearos.ui.theme.MeshGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, color: Color = MeshGreen) {
    Text(
        text = text,
        style = MaterialTheme.typography.title3,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 4.dp, start = 24.dp, end = 24.dp)
    )
}

@Composable
fun UnreadBadge(count: Int) {
    if (count <= 0) return
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .background(MeshGreen, CircleShape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.caption2,
            color = Color.Black
        )
    }
}

fun BleConnectionState.label(): String = when (this) {
    is BleConnectionState.Connected -> "Connected"
    is BleConnectionState.Connecting -> "Connecting…"
    is BleConnectionState.Pairing -> "Pairing - enter PIN"
    is BleConnectionState.Scanning -> "Scanning…"
    is BleConnectionState.Disconnected -> "Disconnected"
    is BleConnectionState.Error -> message
}

fun formatTime(epochSecs: Long): String {
    if (epochSecs <= 0) return ""
    val now = System.currentTimeMillis() / 1000
    val pattern = if (now - epochSecs < 20 * 3600) "HH:mm" else "dd.MM. HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochSecs * 1000))
}

fun formatAgo(epochSecs: Long): String {
    if (epochSecs <= 0) return ""
    val diff = System.currentTimeMillis() / 1000 - epochSecs
    return when {
        diff < 0 -> "now"
        diff < 60 -> "now"
        diff < 3600 -> "${diff / 60}m"
        diff < 86400 -> "${diff / 3600}h"
        diff < 30 * 86400 -> "${diff / 86400}d"
        else -> SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(Date(epochSecs * 1000))
    }
}
