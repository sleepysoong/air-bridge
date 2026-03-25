package com.airbridge.app.feature.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import com.airbridge.app.feature.common.ClipboardApplyOutcome
import com.airbridge.app.feature.common.ClipboardSnapshot
import com.airbridge.app.feature.common.MaxClipboardPayloadBytes
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ClipboardApplyGateway {
    suspend fun apply(snapshot: ClipboardSnapshot): ClipboardApplyOutcome
}

class AndroidClipboardApplyGateway(
    private val context: Context,
    private val providerAuthority: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val artifactStore: ClipboardArtifactStore = CacheClipboardArtifactStore(context, providerAuthority),
) : ClipboardApplyGateway {
    private val clipboardManager: ClipboardManager =
        context.getSystemService(ClipboardManager::class.java)

    override suspend fun apply(snapshot: ClipboardSnapshot): ClipboardApplyOutcome {
        return withContext(ioDispatcher) {
            runCatching {
                if (snapshot.binaryPayload != null && snapshot.binaryPayload.size > MaxClipboardPayloadBytes) {
                    return@runCatching ClipboardApplyOutcome.Failure("클립보드 payload가 20MB 제한을 넘었어요")
                }

                val clipData = createClipData(snapshot)
                clipboardManager.setPrimaryClip(clipData)
                ClipboardApplyOutcome.Applied
            }.getOrElse { error ->
                ClipboardApplyOutcome.Failure(
                    message = "수신한 클립보드를 적용하지 못했어요",
                    cause = error,
                )
            }
        }
    }

    private suspend fun createClipData(snapshot: ClipboardSnapshot): ClipData {
        val label = snapshot.label ?: "air-bridge"
        return when (snapshot.mimeType) {
            MimeTextPlain -> ClipData.newPlainText(label, snapshot.plainText.orEmpty())
            MimeTextHtml -> {
                val htmlText = snapshot.htmlText
                    ?: throw IllegalArgumentException("HTML 클립보드에 htmlText가 없어요")
                val plainText = snapshot.plainText ?: HtmlCompat.fromHtml(
                    htmlText,
                    HtmlCompat.FROM_HTML_MODE_LEGACY,
                ).toString()
                ClipData.newHtmlText(label, plainText, htmlText)
            }
            MimeTextUriList -> buildUriClipData(label, snapshot.uriList)
            MimeTextRtf, MimeImagePng, MimeImageJpeg -> buildBinaryClipData(label, snapshot)
            else -> throw IllegalArgumentException("지원하지 않는 MIME type이에요: ${snapshot.mimeType}")
        }
    }

    private fun buildUriClipData(label: String, uriList: List<String>): ClipData {
        require(uriList.isNotEmpty()) { "URI 클립보드가 비어 있어요" }
        val firstUri = uriList.first().toUri()
        val clipData = ClipData(label, arrayOf(ClipDescription.MIMETYPE_TEXT_URILIST), ClipData.Item(firstUri))
        uriList.drop(1).forEach { rawUri ->
            clipData.addItem(ClipData.Item(rawUri.toUri()))
        }
        return clipData
    }

    private suspend fun buildBinaryClipData(label: String, snapshot: ClipboardSnapshot): ClipData {
        val payload = snapshot.binaryPayload
            ?: throw IllegalArgumentException("바이너리 클립보드에 payload가 없어요")
        val contentUri = artifactStore.persist(snapshot.fingerprint, snapshot.mimeType, payload)
        return ClipData(
            label,
            arrayOf(snapshot.mimeType),
            ClipData.Item(contentUri),
        )
    }
}

interface ClipboardArtifactStore {
    suspend fun persist(fingerprint: String, mimeType: String, payload: ByteArray): Uri
}

class CacheClipboardArtifactStore(
    private val context: Context,
    private val providerAuthority: String,
) : ClipboardArtifactStore {
    override suspend fun persist(fingerprint: String, mimeType: String, payload: ByteArray): Uri {
        val fileName = "$fingerprint.${extensionForClipboardMimeType(mimeType)}"
        val targetDirectory = File(context.cacheDir, AirBridgeClipboardContentProvider.RootDirectoryName)
        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs()
        }

        val targetFile = File(targetDirectory, fileName)
        if (!targetFile.exists()) {
            targetFile.writeBytes(payload)
        }

        return AirBridgeClipboardContentProvider.buildUri(providerAuthority, fileName)
    }
}

class AirBridgeClipboardContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String {
        return when (resolveFileName(uri).substringAfterLast('.', missingDelimiterValue = "")) {
            "png" -> MimeImagePng
            "jpg", "jpeg" -> MimeImageJpeg
            "rtf" -> MimeTextRtf
            else -> "application/octet-stream"
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("읽기 전용 URI예요")
        }

        val file = File(providerContext().cacheDir, "${RootDirectoryName}/${resolveFileName(uri)}")
        if (!file.exists()) {
            throw FileNotFoundException("클립보드 아티팩트를 찾을 수 없어요")
        }

        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun resolveFileName(uri: Uri): String {
        val fileName = uri.lastPathSegment ?: throw FileNotFoundException("URI 파일명이 없어요")
        if (!fileName.matches(FileNameRegex)) {
            throw FileNotFoundException("허용되지 않는 URI 파일명이에요")
        }
        return fileName
    }

    private fun providerContext(): Context {
        return context ?: throw IOException("ContentProvider context가 없어요")
    }

    companion object {
        internal const val RootDirectoryName = "airbridge-clipboard"
        private val FileNameRegex = Regex("[A-Za-z0-9._-]+")

        fun buildUri(authority: String, fileName: String): Uri {
            require(fileName.matches(FileNameRegex)) { "허용되지 않는 파일명이에요" }
            return Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendPath(RootDirectoryName)
                .appendPath(fileName)
                .build()
        }
    }
}
