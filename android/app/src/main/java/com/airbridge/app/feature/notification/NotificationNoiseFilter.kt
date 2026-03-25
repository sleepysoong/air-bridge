package com.airbridge.app.feature.notification

import android.app.Notification
import android.service.notification.StatusBarNotification

class NotificationNoiseFilter(
    private val packageName: String,
) {
    fun shouldForward(statusBarNotification: StatusBarNotification): Boolean {
        if (statusBarNotification.packageName == packageName) {
            return false
        }

        val notification = statusBarNotification.notification
        val flags = notification.flags
        val isForegroundService = flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        val isServiceCategory = notification.category == Notification.CATEGORY_SERVICE
        val isOngoingWithoutContent = flags and Notification.FLAG_ONGOING_EVENT != 0 &&
            notification.extras?.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() &&
            notification.extras?.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank()

        return !(isForegroundService || isServiceCategory || isOngoingWithoutContent)
    }
}

