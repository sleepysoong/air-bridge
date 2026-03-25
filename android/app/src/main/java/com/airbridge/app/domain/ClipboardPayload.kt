package com.airbridge.app.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.util.Base64

const val MaxClipboardPayloadBytes = 20 * 1024 * 1024

@Serializable
data class ClipboardPayload(
    @SerialName("schema_version")
    val schemaVersion: Int = 1,
    @SerialName("mime_type")
    val mimeType: String,
    val label: String? = null,
    @SerialName("text_value")
    val textValue: String? = null,
    @SerialName("html_value")
    val htmlValue: String? = null,
    @SerialName("uri_list")
    val uriList: List<String> = emptyList(),
    @SerialName("binary_base64")
    val binaryBase64: String? = null,
) {
    fun estimatedPayloadBytes(): Int = when {
        binaryBase64 != null -> Base64.getDecoder().decode(binaryBase64).size
        htmlValue != null -> htmlValue.toByteArray(StandardCharsets.UTF_8).size
        textValue != null -> textValue.toByteArray(StandardCharsets.UTF_8).size
        uriList.isNotEmpty() -> uriList.joinToString("\n").toByteArray(StandardCharsets.UTF_8).size
        else -> 0
    }

    fun requireSupportedSize() {
        check(estimatedPayloadBytes() <= MaxClipboardPayloadBytes) {
            "클립보드 payload가 20MB 제한을 초과했어요."
        }
    }
}

