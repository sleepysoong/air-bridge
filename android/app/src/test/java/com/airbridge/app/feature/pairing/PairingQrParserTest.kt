package com.airbridge.app.feature.pairing

import com.airbridge.app.domain.PairingQrPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingQrParserTest {
    private val parser = PairingQrParser()

    @Test
    fun `JSON 형식 QR 파싱`() {
        val json = """
            {
                "version": 1,
                "relay_base_url": "https://relay.example.com",
                "pairing_session_id": "session123",
                "pairing_secret": "secret456",
                "initiator_device_id": "device789",
                "initiator_name": "My Mac",
                "initiator_public_key": "pubkey_base64"
            }
        """.trimIndent()

        val result = parser.parse(json)

        assertEquals("https://relay.example.com", result.relayBaseUrl)
        assertEquals("session123", result.pairingSessionId)
        assertEquals("secret456", result.pairingSecret)
        assertEquals("device789", result.initiatorDeviceId)
        assertEquals("My Mac", result.initiatorName)
        assertEquals("pubkey_base64", result.initiatorPublicKey)
    }

    @Test
    fun `URI 형식 QR 파싱 - snake_case 필드명`() {
        val uri = "airbridge://pair?relay_base_url=https://relay.example.com" +
                "&pairing_session_id=session123" +
                "&pairing_secret=secret456" +
                "&initiator_device_id=device789" +
                "&initiator_name=My+Mac" +
                "&initiator_public_key=pubkey_base64"

        val result = parser.parse(uri)

        assertEquals("https://relay.example.com", result.relayBaseUrl)
        assertEquals("session123", result.pairingSessionId)
        assertEquals("secret456", result.pairingSecret)
        assertEquals("device789", result.initiatorDeviceId)
        assertEquals("My Mac", result.initiatorName)
        assertEquals("pubkey_base64", result.initiatorPublicKey)
    }

    @Test
    fun `URI 형식 QR 파싱 - camelCase 필드명 fallback`() {
        val uri = "airbridge://pair?relayBaseUrl=https://relay.example.com" +
                "&pairingSessionId=session123" +
                "&pairingSecret=secret456" +
                "&initiatorDeviceId=device789" +
                "&initiatorName=My+Mac" +
                "&initiatorPublicKey=pubkey_base64"

        val result = parser.parse(uri)

        assertEquals("https://relay.example.com", result.relayBaseUrl)
        assertEquals("session123", result.pairingSessionId)
        assertEquals("secret456", result.pairingSecret)
        assertEquals("device789", result.initiatorDeviceId)
        assertEquals("My Mac", result.initiatorName)
        assertEquals("pubkey_base64", result.initiatorPublicKey)
    }

    @Test
    fun `URI 형식 QR 파싱 - 대체 필드명 지원`() {
        val uri = "airbridge://pair?relay_url=https://relay.example.com" +
                "&session_id=session123" +
                "&pairingSecret=secret456" +
                "&public_key=pubkey_base64"

        val result = parser.parse(uri)

        assertEquals("https://relay.example.com", result.relayBaseUrl)
        assertEquals("session123", result.pairingSessionId)
        assertEquals("secret456", result.pairingSecret)
        assertEquals("pubkey_base64", result.initiatorPublicKey)
    }

    @Test
    fun `빈 문자열 거부`() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse("")
        }
    }

    @Test
    fun `공백만 있는 문자열 거부`() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse("   ")
        }
    }

    @Test
    fun `relay_base_url 누락시 거부`() {
        val json = """
            {
                "pairing_session_id": "session123",
                "pairing_secret": "secret456",
                "initiator_public_key": "pubkey_base64"
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(json)
        }
    }

    @Test
    fun `pairing_session_id 누락시 거부`() {
        val json = """
            {
                "relay_base_url": "https://relay.example.com",
                "pairing_secret": "secret456",
                "initiator_public_key": "pubkey_base64"
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(json)
        }
    }

    @Test
    fun `pairing_secret 누락시 거부`() {
        val json = """
            {
                "relay_base_url": "https://relay.example.com",
                "pairing_session_id": "session123",
                "initiator_public_key": "pubkey_base64"
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(json)
        }
    }

    @Test
    fun `initiator_public_key 누락시 거부`() {
        val json = """
            {
                "relay_base_url": "https://relay.example.com",
                "pairing_session_id": "session123",
                "pairing_secret": "secret456"
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(json)
        }
    }

    @Test
    fun `선택 필드는 null 허용`() {
        val json = """
            {
                "relay_base_url": "https://relay.example.com",
                "pairing_session_id": "session123",
                "pairing_secret": "secret456",
                "initiator_public_key": "pubkey_base64"
            }
        """.trimIndent()

        val result = parser.parse(json)

        assertEquals(null, result.initiatorDeviceId)
        assertEquals(null, result.initiatorName)
    }

    @Test
    fun `알 수 없는 JSON 필드는 무시`() {
        val json = """
            {
                "relay_base_url": "https://relay.example.com",
                "pairing_session_id": "session123",
                "pairing_secret": "secret456",
                "initiator_public_key": "pubkey_base64",
                "unknown_field": "should_be_ignored",
                "extra_data": 42
            }
        """.trimIndent()

        val result = parser.parse(json)

        assertEquals("https://relay.example.com", result.relayBaseUrl)
        assertEquals("session123", result.pairingSessionId)
    }
}
