package com.airbridge.app.feature.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.StatusBarNotification
import com.airbridge.app.feature.clipboard.MimeImageJpeg
import com.airbridge.app.feature.clipboard.MimeImagePng
import com.airbridge.app.feature.common.NotificationAssetSnapshot
import com.airbridge.app.feature.common.NotificationEventType
import com.airbridge.app.feature.common.NotificationImageSnapshot
import com.airbridge.app.feature.common.NotificationSnapshot
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import kotlin.math.max
import kotlin.math.roundToInt

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
        val title = extras
            ?.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            ?.trim()
            ?.takeUnless(String::isNullOrBlank)
        val body = resolveBody(extras)
        val subtitle = resolveSubtitle(extras, title, body)
        val channelId = notification.channelId?.trim()?.takeUnless(String::isBlank)
        val channelName = resolveChannelName(channelId)
        val assets = if (eventType == NotificationEventType.Removed) {
            null
        } else {
            buildAssets(statusBarNotification)
        }

        return NotificationSnapshot(
            eventType = eventType,
            notificationKey = statusBarNotification.key,
            packageName = statusBarNotification.packageName,
            appName = appName,
            title = title,
            subtitle = subtitle,
            body = body,
            postedAt = Instant.ofEpochMilli(statusBarNotification.postTime),
            observedAt = Instant.now(clock),
            isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
            category = notification.category?.trim()?.takeUnless(String::isBlank),
            channelId = channelId,
            channelName = channelName,
            assets = assets,
        )
    }

    private fun resolveBody(extras: android.os.Bundle?): String? {
        val bigText = extras
            ?.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?.toString()
            ?.trim()
            ?.takeUnless(String::isBlank)
        if (bigText != null) {
            return bigText
        }

        val text = extras
            ?.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()
            ?.trim()
            ?.takeUnless(String::isBlank)
        if (text != null) {
            return text
        }

        val textLines = extras
            ?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.map { it.toString().trim() }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }

        return textLines?.joinToString(separator = "\n")
    }

    private fun resolveSubtitle(
        extras: android.os.Bundle?,
        title: String?,
        body: String?,
    ): String? {
        val candidates = listOf(
            extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
            extras?.getCharSequence(Notification.EXTRA_SUB_TEXT),
            extras?.getCharSequence(Notification.EXTRA_SUMMARY_TEXT),
        )

        return candidates
            .asSequence()
            .mapNotNull { it?.toString()?.trim() }
            .firstOrNull { candidate ->
                candidate.isNotBlank() && candidate != title && candidate != body
            }
    }

    private fun resolveChannelName(channelId: String?): String? {
        if (channelId == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        return notificationManager
            ?.getNotificationChannel(channelId)
            ?.name
            ?.toString()
            ?.trim()
            ?.takeUnless(String::isBlank)
    }

    private fun buildAssets(statusBarNotification: StatusBarNotification): NotificationAssetSnapshot? {
        val notification = statusBarNotification.notification
        val extras = notification.extras
        val packageManager = context.packageManager

        val appIcon = runCatching {
            packageManager.getApplicationIcon(statusBarNotification.packageName)
        }.getOrNull()?.let { drawable ->
            renderDrawableAsset(
                drawable = drawable,
                mimeType = MimeImagePng,
                maxDimensionPx = MaxAppIconDimensionPx,
                maxBytes = MaxAppIconBytes,
            )
        }

        val largeIcon = renderAnyAsset(
            source = notification.getLargeIcon()
                ?: extras?.readParcelableCandidate(Notification.EXTRA_LARGE_ICON)
                ?: extras?.readParcelableCandidate(Notification.EXTRA_LARGE_ICON_BIG),
            mimeType = MimeImagePng,
            maxDimensionPx = MaxLargeIconDimensionPx,
            maxBytes = MaxLargeIconBytes,
        )

        val heroImage = renderAnyAsset(
            source = extras?.readParcelableCandidate(Notification.EXTRA_PICTURE),
            mimeType = MimeImageJpeg,
            maxDimensionPx = MaxHeroImageDimensionPx,
            maxBytes = MaxHeroImageBytes,
        )

        val trimmedAssets = enforceAssetBudget(
            NotificationAssetSnapshot(
                appIcon = appIcon,
                largeIcon = largeIcon,
                heroImage = heroImage,
            ),
        )

        return trimmedAssets.takeIf {
            it.appIcon != null || it.largeIcon != null || it.heroImage != null
        }
    }

    private fun enforceAssetBudget(assets: NotificationAssetSnapshot): NotificationAssetSnapshot {
        val totalBytes = sequenceOf(assets.appIcon, assets.largeIcon, assets.heroImage)
            .filterNotNull()
            .sumOf { it.binaryPayload.size }
        if (totalBytes <= MaxNotificationAssetBytes) {
            return assets
        }

        var remaining = assets.copy()
        if (remaining.largeIcon != null) {
            remaining = remaining.copy(largeIcon = null)
        }
        if (currentAssetBytes(remaining) > MaxNotificationAssetBytes && remaining.heroImage != null) {
            remaining = remaining.copy(heroImage = null)
        }
        if (currentAssetBytes(remaining) > MaxNotificationAssetBytes && remaining.appIcon != null) {
            remaining = remaining.copy(appIcon = null)
        }
        return remaining
    }

    private fun currentAssetBytes(assets: NotificationAssetSnapshot): Int = sequenceOf(
        assets.appIcon,
        assets.largeIcon,
        assets.heroImage,
    ).filterNotNull().sumOf { it.binaryPayload.size }

    private fun renderDrawableAsset(
        drawable: Drawable,
        mimeType: String,
        maxDimensionPx: Int,
        maxBytes: Int,
    ): NotificationImageSnapshot? {
        return renderBitmapAsset(
            bitmap = drawable.toBitmapSafely(),
            mimeType = mimeType,
            maxDimensionPx = maxDimensionPx,
            maxBytes = maxBytes,
        )
    }

    private fun renderAnyAsset(
        source: Any?,
        mimeType: String,
        maxDimensionPx: Int,
        maxBytes: Int,
    ): NotificationImageSnapshot? {
        val bitmap = when (source) {
            is Bitmap -> source
            is Drawable -> source.toBitmapSafely()
            is Icon -> source.loadDrawable(context)?.toBitmapSafely()
            else -> null
        } ?: return null

        return renderBitmapAsset(bitmap, mimeType, maxDimensionPx, maxBytes)
    }

    private fun renderBitmapAsset(
        bitmap: Bitmap,
        mimeType: String,
        maxDimensionPx: Int,
        maxBytes: Int,
    ): NotificationImageSnapshot? {
        var working = bitmap.scaleDown(maxDimensionPx)
        var jpegQuality = 90

        repeat(MaxCompressionAttempts) {
            val compressed = working.compressToByteArray(mimeType, jpegQuality) ?: return null
            if (compressed.size <= maxBytes) {
                return NotificationImageSnapshot(
                    mimeType = mimeType,
                    binaryPayload = compressed,
                    width = working.width,
                    height = working.height,
                )
            }

            if (mimeType == MimeImageJpeg && jpegQuality > 55) {
                jpegQuality -= 15
            } else {
                val nextWidth = max((working.width * 0.82f).roundToInt(), 32)
                val nextHeight = max((working.height * 0.82f).roundToInt(), 32)
                if (nextWidth == working.width && nextHeight == working.height) {
                    return null
                }
                working = Bitmap.createScaledBitmap(working, nextWidth, nextHeight, true)
                jpegQuality = 90
            }
        }

        return null
    }

    private fun Bitmap.scaleDown(maxDimensionPx: Int): Bitmap {
        val longestEdge = max(width, height)
        if (longestEdge <= maxDimensionPx) {
            return this
        }

        val scale = maxDimensionPx.toFloat() / longestEdge.toFloat()
        val targetWidth = max((width * scale).roundToInt(), 1)
        val targetHeight = max((height * scale).roundToInt(), 1)
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }

    private fun Bitmap.compressToByteArray(mimeType: String, jpegQuality: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val format = when (mimeType) {
            MimeImagePng -> Bitmap.CompressFormat.PNG
            MimeImageJpeg -> Bitmap.CompressFormat.JPEG
            else -> return null
        }

        val success = compress(format, jpegQuality, output)
        return output.takeIf { success }?.toByteArray()
    }

    private fun Drawable.toBitmapSafely(): Bitmap {
        val width = if (intrinsicWidth > 0) intrinsicWidth else MaxAppIconDimensionPx
        val height = if (intrinsicHeight > 0) intrinsicHeight else MaxAppIconDimensionPx
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bitmap
    }

    private companion object {
        const val MaxAppIconDimensionPx = 160
        const val MaxLargeIconDimensionPx = 256
        const val MaxHeroImageDimensionPx = 1280
        const val MaxAppIconBytes = 96 * 1024
        const val MaxLargeIconBytes = 160 * 1024
        const val MaxHeroImageBytes = 480 * 1024
        const val MaxNotificationAssetBytes = 700 * 1024
        const val MaxCompressionAttempts = 8
    }
}

private fun android.os.Bundle.readParcelableCandidate(key: String): Any? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(key, Bitmap::class.java) ?: getParcelable(key, Icon::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelable(key)
    }
}
