package com.airbridge.app.data.relay

import com.airbridge.app.data.crypto.decodeBase64
import com.airbridge.app.data.crypto.encodeBase64
import com.airbridge.app.domain.BridgeChannel
import com.airbridge.app.domain.EncryptedEnvelope
import com.airbridge.app.domain.IncomingEncryptedEnvelope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface RelayClientMessage

@Serializable
data class RelayPingMessage(
    val type: String = "ping",
) : RelayClientMessage

@Serializable
data class RelaySendEnvelopeMessage(
    val type: String = "send_envelope",
    @SerialName("recipient_device_id")
    val recipientDeviceId: String,
    val channel: String,
    @SerialName("content_type")
    val contentType: String,
    val nonce: String,
    @SerialName("header_aad")
    val headerAad: String,
    val ciphertext: String,
) : RelayClientMessage

@Serializable
data class RelayAckEnvelopeMessage(
    val type: String = "ack_envelope",
    @SerialName("envelope_id")
    val envelopeId: String,
) : RelayClientMessage

@Serializable
data class RelayServerMessage(
    val type: String,
    val code: String? = null,
    val message: String? = null,
    @SerialName("device_id")
    val deviceId: String? = null,
    @SerialName("peer_device_id")
    val peerDeviceId: String? = null,
    @SerialName("envelope_id")
    val envelopeId: String? = null,
    @SerialName("sender_device_id")
    val senderDeviceId: String? = null,
    val channel: String? = null,
    @SerialName("content_type")
    val contentType: String? = null,
    val nonce: String? = null,
    @SerialName("header_aad")
    val headerAad: String? = null,
    val ciphertext: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("expires_at")
    val expiresAt: String? = null,
)

sealed interface RelayServerEvent {
    data class Connected(val deviceId: String, val peerDeviceId: String) : RelayServerEvent
    data object Pong : RelayServerEvent
    data class Envelope(val envelope: IncomingEncryptedEnvelope) : RelayServerEvent
    data class Error(val code: String, val message: String) : RelayServerEvent
    data class Closed(val code: Int, val reason: String) : RelayServerEvent
    data class Failure(val throwable: Throwable) : RelayServerEvent
}

fun EncryptedEnvelope.toWire(recipientDeviceId: String): RelaySendEnvelopeMessage {
    return RelaySendEnvelopeMessage(
        recipientDeviceId = recipientDeviceId,
        channel = channel.wireValue(),
        contentType = contentType,
        nonce = nonce.encodeBase64(),
        headerAad = headerAad.encodeBase64(),
        ciphertext = ciphertext.encodeBase64(),
    )
}

fun RelayServerMessage.toEvent(): RelayServerEvent = when (type) {
    "connected" -> RelayServerEvent.Connected(
        deviceId = requireNotNull(deviceId),
        peerDeviceId = requireNotNull(peerDeviceId),
    )
    "pong" -> RelayServerEvent.Pong
    "envelope" -> RelayServerEvent.Envelope(
        IncomingEncryptedEnvelope(
            envelopeId = requireNotNull(envelopeId),
            senderDeviceId = requireNotNull(senderDeviceId),
            channel = BridgeChannel.valueOf(requireNotNull(channel).uppercase()),
            contentType = requireNotNull(contentType),
            nonce = requireNotNull(nonce).decodeBase64(),
            headerAad = requireNotNull(headerAad).decodeBase64(),
            ciphertext = requireNotNull(ciphertext).decodeBase64(),
        ),
    )
    "error" -> RelayServerEvent.Error(
        code = code ?: "error",
        message = message ?: "relay websocket 오류가 발생했어요",
    )
    else -> RelayServerEvent.Error(
        code = "unknown_message_type",
        message = "알 수 없는 서버 메시지를 받았어요: $type",
    )
}

private fun BridgeChannel.wireValue(): String = when (this) {
    BridgeChannel.CLIPBOARD -> "clipboard"
    BridgeChannel.NOTIFICATION -> "notification"
}

