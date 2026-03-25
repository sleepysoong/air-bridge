package com.airbridge.app.feature.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

object BridgeForegroundNotificationFactory {
    const val ChannelId = "air_bridge_runtime"
    const val ChannelName = "air-bridge relay"
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
                ChannelName,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "air-bridge relay runtime state"
            },
        )
    }

    fun build(context: Context): Notification {
        return NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("air-bridge 연결 유지 중")
            .setContentText("Mac 과의 암호화 브리지를 백그라운드에서 유지하고 있어요.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}

