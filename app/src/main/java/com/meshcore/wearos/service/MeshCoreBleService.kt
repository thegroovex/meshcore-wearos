package com.meshcore.wearos.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.meshcore.wearos.MeshCoreApplication
import com.meshcore.wearos.R
import com.meshcore.wearos.ble.BleConnectionState
import com.meshcore.wearos.data.repository.MeshCoreRepository
import com.meshcore.wearos.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps the process (and with it the BLE link) alive while the
 * watch screen is off. BLE notifications wake the CPU by themselves, so no long-running
 * wake lock is needed - the old 10-minute wake locks only drained the watch battery.
 */
@AndroidEntryPoint
class MeshCoreBleService : Service() {

    @Inject
    lateinit var repository: MeshCoreRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                repository.disconnect()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (!goForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (intent == null) {
                    // restarted by the system after process death -> reconnect
                    repository.connectToSavedDevice()
                }
                observe()
            }
        }
        return START_STICKY
    }

    private fun goForeground(): Boolean {
        if (!repository.hasPermissions()) return false
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(statusText(repository.connectionState.value)),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            )
            true
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException or missing permission
            Log.e("MeshCore-Service", "startForeground failed: ${e.message}")
            false
        }
    }

    private fun observe() {
        if (observerJob?.isActive == true) return
        observerJob = serviceScope.launch {
            repository.connectionState.collect { state ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(statusText(state)))
                refreshSurfaces(this@MeshCoreBleService)
            }
        }
    }

    private fun statusText(state: BleConnectionState) = when (state) {
        is BleConnectionState.Connected -> "Connected to ${state.deviceName}"
        is BleConnectionState.Connecting -> "Connecting to ${state.deviceName}…"
        is BleConnectionState.Pairing -> "Pairing with ${state.deviceName}…"
        is BleConnectionState.Scanning -> "Scanning…"
        is BleConnectionState.Disconnected -> "Disconnected"
        is BleConnectionState.Error -> "Error: ${state.message}"
    }

    private fun buildNotification(statusText: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MeshCoreBleService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MeshCoreApplication.CHANNEL_ID_CONNECTION)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_mesh_notification)
            .setContentIntent(contentIntent)
            .addAction(R.drawable.ic_disconnect, getString(R.string.action_disconnect), disconnectIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.meshcore.wearos.action.START_SERVICE"
        const val ACTION_STOP = "com.meshcore.wearos.action.STOP_SERVICE"
        const val ACTION_DISCONNECT = "com.meshcore.wearos.action.DISCONNECT"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, MeshCoreBleService::class.java).setAction(ACTION_START)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e("MeshCore-Service", "Could not start service: ${e.message}")
            }
        }

        /** Asks the system to re-query our tile and complication. */
        fun refreshSurfaces(context: Context) {
            try {
                TileService.getUpdater(context).requestUpdate(MeshCoreTileService::class.java)
                ComplicationDataSourceUpdateRequester.create(
                    context,
                    android.content.ComponentName(context, MeshCoreComplicationService::class.java)
                ).requestUpdateAll()
            } catch (e: Exception) {
                Log.w("MeshCore-Service", "Surface refresh failed: ${e.message}")
            }
        }
    }
}
