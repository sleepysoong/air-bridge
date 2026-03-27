package com.airbridge.app.feature.pairing

import android.content.Context
import android.os.Build
import com.airbridge.app.data.crypto.SessionKeyStore
import com.airbridge.app.data.relay.RelayHttpClient
import com.airbridge.app.data.storage.DeviceIdentityStore
import com.airbridge.app.data.storage.RelayCredentialStore
import com.airbridge.app.domain.PairingQrPayload
import com.airbridge.app.domain.StoredRelayCredentials
import java.time.Instant

class PairingRepository(
    private val appContext: Context,
    private val relayHttpClient: RelayHttpClient,
    private val deviceIdentityStore: DeviceIdentityStore,
    private val relayCredentialStore: RelayCredentialStore,
    private val sessionKeyStore: SessionKeyStore,
) {
    suspend fun preparePairing(
        qrPayload: PairingQrPayload,
        requestedDeviceName: String,
    ): StoredRelayCredentials {
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

        val credentials = StoredRelayCredentials(
            relayBaseUrl = workingRelayUrl,
            pairingSessionId = qrPayload.pairingSessionId,
            pairingSecret = qrPayload.pairingSecret,
            localDeviceId = joinResult.joinerDeviceId,
            localRelayToken = joinResult.joinerRelayToken,
            localDeviceName = localIdentity.deviceName,
            peerDeviceId = joinResult.initiatorDeviceId,
            peerPublicKeyBase64 = joinResult.initiatorPublicKey,
            initiatorDeviceId = joinResult.initiatorDeviceId,
            initiatorName = snapshot.initiatorName,
            joinedAt = Instant.now().toString(),
            completedAt = snapshot.completedAt ?: snapshot.updatedAt,
        )

        deviceIdentityStore.write(localIdentity)
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
