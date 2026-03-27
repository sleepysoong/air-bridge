package com.airbridge.app.data.relay

import com.airbridge.app.domain.PairingJoinResult
import com.airbridge.app.domain.PairingSessionSnapshot
import com.airbridge.app.domain.PairingSessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class RelayHttpClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    suspend fun findWorkingRelayUrl(
        relayAddresses: List<String>,
    ): String = withContext(Dispatchers.IO) {
        require(relayAddresses.isNotEmpty()) { "relay 주소 목록이 비어 있어요" }

        for (address in relayAddresses) {
            val success = tryConnectRelay(address)
            if (success) {
                return@withContext address
            }
        }

        throw RelayApiException(
            code = "connection_failed",
            message = "어떤 relay 주소에도 연결할 수 없어요. 주소 목록: $relayAddresses",
            statusCode = 0,
        )
    }

    private suspend fun tryConnectRelay(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("${baseUrl.normalizedBaseUrl()}/api/v1/server/info")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        }.getOrDefault(false)
    }

    suspend fun joinPairingSession(
        relayBaseUrl: String,
        pairingSessionId: String,
        pairingSecret: String,
        deviceName: String,
        publicKeyBase64: String,
    ): PairingJoinResult = withContext(Dispatchers.IO) {
        require(deviceName.length <= RelayServerLimits.MAX_DEVICE_NAME_LENGTH) {
            "기기 이름은 ${RelayServerLimits.MAX_DEVICE_NAME_LENGTH}자를 넘을 수 없어요 (현재: ${deviceName.length}자)"
        }
        require(pairingSecret.length <= RelayServerLimits.MAX_PAIRING_SECRET_LENGTH) {
            "페어링 비밀값은 ${RelayServerLimits.MAX_PAIRING_SECRET_LENGTH}자를 넘을 수 없어요 (현재: ${pairingSecret.length}자)"
        }
        
        val request = JoinPairingSessionRequest(
            pairingSecret = pairingSecret,
            deviceName = deviceName,
            platform = "android",
            publicKey = publicKeyBase64,
        )
        val response = executeJson<JoinPairingSessionResponse>(
            url = "${relayBaseUrl.normalizedBaseUrl()}/api/v1/pairing/sessions/$pairingSessionId/join",
            method = "POST",
            body = request,
            serializer = JoinPairingSessionResponse.serializer(),
        )
        response.toDomain()
    }

    suspend fun lookupPairingSession(
        relayBaseUrl: String,
        pairingSessionId: String,
        pairingSecret: String,
    ): PairingSessionSnapshot = withContext(Dispatchers.IO) {
        require(pairingSecret.length <= RelayServerLimits.MAX_PAIRING_SECRET_LENGTH) {
            "페어링 비밀값은 ${RelayServerLimits.MAX_PAIRING_SECRET_LENGTH}자를 넘을 수 없어요 (현재: ${pairingSecret.length}자)"
        }
        
        val response = executeJson<LookupPairingSessionResponse>(
            url = "${relayBaseUrl.normalizedBaseUrl()}/api/v1/pairing/sessions/$pairingSessionId/lookup",
            method = "POST",
            body = LookupPairingSessionRequest(pairingSecret = pairingSecret),
            serializer = LookupPairingSessionResponse.serializer(),
        )
        response.toDomain()
    }

    suspend fun completePairingSession(
        relayBaseUrl: String,
        pairingSessionId: String,
        pairingSecret: String,
    ): CompletePairingSessionResponse = withContext(Dispatchers.IO) {
        require(pairingSecret.length <= RelayServerLimits.MAX_PAIRING_SECRET_LENGTH) {
            "페어링 비밀값은 ${RelayServerLimits.MAX_PAIRING_SECRET_LENGTH}자를 넘을 수 없어요 (현재: ${pairingSecret.length}자)"
        }
        
        executeJson(
            url = "${relayBaseUrl.normalizedBaseUrl()}/api/v1/pairing/sessions/$pairingSessionId/complete",
            method = "POST",
            body = CompletePairingSessionRequest(pairingSecret = pairingSecret),
            serializer = CompletePairingSessionResponse.serializer(),
        )
    }

    private suspend fun <T> executeJson(
        url: String,
        method: String,
        body: Any?,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(url)
        val requestBody = body?.let {
            json.encodeToString(
                serializer = serializerForBody(it),
                value = it,
            ).toRequestBody(JsonMediaType)
        }

        when (method) {
            "POST" -> requestBuilder.post(requireNotNull(requestBody))
            "GET" -> requestBuilder.get()
            else -> error("지원하지 않는 HTTP 메서드예요: $method")
        }

        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val errorResponse = runCatching {
                    json.decodeFromString(RelayErrorEnvelope.serializer(), rawBody)
                }.getOrNull()
                throw RelayApiException(
                    code = errorResponse?.error?.code ?: "http_${response.code}",
                    message = errorResponse?.error?.message ?: "relay 서버 요청에 실패했어요",
                    statusCode = response.code,
                )
            }

            return@use json.decodeFromString(serializer, rawBody)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> serializerForBody(value: T): kotlinx.serialization.KSerializer<T> {
        return when (value) {
            is JoinPairingSessionRequest -> JoinPairingSessionRequest.serializer() as kotlinx.serialization.KSerializer<T>
            is LookupPairingSessionRequest -> LookupPairingSessionRequest.serializer() as kotlinx.serialization.KSerializer<T>
            is CompletePairingSessionRequest -> CompletePairingSessionRequest.serializer() as kotlinx.serialization.KSerializer<T>
            else -> error("지원하지 않는 요청 본문 타입이에요: ${value::class.java.name}")
        }
    }

    companion object {
        private val JsonMediaType = "application/json; charset=utf-8".toMediaType()
    }
}

class RelayApiException(
    val code: String,
    override val message: String,
    val statusCode: Int,
) : Exception(message)

private fun String.normalizedBaseUrl(): String = trim().removeSuffix("/")

@Serializable
private data class JoinPairingSessionRequest(
    @SerialName("pairing_secret")
    val pairingSecret: String,
    @SerialName("device_name")
    val deviceName: String,
    val platform: String,
    @SerialName("public_key")
    val publicKey: String,
)

@Serializable
private data class JoinPairingSessionResponse(
    @SerialName("pairing_session_id")
    val pairingSessionId: String,
    @SerialName("joiner_device_id")
    val joinerDeviceId: String,
    @SerialName("joiner_relay_token")
    val joinerRelayToken: String,
    @SerialName("initiator_device_id")
    val initiatorDeviceId: String,
    @SerialName("initiator_public_key")
    val initiatorPublicKey: String,
    @SerialName("expires_at")
    val expiresAt: String,
) {
    fun toDomain(): PairingJoinResult = PairingJoinResult(
        pairingSessionId = pairingSessionId,
        joinerDeviceId = joinerDeviceId,
        joinerRelayToken = joinerRelayToken,
        initiatorDeviceId = initiatorDeviceId,
        initiatorPublicKey = initiatorPublicKey,
        expiresAt = expiresAt,
    )
}

@Serializable
private data class LookupPairingSessionRequest(
    @SerialName("pairing_secret")
    val pairingSecret: String,
)

@Serializable
private data class LookupPairingSessionResponse(
    @SerialName("pairing_session_id")
    val pairingSessionId: String,
    val state: String,
    @SerialName("initiator_device_id")
    val initiatorDeviceId: String,
    @SerialName("initiator_name")
    val initiatorName: String,
    @SerialName("initiator_platform")
    val initiatorPlatform: String,
    @SerialName("initiator_public_key")
    val initiatorPublicKey: String,
    @SerialName("joiner_device_id")
    val joinerDeviceId: String? = null,
    @SerialName("joiner_name")
    val joinerName: String? = null,
    @SerialName("joiner_platform")
    val joinerPlatform: String? = null,
    @SerialName("joiner_public_key")
    val joinerPublicKey: String? = null,
    @SerialName("expires_at")
    val expiresAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
    @SerialName("completed_at")
    val completedAt: String? = null,
) {
    fun toDomain(): PairingSessionSnapshot = PairingSessionSnapshot(
        pairingSessionId = pairingSessionId,
        state = PairingSessionState.fromWire(state),
        initiatorDeviceId = initiatorDeviceId,
        initiatorName = initiatorName,
        initiatorPlatform = initiatorPlatform,
        initiatorPublicKey = initiatorPublicKey,
        joinerDeviceId = joinerDeviceId,
        joinerName = joinerName,
        joinerPlatform = joinerPlatform,
        joinerPublicKey = joinerPublicKey,
        expiresAt = expiresAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
    )
}

@Serializable
private data class CompletePairingSessionRequest(
    @SerialName("pairing_secret")
    val pairingSecret: String,
)

@Serializable
data class CompletePairingSessionResponse(
    @SerialName("pairing_session_id")
    val pairingSessionId: String,
    val state: String,
    @SerialName("completed_at")
    val completedAt: String,
)

@Serializable
private data class RelayErrorEnvelope(
    val error: RelayErrorBody,
)

@Serializable
private data class RelayErrorBody(
    val code: String,
    val message: String,
)

