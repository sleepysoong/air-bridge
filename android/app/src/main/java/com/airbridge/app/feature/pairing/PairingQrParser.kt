package com.airbridge.app.feature.pairing

import android.net.Uri
import com.airbridge.app.domain.PairingQrPayload
import kotlinx.serialization.json.Json

class PairingQrParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parse(rawValue: String): PairingQrPayload {
        val trimmed = rawValue.trim()
        require(trimmed.isNotEmpty()) { "QR payload가 비어 있어요." }

        return if (trimmed.startsWith("{")) {
            json.decodeFromString(PairingQrPayload.serializer(), trimmed)
        } else {
            parseUri(trimmed)
        }.also { payload ->
            require(payload.relayBaseUrl.isNotBlank()) { "relay_base_url 값이 필요해요." }
            require(payload.pairingSessionId.isNotBlank()) { "pairing_session_id 값이 필요해요." }
            require(payload.pairingSecret.isNotBlank()) { "pairing_secret 값이 필요해요." }
            require(payload.initiatorPublicKey.isNotBlank()) { "initiator_public_key 값이 필요해요." }
        }
    }

    private fun parseUri(rawValue: String): PairingQrPayload {
        val uri = Uri.parse(rawValue)
        val relayBaseUrl = uri.getQueryParameter("relay_base_url")
            ?: uri.getQueryParameter("relay_url")
            ?: uri.getQueryParameter("relayBaseUrl")
            ?: ""
        val pairingSessionId = uri.getQueryParameter("pairing_session_id")
            ?: uri.getQueryParameter("session_id")
            ?: uri.getQueryParameter("pairingSessionId")
            ?: ""
        val pairingSecret = uri.getQueryParameter("pairing_secret")
            ?: uri.getQueryParameter("pairingSecret")
            ?: ""
        val initiatorPublicKey = uri.getQueryParameter("initiator_public_key")
            ?: uri.getQueryParameter("public_key")
            ?: uri.getQueryParameter("initiatorPublicKey")
            ?: ""

        return PairingQrPayload(
            relayBaseUrl = relayBaseUrl,
            pairingSessionId = pairingSessionId,
            pairingSecret = pairingSecret,
            initiatorDeviceId = uri.getQueryParameter("initiator_device_id")
                ?: uri.getQueryParameter("initiatorDeviceId"),
            initiatorName = uri.getQueryParameter("initiator_name")
                ?: uri.getQueryParameter("initiatorName"),
            initiatorPublicKey = initiatorPublicKey,
        )
    }
}

