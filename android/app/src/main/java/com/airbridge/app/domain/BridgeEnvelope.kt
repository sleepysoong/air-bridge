package com.airbridge.app.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class BridgeChannel {
    @SerialName("clipboard")
    CLIPBOARD,

    @SerialName("notification")
    NOTIFICATION,
}

@Serializable
data class EnvelopeHeader(
    @SerialName("schema_version")
    val schemaVersion: Int = 1,
    val channel: BridgeChannel,
    @SerialName("content_type")
    val contentType: String,
    @SerialName("sender_device_id")
    val senderDeviceId: String,
    @SerialName("recipient_device_id")
    val recipientDeviceId: String,
    @SerialName("pairing_session_id")
    val pairingSessionId: String,
)

data class EncryptedEnvelope(
    val channel: BridgeChannel,
    val contentType: String,
    val nonce: ByteArray,
    val headerAad: ByteArray,
    val ciphertext: ByteArray,
)

data class IncomingEncryptedEnvelope(
    val envelopeId: String,
    val senderDeviceId: String,
    val channel: BridgeChannel,
    val contentType: String,
    val nonce: ByteArray,
    val headerAad: ByteArray,
    val ciphertext: ByteArray,
)

@Serializable
data class PersistedOutboundEnvelope(
    val queueId: String = UUID.randomUUID().toString(),
    @SerialName("recipient_device_id")
    val recipientDeviceId: String,
    val channel: BridgeChannel,
    @SerialName("content_type")
    val contentType: String,
    val nonce: String,
    @SerialName("header_aad")
    val headerAad: String,
    val ciphertext: String,
    @SerialName("created_at")
    val createdAt: String,
)
