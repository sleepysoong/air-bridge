package com.airbridge.app.data.crypto

import com.airbridge.app.data.relay.RelayServerLimits
import com.airbridge.app.domain.BridgeChannel
import com.airbridge.app.domain.EncryptedEnvelope
import com.airbridge.app.domain.EnvelopeHeader
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EnvelopeCipher(
    private val json: Json,
    private val sessionKeyStore: SessionKeyStore,
) {
    private val secureRandom = SecureRandom()

    fun encrypt(
        pairingSessionId: String,
        senderDeviceId: String,
        recipientDeviceId: String,
        channel: BridgeChannel,
        contentType: String,
        plaintext: ByteArray,
        localPrivateKeyBase64: String,
        peerPublicKeyBase64: String,
    ): EncryptedEnvelope {
        val contentTypeBytes = contentType.toByteArray(StandardCharsets.UTF_8)
        require(contentTypeBytes.size <= RelayServerLimits.MAX_CONTENT_TYPE_BYTES) {
            "content_type은 ${RelayServerLimits.MAX_CONTENT_TYPE_BYTES}바이트를 넘을 수 없어요 (현재: ${contentTypeBytes.size}바이트)"
        }
        
        val sharedSecret = sessionKeyStore.deriveSharedSecret(localPrivateKeyBase64, peerPublicKeyBase64)
        val key = deriveDirectionKey(sharedSecret, pairingSessionId, senderDeviceId, recipientDeviceId)
        val nonce = ByteArray(12).also(secureRandom::nextBytes)
        
        require(nonce.size <= RelayServerLimits.MAX_NONCE_BYTES) {
            "nonce는 ${RelayServerLimits.MAX_NONCE_BYTES}바이트를 넘을 수 없어요 (현재: ${nonce.size}바이트)"
        }
        
        val header = EnvelopeHeader(
            channel = channel,
            contentType = contentType,
            senderDeviceId = senderDeviceId,
            recipientDeviceId = recipientDeviceId,
            pairingSessionId = pairingSessionId,
        )
        val headerBytes = json.encodeToString(EnvelopeHeader.serializer(), header).toByteArray(StandardCharsets.UTF_8)
        
        require(headerBytes.size <= RelayServerLimits.MAX_HEADER_AAD_BYTES) {
            "header_aad는 ${RelayServerLimits.MAX_HEADER_AAD_BYTES}바이트를 넘을 수 없어요 (현재: ${headerBytes.size}바이트)"
        }
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(headerBytes)
        val ciphertext = cipher.doFinal(plaintext)
        
        require(ciphertext.size <= RelayServerLimits.MAX_CIPHERTEXT_BYTES) {
            "ciphertext는 ${RelayServerLimits.MAX_CIPHERTEXT_BYTES}바이트를 넘을 수 없어요 (현재: ${ciphertext.size}바이트)"
        }

        return EncryptedEnvelope(
            channel = channel,
            contentType = contentType,
            nonce = nonce,
            headerAad = headerBytes,
            ciphertext = ciphertext,
        )
    }

    fun decrypt(
        pairingSessionId: String,
        senderDeviceId: String,
        recipientDeviceId: String,
        channel: BridgeChannel,
        contentType: String,
        nonce: ByteArray,
        headerAad: ByteArray,
        ciphertext: ByteArray,
        localPrivateKeyBase64: String,
        peerPublicKeyBase64: String,
    ): ByteArray {
        val sharedSecret = sessionKeyStore.deriveSharedSecret(localPrivateKeyBase64, peerPublicKeyBase64)
        val key = deriveDirectionKey(sharedSecret, pairingSessionId, senderDeviceId, recipientDeviceId)
        val expectedHeader = EnvelopeHeader(
            channel = channel,
            contentType = contentType,
            senderDeviceId = senderDeviceId,
            recipientDeviceId = recipientDeviceId,
            pairingSessionId = pairingSessionId,
        )
        val expectedHeaderBytes = json.encodeToString(EnvelopeHeader.serializer(), expectedHeader)
            .toByteArray(StandardCharsets.UTF_8)

        check(headerAad.contentEquals(expectedHeaderBytes)) {
            "envelope header AAD가 예상 값과 일치하지 않아요."
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(headerAad)
        return cipher.doFinal(ciphertext)
    }

    private fun deriveDirectionKey(
        sharedSecret: ByteArray,
        pairingSessionId: String,
        senderDeviceId: String,
        recipientDeviceId: String,
    ): ByteArray {
        val info = "air-bridge|aes-gcm|$senderDeviceId|$recipientDeviceId|v1".toByteArray(StandardCharsets.UTF_8)
        return sessionKeyStore.hkdfSha256(
            ikm = sharedSecret,
            salt = pairingSessionId.toByteArray(StandardCharsets.UTF_8),
            info = info,
            size = 32,
        )
    }
}
