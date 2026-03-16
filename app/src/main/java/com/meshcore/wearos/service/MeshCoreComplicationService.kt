package com.meshcore.wearos.service

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.meshcore.wearos.ble.BleConnectionState
import com.meshcore.wearos.data.repository.MeshCoreRepository
import com.meshcore.wearos.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * MeshCoreComplicationService provides complication data for watch faces.
 *
 * It displays the current connection status and can show unread message counts
 * directly on the watch face, giving users at-a-glance mesh network information.
 */
@AndroidEntryPoint
class MeshCoreComplicationService : SuspendingComplicationDataSourceService() {

    @Inject
    lateinit var repository: MeshCoreRepository

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.SHORT_TEXT -> buildShortTextComplication("Mesh", "Connected")
            ComplicationType.LONG_TEXT -> buildLongTextComplication("MeshCore: Connected")
            else -> null
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val connectionState = repository.connectionState.value
        val (shortStatus, longStatus) = when (connectionState) {
            is BleConnectionState.Connected -> Pair("Mesh", "MeshCore: ${connectionState.deviceName}")
            is BleConnectionState.Connecting -> Pair("...", "MeshCore: Connecting...")
            is BleConnectionState.Scanning -> Pair("Scan", "MeshCore: Scanning")
            is BleConnectionState.Disconnected -> Pair("Off", "MeshCore: Disconnected")
            is BleConnectionState.Error -> Pair("Err", "MeshCore: Error")
        }

        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> buildShortTextComplication("Mesh", shortStatus)
            ComplicationType.LONG_TEXT -> buildLongTextComplication(longStatus)
            else -> null
        }
    }

    private fun buildShortTextComplication(title: String, text: String): ShortTextComplicationData {
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder("MeshCore status: $text").build()
        )
            .setTitle(PlainComplicationText.Builder(title).build())
            .setTapAction(buildLaunchPendingIntent())
            .build()
    }

    private fun buildLongTextComplication(text: String): LongTextComplicationData {
        return LongTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder(text).build()
        )
            .setTapAction(buildLaunchPendingIntent())
            .build()
    }

    private fun buildLaunchPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
