package com.meshcore.wearos.service

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService
import com.meshcore.wearos.ble.BleConnectionState
import com.meshcore.wearos.data.repository.MeshCoreRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

/**
 * Tile with connection state and unread messages; tapping opens the app.
 * Uses the ProtoLayout API (the old androidx.wear.tiles layout builders are deprecated).
 */
@OptIn(ExperimentalHorologistApi::class)
class MeshCoreTileService : SuspendingTileService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ServiceEntryPoint {
        fun repository(): MeshCoreRepository
    }

    private val repository: MeshCoreRepository by lazy {
        EntryPointAccessors.fromApplication(applicationContext, ServiceEntryPoint::class.java).repository()
    }

    override suspend fun resourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ResourceBuilders.Resources =
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()

    override suspend fun tileRequest(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val state = repository.connectionState.value
        val unread = repository.unreadTotal.first()
        val (status, color) = when (state) {
            is BleConnectionState.Connected -> state.deviceName to GREEN
            is BleConnectionState.Connecting -> "Connecting…" to ORANGE
            is BleConnectionState.Pairing -> "Pairing…" to ORANGE
            is BleConnectionState.Scanning -> "Scanning…" to ORANGE
            is BleConnectionState.Disconnected -> "Disconnected" to GREY
            is BleConnectionState.Error -> state.message to RED
        }

        val launch = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName("com.meshcore.wearos.ui.MainActivity")
                            .build()
                    ).build()
            ).build()

        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text("MeshCore", 16f, GREEN, bold = true))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(text(status, 13f, color))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(10f)).build())
            .addContent(
                text(
                    if (unread > 0) "$unread unread" else "No new messages",
                    if (unread > 0) 20f else 12f,
                    if (unread > 0) WHITE else GREY,
                    bold = unread > 0
                )
            )
            .build()

        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())
            .setHeight(androidx.wear.protolayout.DimensionBuilders.expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(launch).build())
            .addContent(column)
            .build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(15 * 60 * 1000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(2)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .build()
            ).build()

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val GREEN = 0xFF4CAF50.toInt()
        const val ORANGE = 0xFFFF9800.toInt()
        const val RED = 0xFFF44336.toInt()
        const val GREY = 0xFF9E9E9E.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
