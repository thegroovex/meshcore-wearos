package com.meshcore.wearos.service

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import com.meshcore.wearos.ble.BleConnectionState
import com.meshcore.wearos.data.repository.MeshCoreRepository
import com.meshcore.wearos.ui.MainActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService.ComplicationRequestListener

/**
 * Watch-face complication: connection state and unread count.
 * (Fixed: the old class overrode the removed onComplicationUpdate() API and imported
 * ComplicationData twice, so the project did not compile.)
 */
class MeshCoreComplicationService : ComplicationDataSourceService() {

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        scope.launch {
            val data = try { buildData(request) } catch (e: Exception) { null }
            try { listener.onComplicationData(data) } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }


    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ServiceEntryPoint {
        fun repository(): MeshCoreRepository
    }

    private val repository: MeshCoreRepository by lazy {
        EntryPointAccessors.fromApplication(applicationContext, ServiceEntryPoint::class.java).repository()
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = when (type) {
        ComplicationType.SHORT_TEXT -> shortText("Mesh", "2")
        ComplicationType.LONG_TEXT -> longText("MeshCore: 2 unread")
        else -> null
    }

    private suspend fun buildData(request: ComplicationRequest): ComplicationData? {
        val state = repository.connectionState.value
        val unread = repository.unreadTotal.first()
        val short = when (state) {
            is BleConnectionState.Connected -> if (unread > 0) unread.toString() else "On"
            is BleConnectionState.Connecting, is BleConnectionState.Pairing -> "…"
            is BleConnectionState.Scanning -> "Scan"
            is BleConnectionState.Disconnected -> "Off"
            is BleConnectionState.Error -> "Err"
        }
        val long = when (state) {
            is BleConnectionState.Connected ->
                "${state.deviceName}" + if (unread > 0) " · $unread unread" else ""
            is BleConnectionState.Connecting, is BleConnectionState.Pairing -> "MeshCore: connecting…"
            is BleConnectionState.Scanning -> "MeshCore: scanning"
            is BleConnectionState.Disconnected -> "MeshCore: offline"
            is BleConnectionState.Error -> "MeshCore: ${state.message}"
        }
        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> shortText("Mesh", short)
            ComplicationType.LONG_TEXT -> longText(long)
            else -> null
        }
    }

    private fun shortText(title: String, text: String) = ShortTextComplicationData.Builder(
        text = PlainComplicationText.Builder(text).build(),
        contentDescription = PlainComplicationText.Builder("MeshCore $text").build()
    )
        .setTitle(PlainComplicationText.Builder(title).build())
        .setTapAction(launchIntent())
        .build()

    private fun longText(text: String) = LongTextComplicationData.Builder(
        text = PlainComplicationText.Builder(text).build(),
        contentDescription = PlainComplicationText.Builder(text).build()
    )
        .setTapAction(launchIntent())
        .build()

    private fun launchIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
