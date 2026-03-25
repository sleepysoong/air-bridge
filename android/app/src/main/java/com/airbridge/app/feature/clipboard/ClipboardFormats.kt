package com.airbridge.app.feature.clipboard

import com.airbridge.app.feature.common.ClipboardSnapshot
import java.security.MessageDigest

const val MimeTextPlain = "text/plain"
const val MimeTextUriList = "text/uri-list"
const val MimeTextHtml = "text/html"
const val MimeTextRtf = "text/rtf"
const val MimeImagePng = "image/png"
const val MimeImageJpeg = "image/jpeg"

private val BinaryMimeTypes = setOf(MimeTextRtf, MimeImagePng, MimeImageJpeg)

fun isSupportedClipboardMimeType(mimeType: String): Boolean {
    return mimeType in setOf(
        MimeTextPlain,
        MimeTextUriList,
        MimeTextHtml,
        MimeTextRtf,
        MimeImagePng,
        MimeImageJpeg,
    )
}

fun isBinaryClipboardMimeType(mimeType: String): Boolean = mimeType in BinaryMimeTypes

fun extensionForClipboardMimeType(mimeType: String): String {
    return when (mimeType) {
        MimeTextRtf -> "rtf"
        MimeImagePng -> "png"
        MimeImageJpeg -> "jpg"
        else -> "bin"
    }
}

fun buildClipboardFingerprint(
    mimeType: String,
    plainText: String?,
    htmlText: String?,
    uriList: List<String>,
    binaryPayload: ByteArray?,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(mimeType.toByteArray(Charsets.UTF_8))
    plainText?.let { digest.update(it.toByteArray(Charsets.UTF_8)) }
    htmlText?.let { digest.update(it.toByteArray(Charsets.UTF_8)) }
    uriList.forEach { uri -> digest.update(uri.toByteArray(Charsets.UTF_8)) }
    binaryPayload?.let(digest::update)
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}

fun ClipboardSnapshot.byteSize(): Int {
    val textBytes = plainText?.toByteArray(Charsets.UTF_8)?.size ?: 0
    val htmlBytes = htmlText?.toByteArray(Charsets.UTF_8)?.size ?: 0
    val uriBytes = uriList.sumOf { it.toByteArray(Charsets.UTF_8).size }
    val binaryBytes = binaryPayload?.size ?: 0
    return textBytes + htmlBytes + uriBytes + binaryBytes
}

