package com.airbridge.app.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PairingQrPayload(
    val version: Int = 1,
    @SerialName("relay_addresses")
    val relayAddresses: List<String>,
    @SerialName("pairing_session_id")
    val pairingSessionId: String,
    @SerialName("pairing_secret")
    val pairingSecret: String,
    @SerialName("initiator_device_id")
    val initiatorDeviceId: String? = null,
    @SerialName("initiator_name")
    val initiatorName: String? = null,
    @SerialName("initiator_public_key")
    val initiatorPublicKey: String,
)

enum class PairingSessionState {
    PENDING,
    COMPLETED,
    UNKNOWN;

    companion object {
        fun fromWire(value: String): PairingSessionState = when (value.lowercase()) {
            "pending" -> PENDING
            "ready", "completed" -> COMPLETED
            else -> UNKNOWN
        }
    }
}

data class PairingSessionSnapshot(
    val pairingSessionId: String,
    val state: PairingSessionState,
    val initiatorDeviceId: String,
    val initiatorName: String,
    val initiatorPlatform: String,
    val initiatorPublicKey: String,
    val joinerDeviceId: String?,
    val joinerName: String?,
    val joinerPlatform: String?,
    val joinerPublicKey: String?,
    val expiresAt: String,
    val updatedAt: String,
    val completedAt: String?,
)

data class PairingJoinResult(
    val pairingSessionId: String,
    val joinerDeviceId: String,
    val joinerRelayToken: String,
    val initiatorDeviceId: String,
    val initiatorPublicKey: String,
    val expiresAt: String,
)

@Serializable
data class StoredDeviceIdentity(
    val deviceName: String,
    val publicKeyBase64: String,
    val privateKeyBase64: String,
)

@Serializable
data class StoredRelayCredentials(
    val relayBaseUrl: String,
    val pairingSessionId: String,
    val pairingSecret: String,
    val localDeviceId: String,
    val localRelayToken: String,
    val localDeviceName: String,
    val peerDeviceId: String,
    val peerPublicKeyBase64: String,
    val initiatorDeviceId: String,
    val initiatorName: String,
    val joinedAt: String,
    val completedAt: String?,
)
