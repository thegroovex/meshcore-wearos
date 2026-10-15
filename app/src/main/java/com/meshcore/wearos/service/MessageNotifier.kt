package com.meshcore.wearos.service

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.meshcore.wearos.MeshCoreApplication
import com.meshcore.wearos.R
import com.meshcore.wearos.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Posts one notification per conversation for incoming messages. */
@Singleton
class MessageNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun notifyMessage(conversationKey: String, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33
        ) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_CONVERSATION, conversationKey)
        }
        val pi = PendingIntent.getActivity(
            context, conversationKey.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, MeshCoreApplication.CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.drawable.ic_mesh_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(conversationKey, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    fun cancel(conversationKey: String) {
        NotificationManagerCompat.from(context).cancel(conversationKey, NOTIFICATION_ID)
    }

    private companion object {
        const val NOTIFICATION_ID = 2001
    }
}
