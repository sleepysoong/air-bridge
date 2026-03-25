package com.airbridge.app.feature.notification

import android.service.notification.StatusBarNotification
import android.util.Log
import com.airbridge.app.feature.common.NotificationEventType
import com.airbridge.app.feature.common.NotificationOutboundSink
import com.airbridge.app.feature.common.NotificationServiceDelegate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class NotificationForwarder(
    private val outboundSink: NotificationOutboundSink,
    private val noiseFilter: NotificationNoiseFilter,
    private val normalizer: NotificationPayloadNormalizer,
    private val scope: CoroutineScope,
) : NotificationServiceDelegate {
    private val observedKeys = ConcurrentHashMap.newKeySet<String>()

    override fun onListenerConnected(activeNotifications: Array<StatusBarNotification>?) {
        activeNotifications
            ?.filter(noiseFilter::shouldForward)
            ?.forEach { statusBarNotification ->
                observedKeys.add(statusBarNotification.key)
                forward(NotificationEventType.Posted, statusBarNotification)
            }
    }

    override fun onNotificationPosted(statusBarNotification: StatusBarNotification) {
        if (!noiseFilter.shouldForward(statusBarNotification)) {
            return
        }

        val eventType = if (observedKeys.add(statusBarNotification.key)) {
            NotificationEventType.Posted
        } else {
            NotificationEventType.Updated
        }

        forward(eventType, statusBarNotification)
    }

    override fun onNotificationRemoved(statusBarNotification: StatusBarNotification) {
        val wasObserved = observedKeys.remove(statusBarNotification.key)
        if (!wasObserved && !noiseFilter.shouldForward(statusBarNotification)) {
            return
        }

        forward(NotificationEventType.Removed, statusBarNotification)
    }

    private fun forward(
        eventType: NotificationEventType,
        statusBarNotification: StatusBarNotification,
    ) {
        scope.launch {
            runCatching {
                outboundSink.publishNotification(normalizer.normalize(eventType, statusBarNotification))
            }.onFailure { error ->
                Log.w(
                    LogTag,
                    "알림 이벤트를 중계 파이프라인으로 넘기지 못했어요: ${statusBarNotification.key}",
                    error,
                )
            }
        }
    }

    private companion object {
        const val LogTag = "NotificationForwarder"
    }
}

