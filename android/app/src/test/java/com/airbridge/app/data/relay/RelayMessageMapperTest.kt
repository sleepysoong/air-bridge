package com.airbridge.app.data.relay

import com.airbridge.app.domain.BridgeChannel
import com.airbridge.app.domain.EncryptedEnvelope
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class RelayMessageMapperTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `EncryptedEnvelope을 wire 형식으로 변환`() {
        val nonce = ByteArray(12) { it.toByte() }
        val headerAad = "header_content".toByteArray()
        val ciphertext = "encrypted_data".toByteArray()

        val envelope = EncryptedEnvelope(
            channel = BridgeChannel.CLIPBOARD,
            contentType = "text/plain",
            nonce = nonce,
            headerAad = headerAad,
            ciphertext = ciphertext,
        )

        val wireMessage = envelope.toWire(recipientDeviceId = "device_b")

        assertEquals("send_envelope", wireMessage.type)
        assertEquals("device_b", wireMessage.recipientDeviceId)
        assertEquals("clipboard", wireMessage.channel)
        assertEquals("text/plain", wireMessage.contentType)
        assertEquals(nonce.encodeBase64NoPadding(), wireMessage.nonce)
        assertEquals(headerAad.encodeBase64NoPadding(), wireMessage.headerAad)
        assertEquals(ciphertext.encodeBase64NoPadding(), wireMessage.ciphertext)
    }

    @Test
    fun `notification 채널도 wire 변환`() {
        val envelope = EncryptedEnvelope(
            channel = BridgeChannel.NOTIFICATION,
            contentType = "application/json",
            nonce = ByteArray(12),
            headerAad = ByteArray(10),
            ciphertext = ByteArray(20),
        )

        val wireMessage = envelope.toWire(recipientDeviceId = "device_xyz")

        assertEquals("notification", wireMessage.channel)
        assertEquals("device_xyz", wireMessage.recipientDeviceId)
    }

    @Test
    fun `connected 서버 메시지를 이벤트로 변환`() {
        val serverMessage = RelayServerMessage(
            type = "connected",
            deviceId = "my_device",
            peerDeviceId = "peer_device",
        )

        val event = serverMessage.toEvent()

        assertEquals(RelayServerEvent.Connected::class, event::class)
        val connected = event as RelayServerEvent.Connected
        assertEquals("my_device", connected.deviceId)
        assertEquals("peer_device", connected.peerDeviceId)
    }

    @Test
    fun `pong 서버 메시지를 이벤트로 변환`() {
        val serverMessage = RelayServerMessage(type = "pong")

        val event = serverMessage.toEvent()

        assertEquals(RelayServerEvent.Pong, event)
    }

    @Test
    fun `envelope 서버 메시지를 이벤트로 변환`() {
        val nonce = ByteArray(12) { it.toByte() }
        val headerAad = "header".toByteArray()
        val ciphertext = "data".toByteArray()

        val serverMessage = RelayServerMessage(
            type = "envelope",
            envelopeId = "env123",
            senderDeviceId = "device_a",
            channel = "clipboard",
            contentType = "text/plain",
            nonce = nonce.encodeBase64NoPadding(),
            headerAad = headerAad.encodeBase64NoPadding(),
            ciphertext = ciphertext.encodeBase64NoPadding(),
        )

        val event = serverMessage.toEvent()

        assertEquals(RelayServerEvent.Envelope::class, event::class)
        val envelope = (event as RelayServerEvent.Envelope).envelope
        assertEquals("env123", envelope.envelopeId)
        assertEquals("device_a", envelope.senderDeviceId)
        assertEquals(BridgeChannel.CLIPBOARD, envelope.channel)
        assertEquals("text/plain", envelope.contentType)
        assertArrayEquals(nonce, envelope.nonce)
        assertArrayEquals(headerAad, envelope.headerAad)
        assertArrayEquals(ciphertext, envelope.ciphertext)
    }

    @Test
    fun `envelope 서버 메시지 - notification 채널`() {
        val serverMessage = RelayServerMessage(
            type = "envelope",
            envelopeId = "env456",
            senderDeviceId = "device_b",
            channel = "notification",
            contentType = "application/json",
            nonce = ByteArray(12).encodeBase64NoPadding(),
            headerAad = ByteArray(10).encodeBase64NoPadding(),
            ciphertext = ByteArray(20).encodeBase64NoPadding(),
        )

        val event = serverMessage.toEvent()

        val envelope = (event as RelayServerEvent.Envelope).envelope
        assertEquals(BridgeChannel.NOTIFICATION, envelope.channel)
    }

    @Test
    fun `error 서버 메시지를 이벤트로 변환`() {
        val serverMessage = RelayServerMessage(
            type = "error",
            code = "invalid_token",
            message = "인증 토큰이 유효하지 않아요",
        )

        val event = serverMessage.toEvent()

        assertEquals(RelayServerEvent.Error::class, event::class)
        val error = event as RelayServerEvent.Error
        assertEquals("invalid_token", error.code)
        assertEquals("인증 토큰이 유효하지 않아요", error.message)
    }

    @Test
    fun `error 서버 메시지 - code와 message 누락시 기본값 사용`() {
        val serverMessage = RelayServerMessage(type = "error")

        val event = serverMessage.toEvent()

        val error = event as RelayServerEvent.Error
        assertEquals("error", error.code)
        assertEquals("relay websocket 오류가 발생했어요", error.message)
    }

    @Test
    fun `알 수 없는 서버 메시지 타입은 Error 이벤트로 변환`() {
        val serverMessage = RelayServerMessage(type = "unknown_type")

        val event = serverMessage.toEvent()

        assertEquals(RelayServerEvent.Error::class, event::class)
        val error = event as RelayServerEvent.Error
        assertEquals("unknown_message_type", error.code)
        assertEquals("알 수 없는 서버 메시지를 받았어요: unknown_type", error.message)
    }

    @Test
    fun `ping 메시지 생성`() {
        val ping = RelayPingMessage()

        assertEquals("ping", ping.type)
    }

    @Test
    fun `ack_envelope 메시지 생성`() {
        val ack = RelayAckEnvelopeMessage(envelopeId = "env789")

        assertEquals("ack_envelope", ack.type)
        assertEquals("env789", ack.envelopeId)
    }

    @Test
    fun `Base64 인코딩 - padding 없음`() {
        val data = byteArrayOf(0x00, 0x01, 0x02)
        val encoded = data.encodeBase64NoPadding()

        assertEquals("AAEC", encoded)
    }

    private fun ByteArray.encodeBase64NoPadding(): String {
        return Base64.getEncoder().withoutPadding().encodeToString(this)
    }
}
