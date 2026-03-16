package com.meshcore.wearos.service

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService
import com.meshcore.wearos.R
import com.meshcore.wearos.data.repository.MeshCoreRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * MeshCoreTileService provides a Wear OS tile for quick access to the MeshCore app.
 *
 * The tile displays the current connection status and unread message count,
 * allowing users to quickly check their mesh network status from the watch face.
 */
@OptIn(ExperimentalHorologistApi::class)
@AndroidEntryPoint
class MeshCoreTileService : SuspendingTileService() {

    @Inject
    lateinit var repository: MeshCoreRepository

    override suspend fun resourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ResourceBuilders.Resources {
        return ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .build()
    }

    override suspend fun tileRequest(
        requestParams: RequestBuilders.TileRequest
    ): TileBuilders.Tile {
        val connectionState = repository.connectionState.value

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(
                TimelineBuilders.Timeline.fromLayoutElement(
                    buildTileLayout(connectionState.toString())
                )
            )
            .setFreshnessIntervalMillis(5 * 60 * 1000L) // Refresh every 5 minutes
            .build()
    }

    private fun buildTileLayout(statusText: String): LayoutElementBuilders.LayoutElement {
        val launchAction = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName("com.meshcore.wearos.ui.MainActivity")
                    .build()
            )
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setWidth(dp(192f))
            .setHeight(dp(192f))
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText("MeshCore")
                            .setFontStyle(
                                LayoutElementBuilders.FontStyle.Builder()
                                    .setSize(dp(16f))
                                    .setColor(argb(0xFFFFFFFF.toInt()))
                                    .build()
                            )
                            .build()
                    )
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText(statusText)
                            .setFontStyle(
                                LayoutElementBuilders.FontStyle.Builder()
                                    .setSize(dp(12f))
                                    .setColor(argb(0xFFAAAAAA.toInt()))
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()
    }

    companion object {
        private const val RESOURCES_VERSION = "1"
    }
}
