package com.airbridge.app.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Base64

class SessionKeyStoreTest {
    private val store = SessionKeyStore()

    @Test
    fun `로컬 identity 생성`() {
        val identity = store.generateLocalIdentity("My Android")

        assertEquals("My Android", identity.deviceName)
        assertEquals(32, identity.publicKeyBase64.decodeBase64().size)
        assertEquals(32, identity.privateKeyBase64.decodeBase64().size)
    }

    @Test
    fun `디바이스 이름 공백 제거`() {
        val identity = store.generateLocalIdentity("  My Android  ")

        assertEquals("My Android", identity.deviceName)
    }

    @Test
    fun `매번 다른 키 쌍 생성`() {
        val identity1 = store.generateLocalIdentity("Device A")
        val identity2 = store.generateLocalIdentity("Device A")

        assertNotEquals(identity1.publicKeyBase64, identity2.publicKeyBase64)
        assertNotEquals(identity1.privateKeyBase64, identity2.privateKeyBase64)
    }

    @Test
    fun `X25519 shared secret 계산 - 양방향 일치`() {
        val identity1 = store.generateLocalIdentity("Device A")
        val identity2 = store.generateLocalIdentity("Device B")

        val secret1to2 = store.deriveSharedSecret(
            identity1.privateKeyBase64,
            identity2.publicKeyBase64,
        )
        val secret2to1 = store.deriveSharedSecret(
            identity2.privateKeyBase64,
            identity1.publicKeyBase64,
        )

        assertArrayEquals(secret1to2, secret2to1)
        assertEquals(32, secret1to2.size)
    }

    @Test
    fun `HKDF-SHA256 - RFC 5869 Test Case 1`() {
        val ikm = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".hexToByteArray()
        val salt = "000102030405060708090a0b0c".hexToByteArray()
        val info = "f0f1f2f3f4f5f6f7f8f9".hexToByteArray()
        val expectedOkm = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865".hexToByteArray()

        val result = store.hkdfSha256(ikm, salt, info, 42)

        assertArrayEquals(expectedOkm, result)
    }

    @Test
    fun `HKDF-SHA256 - RFC 5869 Test Case 2`() {
        val ikm = ("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" +
                "404142434445464748494a4b4c4d4e4f").hexToByteArray()
        val salt = ("606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f" +
                "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f" +
                "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf").hexToByteArray()
        val info = ("b0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
                "d0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
                "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff").hexToByteArray()
        val expectedOkm = ("b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f1d87").hexToByteArray()

        val result = store.hkdfSha256(ikm, salt, info, 82)

        assertArrayEquals(expectedOkm, result)
    }

    @Test
    fun `HKDF-SHA256 - RFC 5869 Test Case 3 (빈 salt)`() {
        val ikm = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".hexToByteArray()
        val salt = ByteArray(0)
        val info = ByteArray(0)
        val expectedOkm = "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8".hexToByteArray()

        val result = store.hkdfSha256(ikm, salt, info, 42)

        assertArrayEquals(expectedOkm, result)
    }

    @Test
    fun `HKDF-SHA256 - 다중 블록 확장`() {
        val ikm = "secret_key".toByteArray()
        val salt = "session123".toByteArray()
        val info = "air-bridge|test|v1".toByteArray()

        val result32 = store.hkdfSha256(ikm, salt, info, 32)
        val result64 = store.hkdfSha256(ikm, salt, info, 64)

        assertEquals(32, result32.size)
        assertEquals(64, result64.size)
        assertArrayEquals(result32, result64.copyOfRange(0, 32))
    }

    @Test
    fun `Base64 인코딩 - padding 없음`() {
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val encoded = bytes.encodeBase64()

        assertEquals("AAECAw", encoded)
    }

    @Test
    fun `Base64 디코딩`() {
        val encoded = "AAECAw"
        val decoded = encoded.decodeBase64()

        assertArrayEquals(byteArrayOf(0x00, 0x01, 0x02, 0x03), decoded)
    }

    @Test
    fun `Base64 왕복 변환`() {
        val original = ByteArray(32) { it.toByte() }
        val roundtrip = original.encodeBase64().decodeBase64()

        assertArrayEquals(original, roundtrip)
    }

    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "hex string은 짝수 길이여야 해요" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
