package com.airbridge.app.feature.common

object BridgeFeatureRegistry {
    @Volatile
    var notificationServiceDelegate: NotificationServiceDelegate? = null

    @Volatile
    var foregroundServiceDelegate: BridgeForegroundServiceDelegate? = null
}
