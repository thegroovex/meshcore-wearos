package com.meshcore.wearos.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.meshcore.wearos.MeshCoreApplication
import com.meshcore.wearos.R
import com.meshcore.wearos.ble.BleConnectionState
import com.meshcore.wearos.data.repository.MeshCoreRepository
import com.meshcore.wearos.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MeshCoreBleService is a foreground service that maintains the BLE connection
 * to the MeshCore companion radio even when the app is in the background.
 */
@AndroidEntryPoint
class MeshCoreBleService : Service() {

    @Inject
    lateinit var repository: MeshCoreRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeshCore:BleWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundService()
            ACTION_STOP -> stopSelf()
            ACTION_DISCONNECT -> {
                repository.disconnect()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        startForeground(NOTIFICATION_ID, buildConnectionNotification("Connecting..."))

        // Keep CPU alive for BLE stability
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(10 * 60 * 1000L /* 10 min */)
        }

        serviceScope.launch {
            repository.connectionState.collect { state ->
                updateNotification(state)
                
                // Refresh WakeLock when connected
                if (state is BleConnectionState.Connected) {
                    if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 1000L)
                }
            }
        }
    }

    private fun updateNotification(state: BleConnectionState) {
        val message = when (state) {
            is BleConnectionState.Connected -> "Connected to ${state.deviceName}"
            is BleConnectionState.Connecting -> "Connecting to ${state.deviceName}..."
            is BleConnectionState.Scanning -> "Scanning for devices..."
            is BleConnectionState.Disconnected -> "Disconnected"
            is BleConnectionState.Error -> "Error: ${state.message}"
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildConnectionNotification(message))
    }

    private fun buildConnectionNotification(statusText: String): Notification {
        val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, MeshCoreBleService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, MeshCoreApplication.CHANNEL_ID_CONNECTION)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_mesh_notification)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_disconnect,
                getString(R.string.action_disconnect),
                disconnectPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    companion object {
        const val ACTION_START = "com.meshcore.wearos.action.START_SERVICE"
        const val ACTION_STOP = "com.meshcore.wearos.action.STOP_SERVICE"
        const val ACTION_DISCONNECT = "com.meshcore.wearos.action.DISCONNECT"
        private const val NOTIFICATION_ID = 1001
    }
}
