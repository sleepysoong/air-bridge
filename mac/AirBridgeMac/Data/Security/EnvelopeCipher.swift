import CryptoKit
import Foundation

enum EnvelopeCipherError: LocalizedError, Equatable {
    case invalidCiphertext
    case aadMismatch
    case unsupportedEnvelopeVersion
    case invalidSessionKeyLength

    var errorDescription: String? {
        switch self {
        case .invalidCiphertext:
            return "The encrypted envelope is invalid."
        case .aadMismatch:
            return "The encrypted envelope metadata did not match the expected peer information."
        case .unsupportedEnvelopeVersion:
            return "The encrypted envelope version is not supported."
        case .invalidSessionKeyLength:
            return "The AirBridge session key must be 32 bytes."
        }
    }
}

struct SealedRelayEnvelope: Equatable {
    let nonce: Data
    let headerAAD: Data
    let ciphertext: Data
}

private struct EnvelopeAdditionalData: Codable {
    let version: Int
    let senderDeviceID: String
    let recipientDeviceID: String
    let channel: RelayChannel
    let contentType: String

    enum CodingKeys: String, CodingKey {
        case version
        case senderDeviceID = "sender_device_id"
        case recipientDeviceID = "recipient_device_id"
        case channel
        case contentType = "content_type"
    }
}

struct EnvelopeCipher {
    func encrypt<Payload: Encodable>(
        _ payload: Payload,
        channel: RelayChannel,
        contentType: String,
        senderDeviceID: String,
        recipientDeviceID: String,
        sessionKeyData: Data
    ) throws -> SealedRelayEnvelope {
        guard sessionKeyData.count == 32 else {
            throw EnvelopeCipherError.invalidSessionKeyLength
        }

        let validatedContentType = try RelayInputValidator.contentType(contentType)
        let validatedSenderDeviceID = try RelayInputValidator.identifier(senderDeviceID, field: "sender_device_id")
        let validatedRecipientDeviceID = try RelayInputValidator.identifier(recipientDeviceID, field: "recipient_device_id")
        let payloadData = try JSONEncoder.airBridge.encode(payload)
        let aad = try JSONEncoder.airBridge.encode(
            EnvelopeAdditionalData(
                version: 1,
                senderDeviceID: validatedSenderDeviceID,
                recipientDeviceID: validatedRecipientDeviceID,
                channel: channel,
                contentType: validatedContentType
            )
        )
        let key = SymmetricKey(data: sessionKeyData)
        let sealedBox = try AES.GCM.seal(payloadData, using: key, authenticating: aad)
        let ciphertextWithTag = sealedBox.ciphertext + sealedBox.tag
        _ = try RelayInputValidator.envelope(
            contentType: validatedContentType,
            nonce: Data(sealedBox.nonce),
            headerAAD: aad,
            ciphertext: ciphertextWithTag
        )

        return SealedRelayEnvelope(
            nonce: Data(sealedBox.nonce),
            headerAAD: aad,
            ciphertext: ciphertextWithTag
        )
    }

    func decrypt<Payload: Decodable>(
        _ payloadType: Payload.Type,
        envelope: RelayEnvelope,
        expectedRecipientDeviceID: String,
        sessionKeyData: Data
    ) throws -> Payload {
        guard sessionKeyData.count == 32 else {
            throw EnvelopeCipherError.invalidSessionKeyLength
        }

        let validatedExpectedRecipientDeviceID = try RelayInputValidator.identifier(
            expectedRecipientDeviceID,
            field: "recipient_device_id"
        )
        _ = try RelayInputValidator.envelope(
            contentType: envelope.contentType,
            nonce: envelope.nonce,
            headerAAD: envelope.headerAAD,
            ciphertext: envelope.ciphertext
        )

        let aad = try JSONDecoder.airBridge.decode(EnvelopeAdditionalData.self, from: envelope.headerAAD)
        guard aad.version == 1 else {
            throw EnvelopeCipherError.unsupportedEnvelopeVersion
        }

        guard aad.recipientDeviceID == validatedExpectedRecipientDeviceID,
              aad.senderDeviceID == envelope.senderDeviceID,
              aad.channel == envelope.channel,
              aad.contentType == envelope.contentType else {
            throw EnvelopeCipherError.aadMismatch
        }

        guard envelope.ciphertext.count >= 16 else {
            throw EnvelopeCipherError.invalidCiphertext
        }

        let ciphertext = envelope.ciphertext.dropLast(16)
        let tag = envelope.ciphertext.suffix(16)
        let key = SymmetricKey(data: sessionKeyData)
        let sealedBox = try AES.GCM.SealedBox(
            nonce: AES.GCM.Nonce(data: envelope.nonce),
            ciphertext: ciphertext,
            tag: tag
        )
        let plaintext = try AES.GCM.open(sealedBox, using: key, authenticating: envelope.headerAAD)
        return try JSONDecoder.airBridge.decode(payloadType, from: plaintext)
    }
}
