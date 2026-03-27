package com.airbridge.app.feature.service

import android.app.Service
import android.content.Context
import android.util.Log
import com.airbridge.app.data.crypto.EnvelopeCipher
import com.airbridge.app.data.crypto.decodeBase64
import com.airbridge.app.data.crypto.encodeBase64
import com.airbridge.app.data.relay.RelayServerEvent
import com.airbridge.app.data.relay.RelaySocketConnection
import com.airbridge.app.data.relay.RelayWebSocketClient
import com.airbridge.app.data.relay.toWire
import com.airbridge.app.data.storage.DeviceIdentityStore
import com.airbridge.app.data.storage.RelayCredentialStore
import com.airbridge.app.domain.BridgeChannel
import com.airbridge.app.domain.ClipboardPayload
import com.airbridge.app.domain.IncomingEncryptedEnvelope
import com.airbridge.app.domain.NotificationEvent
import com.airbridge.app.domain.NotificationPayload
import com.airbridge.app.domain.StoredDeviceIdentity
import com.airbridge.app.domain.StoredRelayCredentials
import com.airbridge.app.feature.clipboard.ClipboardApplyGateway
import com.airbridge.app.feature.clipboard.ClipboardSyncCoordinator
import com.airbridge.app.feature.clipboard.buildClipboardFingerprint
import com.airbridge.app.feature.common.BridgeForegroundServiceDelegate
import com.airbridge.app.feature.common.ClipboardApplyOutcome
import com.airbridge.app.feature.common.ClipboardOutboundSink
import com.airbridge.app.feature.common.ClipboardSnapshot
import com.airbridge.app.feature.common.ClipboardTransferOrigin
import com.airbridge.app.feature.common.NotificationEventType
import com.airbridge.app.feature.common.NotificationOutboundSink
import com.airbridge.app.feature.common.NotificationSnapshot
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class BridgeRuntime(
    private val appContext: Context,
    parentScope: CoroutineScope,
    private val json: Json,
    private val deviceIdentityStore: DeviceIdentityStore,
    private val relayCredentialStore: RelayCredentialStore,
    private val relayWebSocketClient: RelayWebSocketClient,
    private val envelopeCipher: EnvelopeCipher,
) : ClipboardOutboundSink, NotificationOutboundSink, BridgeForegroundServiceDelegate {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
    private val mutableSnapshot = MutableStateFlow(BridgeRuntimeSnapshot())
    private val outboundMessages = MutableSharedFlow<OutboundPayload>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @Volatile
    private var runtimeJob: Job? = null
    @Volatile
    private var activeConnection: RelaySocketConnection? = null
    private val lifecycleMutex = Mutex()
    
    lateinit var clipboardSyncCoordinator: ClipboardSyncCoordinator

    val snapshot: StateFlow<BridgeRuntimeSnapshot> = mutableSnapshot.asStateFlow()

    override fun onForegroundServiceStarted(service: Service) {
        ensureRunning()
        mutableSnapshot.value = mutableSnapshot.value.copy(
            isServiceRunning = true,
            status = "브리지 서비스를 시작했어요",
        )
    }

    override fun onForegroundServiceStopped() {
        scope.launch {
            resetRuntime(
                keepServiceRunning = false,
                status = "브리지 서비스가 중지되었어요",
            )
        }
    }

    override suspend fun publishClipboard(
        snapshot: ClipboardSnapshot,
        origin: ClipboardTransferOrigin,
    ) {
        outboundMessages.emit(OutboundPayload.Clipboard(snapshot, origin))
    }

    override suspend fun publishNotification(snapshot: NotificationSnapshot) {
        outboundMessages.emit(OutboundPayload.Notification(snapshot))
    }

    fun ensureRunning() {
        if (runtimeJob?.isActive == true) {
            return
        }

        runtimeJob = scope.launch {
            connectionLoop()
        }
    }

    fun startForegroundService() {
        AirBridgeRelayForegroundService.start(appContext)
    }

    fun reloadStoredPairing() {
        scope.launch {
            resetRuntime(
                keepServiceRunning = true,
                status = "새 페어링 정보를 적용했어요",
            )
            ensureRunning()
        }
    }

    fun clearActivePairing() {
        scope.launch {
            resetRuntime(
                keepServiceRunning = mutableSnapshot.value.isServiceRunning,
                status = "저장된 페어링을 정리했어요",
            )
        }
    }

    private suspend fun connectionLoop() {
        while (true) {
            val credentials = relayCredentialStore.read()
            val identity = deviceIdentityStore.read()
            if (credentials == null || identity == null) {
                mutableSnapshot.value = BridgeRuntimeSnapshot(
                    isServiceRunning = true,
                    status = "페어링 완료 전이라 연결을 만들 수 없어요",
                )
                delay(5_000)
                continue
            }

            mutableSnapshot.value = mutableSnapshot.value.copy(
                isServiceRunning = true,
                isConnected = false,
                localDeviceId = credentials.localDeviceId,
                peerDeviceId = credentials.peerDeviceId,
                status = "relay 서버에 연결 중이에요",
            )

            runCatching {
                val connection = relayWebSocketClient.connect(
                    relayBaseUrl = credentials.relayBaseUrl,
                    deviceId = credentials.localDeviceId,
                    relayToken = credentials.localRelayToken,
                )
                handleConnection(connection, credentials, identity)
            }.onFailure { error ->
                Log.w(LogTag, "relay 연결 루프에서 오류가 발생했어요", error)
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    isConnected = false,
                    lastError = error.message ?: "relay 연결에 실패했어요",
                    status = "relay 재연결을 준비 중이에요",
                )
                delay(3_000)
            }
        }
    }

    private suspend fun handleConnection(
        connection: RelaySocketConnection,
        credentials: StoredRelayCredentials,
        identity: StoredDeviceIdentity,
    ) = coroutineScope {
        activeConnection = connection
        val senderJob = launch {
            outboundMessages.collect { payload ->
                sendPayload(connection, payload, credentials, identity)
            }
        }

        try {
            for (event in connection.events) {
                when (event) {
                    is RelayServerEvent.Connected -> {
                        mutableSnapshot.value = mutableSnapshot.value.copy(
                            isConnected = true,
                            localDeviceId = event.deviceId,
                            peerDeviceId = event.peerDeviceId,
                            status = "relay 연결이 활성화되었어요",
                            lastError = null,
                        )
                    }
                    is RelayServerEvent.Envelope -> {
                        handleIncomingEnvelope(event.envelope, connection, credentials, identity)
                    }
                    is RelayServerEvent.Error -> {
                        mutableSnapshot.value = mutableSnapshot.value.copy(
                            lastError = event.message,
                            status = "relay 서버 오류를 받았어요",
                        )
                    }
                    RelayServerEvent.Pong -> {
                        mutableSnapshot.value = mutableSnapshot.value.copy(
                            isConnected = true,
                            status = "relay 연결이 유지되고 있어요",
                        )
                    }
                    is RelayServerEvent.Closed -> {
                        mutableSnapshot.value = mutableSnapshot.value.copy(
                            isConnected = false,
                            status = "relay 연결이 종료되었어요",
                        )
                    }
                    is RelayServerEvent.Failure -> throw event.throwable
                }
            }
        } finally {
            senderJob.cancel()
            connection.close()
            if (activeConnection === connection) {
                activeConnection = null
            }
            mutableSnapshot.value = mutableSnapshot.value.copy(isConnected = false)
        }
    }

    private suspend fun resetRuntime(
        keepServiceRunning: Boolean,
        status: String,
    ) {
        lifecycleMutex.withLock {
            runtimeJob?.cancel()
            runtimeJob = null
            activeConnection?.close()
            activeConnection = null
            clipboardSyncCoordinator.resetSyncState()
            mutableSnapshot.value = BridgeRuntimeSnapshot(
                isServiceRunning = keepServiceRunning,
                status = status,
            )
        }
    }

    private suspend fun sendPayload(
        connection: RelaySocketConnection,
        payload: OutboundPayload,
        credentials: StoredRelayCredentials,
        identity: StoredDeviceIdentity,
    ) {
        val (channel, contentType, plaintext) = when (payload) {
            is OutboundPayload.Clipboard -> {
                val clipboardPayload = payload.snapshot.toDomainPayload()
                clipboardPayload.requireSupportedSize()
                Triple(
                    BridgeChannel.CLIPBOARD,
                    "application/json",
                    json.encodeToString(ClipboardPayload.serializer(), clipboardPayload).encodeToByteArray(),
                )
            }
            is OutboundPayload.Notification -> {
                val notificationPayload = payload.snapshot.toDomainPayload()
                Triple(
                    BridgeChannel.NOTIFICATION,
                    "application/json",
                    json.encodeToString(NotificationPayload.serializer(), notificationPayload).encodeToByteArray(),
                )
            }
        }

        val encryptedEnvelope = envelopeCipher.encrypt(
            pairingSessionId = credentials.pairingSessionId,
            senderDeviceId = credentials.localDeviceId,
            recipientDeviceId = credentials.peerDeviceId,
            channel = channel,
            contentType = contentType,
            plaintext = plaintext,
            localPrivateKeyBase64 = identity.privateKeyBase64,
            peerPublicKeyBase64 = credentials.peerPublicKeyBase64,
        )

        connection.sendEnvelope(encryptedEnvelope.toWire(credentials.peerDeviceId))
        mutableSnapshot.value = mutableSnapshot.value.copy(
            lastSentAt = Instant.now(),
            status = when (payload) {
                is OutboundPayload.Clipboard -> "클립보드를 암호화해서 전송했어요"
                is OutboundPayload.Notification -> "알림을 암호화해서 전송했어요"
            },
            lastError = null,
        )
    }

    private suspend fun handleIncomingEnvelope(
        incoming: IncomingEncryptedEnvelope,
        connection: RelaySocketConnection,
        credentials: StoredRelayCredentials,
        identity: StoredDeviceIdentity,
    ) {
        when (incoming.channel) {
            BridgeChannel.CLIPBOARD -> {
                val plaintext = envelopeCipher.decrypt(
                    pairingSessionId = credentials.pairingSessionId,
                    senderDeviceId = incoming.senderDeviceId,
                    recipientDeviceId = credentials.localDeviceId,
                    channel = incoming.channel,
                    contentType = incoming.contentType,
                    nonce = incoming.nonce,
                    headerAad = incoming.headerAad,
                    ciphertext = incoming.ciphertext,
                    localPrivateKeyBase64 = identity.privateKeyBase64,
                    peerPublicKeyBase64 = credentials.peerPublicKeyBase64,
                )
                val payload = json.decodeFromString(ClipboardPayload.serializer(), plaintext.decodeToString())
                val applyOutcome = clipboardSyncCoordinator.applyRemoteClipboard(payload.toSnapshot())
                if (applyOutcome is ClipboardApplyOutcome.Applied) {
                    connection.acknowledgeEnvelope(incoming.envelopeId)
                    mutableSnapshot.value = mutableSnapshot.value.copy(
                        lastReceivedAt = Instant.now(),
                        status = "Mac 에서 온 클립보드를 적용했어요",
                    )
                } else if (applyOutcome is ClipboardApplyOutcome.Failure) {
                    mutableSnapshot.value = mutableSnapshot.value.copy(
                        lastError = applyOutcome.message,
                        status = "수신 클립보드 적용에 실패했어요",
                    )
                }
            }
            BridgeChannel.NOTIFICATION -> {
                connection.acknowledgeEnvelope(incoming.envelopeId)
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    lastReceivedAt = Instant.now(),
                    status = "알림 envelope를 수신했어요",
                )
            }
        }
    }

    private fun ClipboardSnapshot.toDomainPayload(): ClipboardPayload = ClipboardPayload(
        mimeType = mimeType,
        label = label,
        textValue = plainText,
        htmlValue = htmlText,
        uriList = uriList,
        binaryBase64 = binaryPayload?.encodeBase64(),
    )

    private fun NotificationSnapshot.toDomainPayload(): NotificationPayload = NotificationPayload(
        event = when (eventType) {
            NotificationEventType.Posted -> NotificationEvent.POSTED
            NotificationEventType.Updated -> NotificationEvent.UPDATED
            NotificationEventType.Removed -> NotificationEvent.REMOVED
        },
        notificationId = notificationKey,
        packageName = packageName,
        appName = appName,
        title = title,
        body = body,
        postedAt = postedAt.toString(),
        isOngoing = isOngoing,
    )

    private fun ClipboardPayload.toSnapshot(): ClipboardSnapshot {
        val binary = binaryBase64?.decodeBase64()
        return ClipboardSnapshot(
            mimeType = mimeType,
            label = label,
            plainText = textValue,
            htmlText = htmlValue,
            uriList = uriList,
            binaryPayload = binary,
            fingerprint = buildClipboardFingerprint(
                mimeType = mimeType,
                plainText = textValue,
                htmlText = htmlValue,
                uriList = uriList,
                binaryPayload = binary,
            ),
            capturedAt = Instant.now(),
        )
    }

    companion object {
        private const val LogTag = "BridgeRuntime"
    }
}

data class BridgeRuntimeSnapshot(
    val isServiceRunning: Boolean = false,
    val isConnected: Boolean = false,
    val localDeviceId: String? = null,
    val peerDeviceId: String? = null,
    val lastSentAt: Instant? = null,
    val lastReceivedAt: Instant? = null,
    val lastError: String? = null,
    val status: String = "대기 중이에요",
)

private sealed interface OutboundPayload {
    data class Clipboard(
        val snapshot: ClipboardSnapshot,
        val origin: ClipboardTransferOrigin,
    ) : OutboundPayload

    data class Notification(val snapshot: NotificationSnapshot) : OutboundPayload
}
