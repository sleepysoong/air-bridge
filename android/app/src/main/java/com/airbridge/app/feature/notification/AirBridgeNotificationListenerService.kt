package com.airbridge.app.feature.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.airbridge.app.feature.common.BridgeFeatureRegistry
import com.airbridge.app.feature.service.AirBridgeRelayForegroundService

class AirBridgeNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        AirBridgeRelayForegroundService.start(applicationContext)
        BridgeFeatureRegistry.notificationServiceDelegate?.onListenerConnected(activeNotifications)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        BridgeFeatureRegistry.notificationServiceDelegate?.onNotificationPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        BridgeFeatureRegistry.notificationServiceDelegate?.onNotificationRemoved(sbn)
    }
}

