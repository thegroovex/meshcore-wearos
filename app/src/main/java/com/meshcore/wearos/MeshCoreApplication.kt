package com.meshcore.wearos

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

/**
 * MeshCore WearOS Application class.
 *
 * Initializes Hilt dependency injection and sets up notification channels
 * for BLE connection status and incoming message alerts.
 */
@HiltAndroidApp
class MeshCoreApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        // BLE Connection Service Channel
        val connectionChannel = NotificationChannel(
            CHANNEL_ID_CONNECTION,
            getString(R.string.channel_connection_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_connection_description)
            setShowBadge(false)
        }

        // Incoming Messages Channel
        val messagesChannel = NotificationChannel(
            CHANNEL_ID_MESSAGES,
            getString(R.string.channel_messages_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_messages_description)
            enableVibration(true)
        }

        notificationManager.createNotificationChannels(
            listOf(connectionChannel, messagesChannel)
        )
    }

    companion object {
        const val CHANNEL_ID_CONNECTION = "meshcore_connection"
        const val CHANNEL_ID_MESSAGES = "meshcore_messages"
    }
}
