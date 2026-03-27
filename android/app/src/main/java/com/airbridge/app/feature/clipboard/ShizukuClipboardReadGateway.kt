package com.airbridge.app.feature.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import com.airbridge.app.BuildConfig
import com.airbridge.app.feature.common.ClipboardReadOutcome
import com.airbridge.app.feature.common.ClipboardSnapshot
import com.airbridge.app.feature.common.ClipboardTransferOrigin
import com.airbridge.app.feature.common.MaxClipboardPayloadBytes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.resume

class ShizukuClipboardReadGateway(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC(),
    private val maxPayloadBytes: Int = MaxClipboardPayloadBytes,
) : ClipboardReadGateway {

    override suspend fun readCurrentClipboard(origin: ClipboardTransferOrigin): ClipboardReadOutcome {
        return withContext(ioDispatcher) {
            runCatching {
                if (!Shizuku.pingBinder()) {
                    return@runCatching ClipboardReadOutcome.Failure("Shizuku가 실행 중이지 않아요")
                }
                
                if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    return@runCatching ClipboardReadOutcome.Failure("Shizuku 권한이 없어요")
                }

                val clipData = fetchClipboardViaShizuku() ?: return@runCatching ClipboardReadOutcome.Empty
                val description = clipData.description ?: return@runCatching ClipboardReadOutcome.Empty

                buildSnapshot(clipData, description)
            }.getOrElse { error ->
                Log.e("ShizukuClipboard", "Error reading clipboard via Shizuku", error)
                ClipboardReadOutcome.Failure(
                    message = "Shizuku를 통해 클립보드를 읽지 못했어요",
                    cause = error,
                )
            }
        }
    }

    private suspend fun fetchClipboardViaShizuku(): ClipData? = suspendCancellableCoroutine { continuation ->
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuClipboardService::class.java.name)
        ).processNameSuffix("clipboard_service").debuggable(BuildConfig.DEBUG)

        var isResumed = false
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: android.os.IBinder?) {
                if (isResumed) return
                isResumed = true
                try {
                    val shizukuClipboard = IShizukuClipboard.Stub.asInterface(service)
                    val clip = shizukuClipboard.clipboard
                    continuation.resume(clip)
                } catch (e: Exception) {
                    continuation.resume(null)
                } finally {
                    Shizuku.unbindUserService(args, this, true)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!isResumed) {
                    isResumed = true
                    continuation.resume(null)
                }
            }
        }

        try {
            Shizuku.bindUserService(args, connection)
        } catch (e: Exception) {
            if (!isResumed) {
                isResumed = true
                continuation.resume(null)
            }
        }

        continuation.invokeOnCancellation {
            Shizuku.unbindUserService(args, connection, true)
        }
    }

    private fun buildSnapshot(
        clipData: ClipData,
        description: ClipDescription,
    ): ClipboardReadOutcome {
        val label = description.label?.toString()
        val capturedAt = Instant.now(clock)
        val firstItem = clipData.getItemAt(0)

        if (description.hasMimeType(MimeTextHtml) || firstItem.htmlText != null) {
            val htmlText = firstItem.htmlText?.toString()
            val plainText = firstItem.text?.toString()
            if (htmlText.isNullOrBlank()) {
                return ClipboardReadOutcome.Unsupported("HTML 클립보드에 본문이 없어요")
            }

            return ClipboardReadOutcome.Success(
                ClipboardSnapshot(
                    mimeType = MimeTextHtml,
                    label = label,
                    plainText = plainText,
                    htmlText = htmlText,
                    fingerprint = buildClipboardFingerprint(
                        mimeType = MimeTextHtml,
                        plainText = plainText,
                        htmlText = htmlText,
                        uriList = emptyList(),
                        binaryPayload = null,
                    ),
                    capturedAt = capturedAt,
                ),
            )
        }

        supportedBinaryMimeType(description, firstItem.uri)?.let { mimeType ->
            val sourceUri = firstItem.uri
                ?: return ClipboardReadOutcome.Unsupported("바이너리 클립보드 URI가 없어요")
            val bytes = readBinaryPayload(sourceUri, mimeType)
            return ClipboardReadOutcome.Success(
                ClipboardSnapshot(
                    mimeType = mimeType,
                    label = label,
                    binaryPayload = bytes,
                    fingerprint = buildClipboardFingerprint(
                        mimeType = mimeType,
                        plainText = null,
                        htmlText = null,
                        uriList = emptyList(),
                        binaryPayload = bytes,
                    ),
                    capturedAt = capturedAt,
                ),
            )
        }

        if (description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) || clipContainsUris(clipData)) {
            val uriList = buildList {
                repeat(clipData.itemCount) { index ->
                    clipData.getItemAt(index).uri?.toString()?.let(::add)
                }
            }

            if (uriList.isNotEmpty()) {
                return ClipboardReadOutcome.Success(
                    ClipboardSnapshot(
                        mimeType = MimeTextUriList,
                        label = label,
                        uriList = uriList,
                        fingerprint = buildClipboardFingerprint(
                            mimeType = MimeTextUriList,
                            plainText = null,
                            htmlText = null,
                            uriList = uriList,
                            binaryPayload = null,
                        ),
                        capturedAt = capturedAt,
                    ),
                )
            }
        }

        val plainText = firstItem.text?.toString()
        if (!plainText.isNullOrBlank()) {
            return ClipboardReadOutcome.Success(
                ClipboardSnapshot(
                    mimeType = MimeTextPlain,
                    label = label,
                    plainText = plainText,
                    fingerprint = buildClipboardFingerprint(
                        mimeType = MimeTextPlain,
                        plainText = plainText,
                        htmlText = null,
                        uriList = emptyList(),
                        binaryPayload = null,
                    ),
                    capturedAt = capturedAt,
                ),
            )
        }

        return ClipboardReadOutcome.Unsupported("지원하는 형식의 클립보드가 아니에요")
    }

    private fun clipContainsUris(clipData: ClipData): Boolean {
        return (0 until clipData.itemCount).any { index -> clipData.getItemAt(index).uri != null }
    }

    private fun supportedBinaryMimeType(
        description: ClipDescription,
        itemUri: Uri?,
    ): String? {
        listOf(MimeTextRtf, MimeImagePng, MimeImageJpeg).forEach { mimeType ->
            if (description.hasMimeType(mimeType)) {
                return mimeType
            }
        }

        if (itemUri == null) {
            return null
        }

        val resolverMimeType = context.contentResolver.getType(itemUri)
        return resolverMimeType?.takeIf(::isBinaryClipboardMimeType)
    }

    private fun readBinaryPayload(uri: Uri, mimeType: String): ByteArray {
        val bytes = readBytesLimited(context.contentResolver, uri, maxPayloadBytes)
        if (bytes.isEmpty()) {
            throw IOException("$mimeType 클립보드 본문이 비어 있어요")
        }
        return bytes
    }
}

private fun readBytesLimited(
    contentResolver: android.content.ContentResolver,
    uri: Uri,
    maxBytes: Int,
): ByteArray {
    contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalBytes = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) {
                break
            }
            totalBytes += read
            if (totalBytes > maxBytes) {
                throw IOException("클립보드 payload가 ${maxBytes / (1024 * 1024)}MB 제한을 넘었어요")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    throw IOException("클립보드 URI를 열 수 없어요: $uri")
}
