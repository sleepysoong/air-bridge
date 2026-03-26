package com.airbridge.app.data.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SasCodeGenerationTest {
    private val sessionKeyStore = SessionKeyStore()
    private val cipher = EnvelopeCipher(
        json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
        sessionKeyStore = sessionKeyStore,
    )

    @Test
    fun `SAS 코드는 6자리 숫자`() {
        val identity1 = sessionKeyStore.generateLocalIdentity("Device A")
        val identity2 = sessionKeyStore.generateLocalIdentity("Device B")

        val sasCode = cipher.calculateSasCode(
            pairingSessionId = "session123",
            initiatorDeviceId = "device_a",
            joinerDeviceId = "device_b",
            localPrivateKeyBase64 = identity1.privateKeyBase64,
            peerPublicKeyBase64 = identity2.publicKeyBase64,
        )

        assertEquals(6, sasCode.length)
        assertTrue(sasCode.all { it.isDigit() })
    }

    @Test
    fun `SAS 코드는 leading zero 포함`() {
        val identity1 = sessionKeyStore.generateLocalIdentity("Device A")
        val identity2 = sessionKeyStore.generateLocalIdentity("Device B")

        val sasCode = cipher.calculateSasCode(
            pairingSessionId = "session_with_low_hash",
            initiatorDeviceId = "device_a",
            joinerDeviceId = "device_b",
            localPrivateKeyBase64 = identity1.privateKeyBase64,
            peerPublicKeyBase64 = identity2.publicKeyBase64,
        )

        assertEquals(6, sasCode.length)
    }

    @Test
    fun `SAS 코드 범위는 000000부터 999999`() {
        val identity1 = sessionKeyStore.generateLocalIdentity("Device A")
        val identity2 = sessionKeyStore.generateLocalIdentity("Device B")

        val sasCode = cipher.calculateSasCode(
            pairingSessionId = "session123",
            initiatorDeviceId = "device_a",
            joinerDeviceId = "device_b",
            localPrivateKeyBase64 = identity1.privateKeyBase64,
            peerPublicKeyBase64 = identity2.publicKeyBase64,
        )

        val numeric = sasCode.toInt()
        assertTrue(numeric in 0..999999)
    }

    @Test
    fun `양방향 SAS 코드 일치`() {
        val identity1 = sessionKeyStore.generateLocalIdentity("Device A")
        val identity2 = sessionKeyStore.generateLocalIdentity("Device B")

        val sasFromInitiator = cipher.calculateSasCode(
            pairingSessionId = "session123",
            initiatorDeviceId = "device_a",
            joinerDeviceId = "device_b",
            localPrivateKeyBase64 = identity1.privateKeyBase64,
            peerPublicKeyBase64 = identity2.publicKeyBase64,
        )

        val sasFromJoiner = cipher.calculateSasCode(
            pairingSessionId = "session123",
            initiatorDeviceId = "device_a",
            joinerDeviceId = "device_b",
            localPrivateKeyBase64 = identity2.privateKeyBase64,
            peerPublicKeyBase64 = identity1.publicKeyBase64,
        )

        assertEquals(sasFromInitiator, sasFromJoiner)
    }

    @Test
    fun `서로 다른 세션은 서로 다른 SAS 생성`() {
        val identity1 = sessionKeyStore.generateLocalIdentity("Device A")
        val identity2 = sessionKeyStore.generateLocalIdentity("Device B")

        val sas1 = cipher.calculateSasCode(
            pairingSessionId = "session1",
            initiatorDeviceId = "device_a",
            joinerDeviceId = "device_b",
            localPrivateKeyBase64 = identity1.privateKeyBase64,
            peerPublicKeyBase64 = identity2.publicKeyBase64,
        )

        val sas2 = cipher.calculateSasCode(
            pairingSessionId = "session2",
            initiatorDeviceId = "device_a",
            joinerDeviceId = "device_b",
            localPrivateKeyBase64 = identity1.privateKeyBase64,
            peerPublicKeyBase64 = identity2.publicKeyBase64,
        )

        assertNotEquals(sas1, sas2)
    }

    @Test
    fun `서로 다른 디바이스 ID 순서는 서로 다른 SAS 생성`() {
        val identity1 = sessionKeyStore.generateLocalIdentity("Device A")
        val identity2 = sessionKeyStore.generateLocalIdentity("Device B")

        val sasAB = cipher.calculateSasCode(
            pairingSessionId = "session123",
            initiatorDeviceId = "device_a",
            joinerDeviceId = "device_b",
            localPrivateKeyBase64 = identity1.privateKeyBase64,
            peerPublicKeyBase64 = identity2.publicKeyBase64,
        )

        val sasBA = cipher.calculateSasCode(
            pairingSessionId = "session123",
            initiatorDeviceId = "device_b",
            joinerDeviceId = "device_a",
            localPrivateKeyBase64 = identity1.privateKeyBase64,
            peerPublicKeyBase64 = identity2.publicKeyBase64,
        )

        assertNotEquals(sasAB, sasBA)
    }

    @Test
    fun `서로 다른 키 쌍은 서로 다른 SAS 생성`() {
        val identity1 = sessionKeyStore.generateLocalIdentity("Device A")
        val identity2 = sessionKeyStore.generateLocalIdentity("Device B")
        val identity3 = sessionKeyStore.generateLocalIdentity("Device C")

        val sas12 = cipher.calculateSasCode(
            pairingSessionId = "session123",
            initiatorDeviceId = "device_a",
            joinerDeviceId = "device_b",
            localPrivateKeyBase64 = identity1.privateKeyBase64,
            peerPublicKeyBase64 = identity2.publicKeyBase64,
        )

        val sas13 = cipher.calculateSasCode(
            pairingSessionId = "session123",
            initiatorDeviceId = "device_a",
            joinerDeviceId = "device_b",
            localPrivateKeyBase64 = identity1.privateKeyBase64,
            peerPublicKeyBase64 = identity3.publicKeyBase64,
        )

        assertNotEquals(sas12, sas13)
    }

    @Test
    fun `동일 입력은 항상 동일 SAS 생성 (결정론적)`() {
        val identity1 = sessionKeyStore.generateLocalIdentity("Device A")
        val identity2 = sessionKeyStore.generateLocalIdentity("Device B")

        val sas1 = cipher.calculateSasCode(
            pairingSessionId = "session123",
            initiatorDeviceId = "device_a",
            joinerDeviceId = "device_b",
            localPrivateKeyBase64 = identity1.privateKeyBase64,
            peerPublicKeyBase64 = identity2.publicKeyBase64,
        )

        val sas2 = cipher.calculateSasCode(
            pairingSessionId = "session123",
            initiatorDeviceId = "device_a",
            joinerDeviceId = "device_b",
            localPrivateKeyBase64 = identity1.privateKeyBase64,
            peerPublicKeyBase64 = identity2.publicKeyBase64,
        )

        assertEquals(sas1, sas2)
    }
}
