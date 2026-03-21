package com.meshcore.wearos.service

import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.TimelineBuilders
import androidx.wear.tiles.DimensionBuilders
import com.google.android.horologist.tiles.SuspendingTileService
import com.meshcore.wearos.ble.BleConnectionState
import com.meshcore.wearos.data.repository.MeshCoreRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * MeshCoreTileService provides a quick-access tile for connection status.
 */
class MeshCoreTileService : SuspendingTileService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ServiceEntryPoint {
        fun repository(): MeshCoreRepository
    }

    private val repository: MeshCoreRepository by lazy {
        val appContext = this.applicationContext ?: throw IllegalStateException("Context not available")
        EntryPointAccessors.fromApplication(
            appContext,
            ServiceEntryPoint::class.java
        ).repository()
    }

    override suspend fun resourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ResourceBuilders.Resources {
        return ResourceBuilders.Resources.Builder()
            .setVersion("1")
            .build()
    }

    override suspend fun tileRequest(
        requestParams: RequestBuilders.TileRequest
    ): TileBuilders.Tile {
        val connectionState = repository.connectionState.value
        val statusText = when (connectionState) {
            is BleConnectionState.Connected -> "Connected to ${connectionState.deviceName}"
            is BleConnectionState.Connecting -> "Connecting..."
            is BleConnectionState.Scanning -> "Scanning for devices..."
            is BleConnectionState.Disconnected -> "Disconnected"
            is BleConnectionState.Error -> "Error: ${connectionState.message}"
        }

        val layout = LayoutElementBuilders.Box.Builder()
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText("MeshCore")
                            .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(DimensionBuilders.sp(16f)).build())
                            .build()
                    )
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText(statusText)
                            .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(DimensionBuilders.sp(12f)).build())
                            .build()
                    )
                    .build()
            )
            .build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(layout)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()
    }
}
