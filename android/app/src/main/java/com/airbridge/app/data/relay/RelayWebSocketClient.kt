package com.airbridge.app.data.relay

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class RelayWebSocketClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    fun connect(
        relayBaseUrl: String,
        deviceId: String,
        relayToken: String,
    ): RelaySocketConnection {
        val events = Channel<RelayServerEvent>(capacity = Channel.BUFFERED)
        val request = Request.Builder()
            .url(buildWebSocketUrl(relayBaseUrl, deviceId, relayToken))
            .build()

        lateinit var webSocket: WebSocket
        webSocket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val message = json.decodeFromString(RelayServerMessage.serializer(), text)
                        events.trySend(message.toEvent())
                    }.onFailure { error ->
                        events.trySend(RelayServerEvent.Failure(error))
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    events.trySend(RelayServerEvent.Closed(code, reason))
                    events.close()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    events.trySend(RelayServerEvent.Closed(code, reason))
                    events.close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    events.trySend(RelayServerEvent.Failure(t))
                    events.close(t)
                }
            },
        )

        return RelaySocketConnection(
            events = events,
            webSocket = webSocket,
            json = json,
        )
    }

    private fun buildWebSocketUrl(
        relayBaseUrl: String,
        deviceId: String,
        relayToken: String,
    ): String {
        val normalized = relayBaseUrl.trim().removeSuffix("/")
        val websocketBase = when {
            normalized.startsWith("https://") -> normalized.replaceFirst("https://", "wss://")
            normalized.startsWith("http://") -> normalized.replaceFirst("http://", "ws://")
            else -> "wss://$normalized"
        }

        return "$websocketBase/api/v1/ws?device_id=$deviceId&relay_token=$relayToken"
    }
}

class RelaySocketConnection(
    val events: ReceiveChannel<RelayServerEvent>,
    private val webSocket: WebSocket,
    private val json: Json,
) {
    fun ping() {
        webSocket.send(json.encodeToString(RelayPingMessage.serializer(), RelayPingMessage()))
    }

    fun sendEnvelope(message: RelaySendEnvelopeMessage) {
        webSocket.send(json.encodeToString(RelaySendEnvelopeMessage.serializer(), message))
    }

    fun acknowledgeEnvelope(envelopeId: String) {
        webSocket.send(
            json.encodeToString(
                RelayAckEnvelopeMessage.serializer(),
                RelayAckEnvelopeMessage(envelopeId = envelopeId),
            ),
        )
    }

    fun close() {
        webSocket.close(1000, "closing")
    }
}

