package com.airbridge.app.feature.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.airbridge.app.feature.common.ClipboardReadOutcome
import com.airbridge.app.feature.common.ClipboardSnapshot
import com.airbridge.app.feature.common.ClipboardTransferOrigin
import com.airbridge.app.feature.common.MaxClipboardPayloadBytes
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ClipboardReadGateway {
    suspend fun readCurrentClipboard(origin: ClipboardTransferOrigin): ClipboardReadOutcome
}

class AndroidClipboardReadGateway(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC(),
    private val maxPayloadBytes: Int = MaxClipboardPayloadBytes,
) : ClipboardReadGateway {
    private val clipboardManager: ClipboardManager =
        context.getSystemService(ClipboardManager::class.java)

    override suspend fun readCurrentClipboard(origin: ClipboardTransferOrigin): ClipboardReadOutcome {
        return withContext(ioDispatcher) {
            runCatching {
                val clip = clipboardManager.primaryClip ?: return@runCatching ClipboardReadOutcome.Empty
                val description = clipboardManager.primaryClipDescription
                    ?: return@runCatching ClipboardReadOutcome.Empty

                buildSnapshot(clip, description)
            }.getOrElse { error ->
                ClipboardReadOutcome.Failure(
                    message = "클립보드를 읽지 못했어요",
                    cause = error,
                )
            }
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
    contentResolver: ContentResolver,
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

