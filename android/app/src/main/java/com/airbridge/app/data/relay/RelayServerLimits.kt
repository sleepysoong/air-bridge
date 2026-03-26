package com.airbridge.app.data.relay

/**
 * 중계 서버가 적용하는 입력 크기 제한을 클라이언트에서 미리 검증하기 위한 상수들이에요.
 * 
 * 이 제한값들은 server/README.md와 server/architecture.md에 문서화된 서버 계약을 따라야 해요.
 */
object RelayServerLimits {
    /** device_name 최대 길이 (문자 단위) */
    const val MAX_DEVICE_NAME_LENGTH = 128

    /** pairing_secret 최대 길이 (문자 단위) */
    const val MAX_PAIRING_SECRET_LENGTH = 128

    /** content_type 최대 크기 (바이트 단위) */
    const val MAX_CONTENT_TYPE_BYTES = 255

    /** nonce 최대 크기 (바이트 단위) */
    const val MAX_NONCE_BYTES = 64

    /** header_aad 최대 크기 (바이트 단위) */
    const val MAX_HEADER_AAD_BYTES = 16 * 1024 // 16 KiB

    /** ciphertext 최대 크기 (바이트 단위) - 20 MiB + 16 bytes (GCM tag) */
    const val MAX_CIPHERTEXT_BYTES = (20 * 1024 * 1024) + 16

    /** WebSocket 메시지 최대 크기 (바이트 단위) */
    const val MAX_WEBSOCKET_MESSAGE_BYTES = 28 * 1024 * 1024 // 28 MiB
}
