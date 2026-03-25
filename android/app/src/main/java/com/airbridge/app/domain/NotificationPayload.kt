package com.airbridge.app.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class NotificationEvent {
    @SerialName("posted")
    POSTED,

    @SerialName("updated")
    UPDATED,

    @SerialName("removed")
    REMOVED,
}

@Serializable
data class NotificationPayload(
    @SerialName("schema_version")
    val schemaVersion: Int = 1,
    val event: NotificationEvent,
    @SerialName("notification_id")
    val notificationId: String,
    @SerialName("package_name")
    val packageName: String,
    @SerialName("app_name")
    val appName: String,
    val title: String? = null,
    val body: String? = null,
    @SerialName("posted_at")
    val postedAt: String,
    @SerialName("is_ongoing")
    val isOngoing: Boolean,
)

