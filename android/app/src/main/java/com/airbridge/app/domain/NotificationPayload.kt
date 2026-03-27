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
data class NotificationImagePayload(
    @SerialName("mime_type")
    val mimeType: String,
    @SerialName("data_base64")
    val dataBase64: String,
    val width: Int,
    val height: Int,
)

@Serializable
data class NotificationAssetPayload(
    @SerialName("app_icon")
    val appIcon: NotificationImagePayload? = null,
    @SerialName("large_icon")
    val largeIcon: NotificationImagePayload? = null,
    @SerialName("hero_image")
    val heroImage: NotificationImagePayload? = null,
)

@Serializable
data class NotificationPayload(
    @SerialName("schema_version")
    val schemaVersion: Int = 2,
    val event: NotificationEvent,
    @SerialName("notification_id")
    val notificationId: String,
    @SerialName("package_name")
    val packageName: String,
    @SerialName("app_name")
    val appName: String,
    val title: String? = null,
    val subtitle: String? = null,
    val body: String? = null,
    @SerialName("posted_at")
    val postedAt: String,
    @SerialName("observed_at")
    val observedAt: String,
    @SerialName("is_ongoing")
    val isOngoing: Boolean,
    val category: String? = null,
    @SerialName("channel_id")
    val channelId: String? = null,
    @SerialName("channel_name")
    val channelName: String? = null,
    val assets: NotificationAssetPayload? = null,
)
