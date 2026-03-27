package com.airbridge.app.feature.pairing

import com.airbridge.app.domain.PairingQrPayload
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
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
            require(payload.relayAddresses.isNotEmpty()) { "relay_addresses 값이 필요해요." }
            require(payload.pairingSessionId.isNotBlank()) { "pairing_session_id 값이 필요해요." }
            require(payload.pairingSecret.isNotBlank()) { "pairing_secret 값이 필요해요." }
            require(payload.initiatorPublicKey.isNotBlank()) { "initiator_public_key 값이 필요해요." }
        }
    }

    private fun parseUri(rawValue: String): PairingQrPayload {
        val queryParameters = parseQueryParameters(rawValue)
        val relayAddresses = queryParameters["relay_addresses"]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: listOfNotNull(
                queryParameters["relay_base_url"],
                queryParameters["relay_url"],
                queryParameters["relayBaseUrl"],
            ).filter { it.isNotBlank() }
        val pairingSessionId = queryParameters["pairing_session_id"]
            ?: queryParameters["session_id"]
            ?: queryParameters["pairingSessionId"]
            ?: ""
        val pairingSecret = queryParameters["pairing_secret"]
            ?: queryParameters["pairingSecret"]
            ?: ""
        val initiatorPublicKey = queryParameters["initiator_public_key"]
            ?: queryParameters["public_key"]
            ?: queryParameters["initiatorPublicKey"]
            ?: ""

        return PairingQrPayload(
            relayAddresses = relayAddresses,
            pairingSessionId = pairingSessionId,
            pairingSecret = pairingSecret,
            initiatorDeviceId = queryParameters["initiator_device_id"]
                ?: queryParameters["initiatorDeviceId"],
            initiatorName = queryParameters["initiator_name"]
                ?: queryParameters["initiatorName"],
            initiatorPublicKey = initiatorPublicKey,
        )
    }

    private fun parseQueryParameters(rawValue: String): Map<String, String> {
        val rawQuery = runCatching { URI(rawValue).rawQuery }
            .getOrNull()
            ?: rawValue.substringAfter('?', "")
        if (rawQuery.isBlank()) {
            return emptyMap()
        }

        val parameters = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { entry ->
            if (entry.isBlank()) {
                return@forEach
            }

            val parts = entry.split('=', limit = 2)
            val key = decodeQueryComponent(parts[0])
            if (key in parameters) {
                return@forEach
            }

            val value = parts.getOrNull(1)?.let(::decodeQueryComponent) ?: ""
            parameters[key] = value
        }
        return parameters
    }

    private fun decodeQueryComponent(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8)
    }
}
