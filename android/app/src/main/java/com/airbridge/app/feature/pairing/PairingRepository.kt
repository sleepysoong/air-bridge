package com.airbridge.app.feature.pairing

import android.content.Context
import android.os.Build
import com.airbridge.app.data.crypto.EnvelopeCipher
import com.airbridge.app.data.crypto.SessionKeyStore
import com.airbridge.app.data.relay.RelayApiException
import com.airbridge.app.data.relay.RelayHttpClient
import com.airbridge.app.data.storage.DeviceIdentityStore
import com.airbridge.app.data.storage.RelayCredentialStore
import com.airbridge.app.domain.PairingQrPayload
import com.airbridge.app.domain.PairingSessionState
import com.airbridge.app.domain.PendingPairingSession
import com.airbridge.app.domain.StoredRelayCredentials
import java.time.Instant

class PairingRepository(
    private val appContext: Context,
    private val relayHttpClient: RelayHttpClient,
    private val deviceIdentityStore: DeviceIdentityStore,
    private val relayCredentialStore: RelayCredentialStore,
    private val sessionKeyStore: SessionKeyStore,
    private val envelopeCipher: EnvelopeCipher,
) {
    suspend fun preparePairing(
        qrPayload: PairingQrPayload,
        requestedDeviceName: String,
    ): PendingPairingSession {
        val workingRelayUrl = relayHttpClient.findWorkingRelayUrl(qrPayload.relayAddresses)
        
        val localIdentity = sessionKeyStore.generateLocalIdentity(
            deviceName = requestedDeviceName.ifBlank { defaultDeviceName() },
        )
        val joinResult = relayHttpClient.joinPairingSession(
            relayBaseUrl = workingRelayUrl,
            pairingSessionId = qrPayload.pairingSessionId,
            pairingSecret = qrPayload.pairingSecret,
            deviceName = localIdentity.deviceName,
            publicKeyBase64 = localIdentity.publicKeyBase64,
        )
        val snapshot = relayHttpClient.lookupPairingSession(
            relayBaseUrl = workingRelayUrl,
            pairingSessionId = qrPayload.pairingSessionId,
            pairingSecret = qrPayload.pairingSecret,
        )
        val sasCode = envelopeCipher.calculateSasCode(
            pairingSessionId = qrPayload.pairingSessionId,
            initiatorDeviceId = joinResult.initiatorDeviceId,
            joinerDeviceId = joinResult.joinerDeviceId,
            localPrivateKeyBase64 = localIdentity.privateKeyBase64,
            peerPublicKeyBase64 = joinResult.initiatorPublicKey,
        )

        return PendingPairingSession(
            qrPayload = qrPayload,
            resolvedRelayUrl = workingRelayUrl,
            sessionSnapshot = snapshot,
            joinResult = joinResult,
            localIdentity = localIdentity,
            sasCode = sasCode,
        )
    }

    suspend fun completePairing(pendingPairingSession: PendingPairingSession): StoredRelayCredentials {
        val qrPayload = pendingPairingSession.qrPayload
        val relayBaseUrl = pendingPairingSession.resolvedRelayUrl
        val completedAt = runCatching {
            relayHttpClient.completePairingSession(
                relayBaseUrl = relayBaseUrl,
                pairingSessionId = qrPayload.pairingSessionId,
                pairingSecret = qrPayload.pairingSecret,
            ).completedAt
        }.recoverCatching { error ->
            if (error is RelayApiException && error.code == "conflict") {
                val snapshot = relayHttpClient.lookupPairingSession(
                    relayBaseUrl = relayBaseUrl,
                    pairingSessionId = qrPayload.pairingSessionId,
                    pairingSecret = qrPayload.pairingSecret,
                )
                if (snapshot.state == PairingSessionState.COMPLETED) {
                    return@recoverCatching snapshot.completedAt ?: Instant.now().toString()
                }
            }
            throw error
        }.getOrThrow()

        val credentials = StoredRelayCredentials(
            relayBaseUrl = relayBaseUrl,
            pairingSessionId = qrPayload.pairingSessionId,
            pairingSecret = qrPayload.pairingSecret,
            localDeviceId = pendingPairingSession.joinResult.joinerDeviceId,
            localRelayToken = pendingPairingSession.joinResult.joinerRelayToken,
            localDeviceName = pendingPairingSession.localIdentity.deviceName,
            peerDeviceId = pendingPairingSession.joinResult.initiatorDeviceId,
            peerPublicKeyBase64 = pendingPairingSession.joinResult.initiatorPublicKey,
            initiatorDeviceId = pendingPairingSession.joinResult.initiatorDeviceId,
            initiatorName = pendingPairingSession.sessionSnapshot.initiatorName,
            joinedAt = Instant.now().toString(),
            completedAt = completedAt,
        )

        deviceIdentityStore.write(pendingPairingSession.localIdentity)
        relayCredentialStore.write(credentials)

        return credentials
    }

    fun currentCredentials(): StoredRelayCredentials? = relayCredentialStore.read()

    fun defaultDeviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { "Android device" }

    fun clearPairing() {
        deviceIdentityStore.clear()
        relayCredentialStore.clear()
    }
}

