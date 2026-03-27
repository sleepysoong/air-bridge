package com.airbridge.app.feature.common

import android.app.Service
import android.service.notification.StatusBarNotification
import java.time.Instant

const val MaxClipboardPayloadBytes = 20 * 1024 * 1024

data class ClipboardSnapshot(
    val mimeType: String,
    val label: String?,
    val plainText: String? = null,
    val htmlText: String? = null,
    val uriList: List<String> = emptyList(),
    val binaryPayload: ByteArray? = null,
    val fingerprint: String,
    val capturedAt: Instant,
)

data class NotificationSnapshot(
    val eventType: NotificationEventType,
    val notificationKey: String,
    val packageName: String,
    val appName: String,
    val title: String?,
    val subtitle: String?,
    val body: String?,
    val postedAt: Instant,
    val observedAt: Instant,
    val isOngoing: Boolean,
    val category: String?,
    val channelId: String?,
    val channelName: String?,
    val assets: NotificationAssetSnapshot? = null,
)

data class NotificationAssetSnapshot(
    val appIcon: NotificationImageSnapshot? = null,
    val largeIcon: NotificationImageSnapshot? = null,
    val heroImage: NotificationImageSnapshot? = null,
)

data class NotificationImageSnapshot(
    val mimeType: String,
    val binaryPayload: ByteArray,
    val width: Int,
    val height: Int,
)

enum class ClipboardTransferOrigin {
    ForegroundMonitor,
    ManualAction,
    RemoteRelay,
}

enum class NotificationEventType {
    Posted,
    Updated,
    Removed,
}

sealed interface ClipboardReadOutcome {
    data class Success(val snapshot: ClipboardSnapshot) : ClipboardReadOutcome
    data object Empty : ClipboardReadOutcome
    data class Unsupported(val reason: String) : ClipboardReadOutcome
    data class Failure(val message: String, val cause: Throwable? = null) : ClipboardReadOutcome
}

sealed interface ClipboardApplyOutcome {
    data object Applied : ClipboardApplyOutcome
    data class Failure(val message: String, val cause: Throwable? = null) : ClipboardApplyOutcome
}

data class ClipboardSyncStatus(
    val isMonitoring: Boolean = false,
    val lastCapturedAt: Instant? = null,
    val lastSentAt: Instant? = null,
    val lastAppliedAt: Instant? = null,
    val lastFingerprint: String? = null,
    val lastError: String? = null,
)

interface ClipboardOutboundSink {
    suspend fun publishClipboard(snapshot: ClipboardSnapshot, origin: ClipboardTransferOrigin)
}

interface NotificationOutboundSink {
    suspend fun publishNotification(snapshot: NotificationSnapshot)
}

interface NotificationServiceDelegate {
    fun onListenerConnected(activeNotifications: Array<StatusBarNotification>?)
    fun onNotificationPosted(statusBarNotification: StatusBarNotification)
    fun onNotificationRemoved(statusBarNotification: StatusBarNotification)
}

interface BridgeForegroundServiceDelegate {
    fun onForegroundServiceStarted(service: Service)
    fun onForegroundServiceStopped()
}
