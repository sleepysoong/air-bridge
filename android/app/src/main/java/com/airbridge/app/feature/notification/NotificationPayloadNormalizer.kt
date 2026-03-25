package com.airbridge.app.feature.notification

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import com.airbridge.app.feature.common.NotificationEventType
import com.airbridge.app.feature.common.NotificationSnapshot
import java.time.Clock
import java.time.Instant

class NotificationPayloadNormalizer(
    private val context: Context,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun normalize(
        eventType: NotificationEventType,
        statusBarNotification: StatusBarNotification,
    ): NotificationSnapshot {
        val notification = statusBarNotification.notification
        val extras = notification.extras
        val packageManager = context.packageManager
        val appName = runCatching {
            val applicationInfo = packageManager.getApplicationInfo(statusBarNotification.packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        }.getOrDefault(statusBarNotification.packageName)
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        val body = bigText.takeUnless { it.isNullOrBlank() }
            ?: extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()

        return NotificationSnapshot(
            eventType = eventType,
            notificationKey = statusBarNotification.key,
            packageName = statusBarNotification.packageName,
            appName = appName,
            title = title.ifBlank { null },
            body = body?.ifBlank { null },
            postedAt = Instant.ofEpochMilli(statusBarNotification.postTime),
            observedAt = Instant.now(clock),
            isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
        )
    }
}

