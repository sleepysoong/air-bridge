package com.airbridge.app.feature.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.airbridge.app.R

object BridgeForegroundNotificationFactory {
    const val ChannelId = "air_bridge_runtime"
    const val NotificationId = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        val existingChannel = manager.getNotificationChannel(ChannelId)
        if (existingChannel != null) {
            return
        }

        manager.createNotificationChannel(
            NotificationChannel(
                ChannelId,
                context.getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.service_channel_description)
            },
        )
    }

    fun build(context: Context): Notification {
        return NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(context.getString(R.string.service_notification_title))
            .setContentText(context.getString(R.string.service_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
