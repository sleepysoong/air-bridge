package com.airbridge.app.data.crypto

import com.airbridge.app.domain.BridgeChannel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EnvelopeCipherTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val sessionKeyStore = SessionKeyStore()
    private val cipher = EnvelopeCipher(json, sessionKeyStore)

    private val pairingSessionId = "session123"
    private val senderDeviceId = "device_a"
    private val recipientDeviceId = "device_b"

    @Test
    fun `AES-GCM 암복호화 - 클립보드 채널`() {
        val identity1 = sessionKeyStore.generateLocalIdentity("Device A")
        val identity2 = sessionKeyStore.generateLocalIdentity("Device B")
        val plaintext = "Hello, World!".toByteArray()

        val envelope = cipher.encrypt(
            pairingSessionId = pairingSessionId,
            senderDeviceId = senderDeviceId,
            recipientDeviceId = recipientDeviceId,
            channel = BridgeChannel.CLIPBOARD,
            contentType = "text/plain",
            plaintext = plaintext,
            localPrivateKeyBase64 = identity1.privateKeyBase64,
            peerPublicKeyBase64 = identity2.publicKeyBase64,
        )

        val decrypted = cipher.decrypt(
            pairingSessionId = pairingSessionId,
            senderDeviceId = senderDeviceId,
            recipientDeviceId = recipientDeviceId,
            channel = BridgeChannel.CLIPBOARD,
            contentType = "text/plain",
            nonce = envelope.nonce,
            headerAad = envelope.headerAad,
            ciphertext = envelope.ciphertext,
            localPrivateKeyBase64 = identity2.privateKeyBase64,
            peerPublicKeyBase64 = identity1.publicKeyBase64,
        )

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `AES-GCM 암복호화 - 알림 채널`() {
        val identity1 = sessionKeyStore.generateLocalIdentity("Device A")
        val identity2 = sessionKeyStore.generateLocalIdentity("Device B")
        val plaintext = """{"title":"Test","body":"Message"}""".toByteArray()

        val envelope = cipher.encrypt(
            pairingSessionId = pairingSessionId,
            senderDeviceId = senderDeviceId,
            recipientDeviceId = recipientDeviceId,
            channel = BridgeChannel.NOTIFICATION,
            contentType = "application/json",
            plaintext = plaintext,
            localPrivateKeyBase64 = identity1.privateKeyBase64,
            peerPublicKeyBase64 = identity2.publicKeyBase64,
        )

        val decrypted = cipher.decrypt(
            pairingSessionId = pairingSessionId,
            senderDeviceId = senderDeviceId,
            recipientDeviceId = recipientDeviceId,
            channel = BridgeChannel.NOTIFICATION,
            contentType = "application/json",
            nonce = envelope.nonce,
            headerAad = envelope.headerAad,
            ciphertext = envelope.ciphertext,
            localPrivateKeyBase64 = identity2.privateKeyBase64,
            peerPublicKeyBase64 = identity1.publicKeyBase64,
        )

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `nonce는 12바이트`() {
        val identity1 = sessionKeyStore.generateLocalIdentity("Device A")
        val identity2 = sessionKeyStore.generateLocalIdentity("Device B")

        val envelope = cipher.encrypt(
            pairingSessionId = pairingSessionId,
            senderDeviceId = senderDeviceId,
            recipientDeviceId = recipientDeviceId,
            channel = BridgeChannel.CLIPBOARD,
            contentType = "text/plain",
            plaintext = "test".toByteArray(),
            localPrivateKeyBase64 = identity1.privateKeyBase64,
            peerPublicKeyBase64 = identity2.publicKeyBase64,
        )

        assertEquals(12, envelope.nonce.size)
    }

    @Test
    fun `ciphertext는 plaintext보다 16바이트 길어야 함 (GCM tag)`() {
        val identity1 = sessionKeyStore.generateLocalIdentity("Device A")
        val identity2 = sessionKeyStore.generateLocalIdentity("Device B")
        val plaintext = "test".toByteArray()

        val envelope = cipher.encrypt(
            pairingSessionId = pairingSessionId,
            senderDeviceId = senderDeviceId,
            recipientDeviceId = recipientDeviceId,
            channel = BridgeChannel.CLIPBOARD,
            contentType = "text/plain",
            plaintext = plaintext,
            localPrivateKeyBase64 = identity1.privateKeyBase64,
            peerPublicKeyBase64 = identity2.publicKeyBase64,
        )

        assertEquals(plaintext.size + 16, envelope.ciphertext.size)
    }

    @Test
    fun `잘못된 키로 복호화 실패`() {
        val identity1 = sessionKeyStore.generateLocalIdentity("Device A")
        val identity2 = sessionKeyStore.generateLocalIdentity("Device B")
        val identity3 = sessionKeyStore.generateLocalIdentity("Device C")

        val envelope = cipher.encrypt(
            pairingSessionId = pairingSessionId,
            senderDeviceId = senderDeviceId,
            recipientDeviceId = recipientDeviceId,
            channel = BridgeChannel.CLIPBOARD,
            contentType = "text/plain",
            plaintext = "secret".toByteArray(),
            localPrivateKeyBase64 = identity1.privateKeyBase64,
            peerPublicKeyBase64 = identity2.publicKeyBase64,
        )

        assertThrows(Exception::class.java) {
            cipher.decrypt(
                pairingSessionId = pairingSessionId,
                senderDeviceId = senderDeviceId,
                recipientDeviceId = recipientDeviceId,
                channel = BridgeChannel.CLIPBOARD,
                contentType = "text/plain",
                nonce = envelope.nonce,
                headerAad = envelope.headerAad,
                ciphertext = envelope.ciphertext,
                localPrivateKeyBase64 = identity3.privateKeyBase64,
                peerPublicKeyBase64 = identity1.publicKeyBase64,
            )
        }
    }

    @Test
    fun `잘못된 header AAD로 복호화 실패`() {
        val identity1 = sessionKeyStore.generateLocalIdentity("Device A")
        val identity2 = sessionKeyStore.generateLocalIdentity("Device B")

        val envelope = cipher.encrypt(
            pairingSessionId = pairingSessionId,
            senderDeviceId = senderDeviceId,
            recipientDeviceId = recipientDeviceId,
            channel = BridgeChannel.CLIPBOARD,
            contentType = "text/plain",
            plaintext = "secret".toByteArray(),
            localPrivateKeyBase64 = identity1.privateKeyBase64,
            peerPublicKeyBase64 = identity2.publicKeyBase64,
        )

        assertThrows(IllegalStateException::class.java) {
            cipher.decrypt(
                pairingSessionId = pairingSessionId,
                senderDeviceId = senderDeviceId,
                recipientDeviceId = recipientDeviceId,
                channel = BridgeChannel.NOTIFICATION,
                contentType = "text/plain",
                nonce = envelope.nonce,
                headerAad = envelope.headerAad,
                ciphertext = envelope.ciphertext,
                localPrivateKeyBase64 = identity2.privateKeyBase64,
                peerPublicKeyBase64 = identity1.publicKeyBase64,
            )
        }
    }

    @Test
    fun `서로 다른 방향은 서로 다른 키 사용`() {
        val identity1 = sessionKeyStore.generateLocalIdentity("Device A")
        val identity2 = sessionKeyStore.generateLocalIdentity("Device B")
        val plaintext = "test".toByteArray()

        val envelope1to2 = cipher.encrypt(
            pairingSessionId = pairingSessionId,
            senderDeviceId = senderDeviceId,
            recipientDeviceId = recipientDeviceId,
            channel = BridgeChannel.CLIPBOARD,
            contentType = "text/plain",
            plaintext = plaintext,
            localPrivateKeyBase64 = identity1.privateKeyBase64,
            peerPublicKeyBase64 = identity2.publicKeyBase64,
        )

        val envelope2to1 = cipher.encrypt(
            pairingSessionId = pairingSessionId,
            senderDeviceId = recipientDeviceId,
            recipientDeviceId = senderDeviceId,
            channel = BridgeChannel.CLIPBOARD,
            contentType = "text/plain",
            plaintext = plaintext,
            localPrivateKeyBase64 = identity2.privateKeyBase64,
            peerPublicKeyBase64 = identity1.publicKeyBase64,
        )

        assertThrows(Exception::class.java) {
            cipher.decrypt(
                pairingSessionId = pairingSessionId,
                senderDeviceId = senderDeviceId,
                recipientDeviceId = recipientDeviceId,
                channel = BridgeChannel.CLIPBOARD,
                contentType = "text/plain",
                nonce = envelope2to1.nonce,
                headerAad = envelope1to2.headerAad,
                ciphertext = envelope2to1.ciphertext,
                localPrivateKeyBase64 = identity2.privateKeyBase64,
                peerPublicKeyBase64 = identity1.publicKeyBase64,
            )
        }
    }

    @Test
    fun `빈 plaintext 암복호화`() {
        val identity1 = sessionKeyStore.generateLocalIdentity("Device A")
        val identity2 = sessionKeyStore.generateLocalIdentity("Device B")
        val plaintext = ByteArray(0)

        val envelope = cipher.encrypt(
            pairingSessionId = pairingSessionId,
            senderDeviceId = senderDeviceId,
            recipientDeviceId = recipientDeviceId,
            channel = BridgeChannel.CLIPBOARD,
            contentType = "text/plain",
            plaintext = plaintext,
            localPrivateKeyBase64 = identity1.privateKeyBase64,
            peerPublicKeyBase64 = identity2.publicKeyBase64,
        )

        val decrypted = cipher.decrypt(
            pairingSessionId = pairingSessionId,
            senderDeviceId = senderDeviceId,
            recipientDeviceId = recipientDeviceId,
            channel = BridgeChannel.CLIPBOARD,
            contentType = "text/plain",
            nonce = envelope.nonce,
            headerAad = envelope.headerAad,
            ciphertext = envelope.ciphertext,
            localPrivateKeyBase64 = identity2.privateKeyBase64,
            peerPublicKeyBase64 = identity1.publicKeyBase64,
        )

        assertArrayEquals(plaintext, decrypted)
        assertEquals(16, envelope.ciphertext.size)
    }

    @Test
    fun `큰 plaintext 암복호화 (1MB)`() {
        val identity1 = sessionKeyStore.generateLocalIdentity("Device A")
        val identity2 = sessionKeyStore.generateLocalIdentity("Device B")
        val plaintext = ByteArray(1024 * 1024) { it.toByte() }

        val envelope = cipher.encrypt(
            pairingSessionId = pairingSessionId,
            senderDeviceId = senderDeviceId,
            recipientDeviceId = recipientDeviceId,
            channel = BridgeChannel.CLIPBOARD,
            contentType = "application/octet-stream",
            plaintext = plaintext,
            localPrivateKeyBase64 = identity1.privateKeyBase64,
            peerPublicKeyBase64 = identity2.publicKeyBase64,
        )

        val decrypted = cipher.decrypt(
            pairingSessionId = pairingSessionId,
            senderDeviceId = senderDeviceId,
            recipientDeviceId = recipientDeviceId,
            channel = BridgeChannel.CLIPBOARD,
            contentType = "application/octet-stream",
            nonce = envelope.nonce,
            headerAad = envelope.headerAad,
            ciphertext = envelope.ciphertext,
            localPrivateKeyBase64 = identity2.privateKeyBase64,
            peerPublicKeyBase64 = identity1.publicKeyBase64,
        )

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `content_type이 255바이트 초과시 거부`() {
        val identity1 = sessionKeyStore.generateLocalIdentity("Device A")
        val identity2 = sessionKeyStore.generateLocalIdentity("Device B")
        val longContentType = "a".repeat(256)

        assertThrows(IllegalArgumentException::class.java) {
            cipher.encrypt(
                pairingSessionId = pairingSessionId,
                senderDeviceId = senderDeviceId,
                recipientDeviceId = recipientDeviceId,
                channel = BridgeChannel.CLIPBOARD,
                contentType = longContentType,
                plaintext = "test".toByteArray(),
                localPrivateKeyBase64 = identity1.privateKeyBase64,
                peerPublicKeyBase64 = identity2.publicKeyBase64,
            )
        }
    }
}
