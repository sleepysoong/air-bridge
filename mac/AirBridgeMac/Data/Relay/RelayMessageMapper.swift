import Foundation

enum RelayMessageMapperError: LocalizedError, Equatable {
    case invalidServerMessage
    case invalidEnvelope

    var errorDescription: String? {
        switch self {
        case .invalidServerMessage:
            return "The relay sent an invalid WebSocket message."
        case .invalidEnvelope:
            return "The relay sent an invalid envelope."
        }
    }
}

enum RelayClientMessage {
    case ping
    case sendEnvelope(
        recipientDeviceID: String,
        channel: RelayChannel,
        contentType: String,
        nonce: Data,
        headerAAD: Data,
        ciphertext: Data
    )
    case acknowledgeEnvelope(envelopeID: String)
}

enum RelayServerMessage: Equatable {
    case connected(deviceID: String, peerDeviceID: String)
    case pong
    case envelope(RelayEnvelope)
    case error(code: String, message: String)
}

private struct RelayClientMessageDTO: Encodable {
    let type: String
    let envelopeID: String?
    let recipientDeviceID: String?
    let channel: String?
    let contentType: String?
    let nonce: String?
    let headerAAD: String?
    let ciphertext: String?

    enum CodingKeys: String, CodingKey {
        case type
        case envelopeID = "envelope_id"
        case recipientDeviceID = "recipient_device_id"
        case channel
        case contentType = "content_type"
        case nonce
        case headerAAD = "header_aad"
        case ciphertext
    }
}

private struct RelayServerMessageDTO: Decodable {
    let type: String
    let code: String?
    let message: String?
    let deviceID: String?
    let peerDeviceID: String?
    let envelopeID: String?
    let senderDeviceID: String?
    let channel: String?
    let contentType: String?
    let nonce: String?
    let headerAAD: String?
    let ciphertext: String?
    let createdAt: Date?
    let expiresAt: Date?

    enum CodingKeys: String, CodingKey {
        case type
        case code
        case message
        case deviceID = "device_id"
        case peerDeviceID = "peer_device_id"
        case envelopeID = "envelope_id"
        case senderDeviceID = "sender_device_id"
        case channel
        case contentType = "content_type"
        case nonce
        case headerAAD = "header_aad"
        case ciphertext
        case createdAt = "created_at"
        case expiresAt = "expires_at"
    }
}

struct RelayMessageMapper {
    func encode(_ message: RelayClientMessage) throws -> String {
        let dto: RelayClientMessageDTO

        switch message {
        case .ping:
            dto = RelayClientMessageDTO(
                type: "ping",
                envelopeID: nil,
                recipientDeviceID: nil,
                channel: nil,
                contentType: nil,
                nonce: nil,
                headerAAD: nil,
                ciphertext: nil
            )
        case .acknowledgeEnvelope(let envelopeID):
            dto = RelayClientMessageDTO(
                type: "ack_envelope",
                envelopeID: envelopeID,
                recipientDeviceID: nil,
                channel: nil,
                contentType: nil,
                nonce: nil,
                headerAAD: nil,
                ciphertext: nil
            )
        case .sendEnvelope(
            let recipientDeviceID,
            let channel,
            let contentType,
            let nonce,
            let headerAAD,
            let ciphertext
        ):
            let validatedRecipientDeviceID = try RelayInputValidator.identifier(
                recipientDeviceID,
                field: "recipient_device_id"
            )
            let validatedContentType = try RelayInputValidator.envelope(
                contentType: contentType,
                nonce: nonce,
                headerAAD: headerAAD,
                ciphertext: ciphertext
            )

            dto = RelayClientMessageDTO(
                type: "send_envelope",
                envelopeID: nil,
                recipientDeviceID: validatedRecipientDeviceID,
                channel: channel.rawValue,
                contentType: validatedContentType,
                nonce: nonce.rawBase64EncodedString,
                headerAAD: headerAAD.rawBase64EncodedString,
                ciphertext: ciphertext.rawBase64EncodedString
            )
        }

        return String(decoding: try JSONEncoder.airBridge.encode(dto), as: UTF8.self)
    }

    func decode(_ text: String) throws -> RelayServerMessage {
        let dto = try JSONDecoder.airBridge.decode(RelayServerMessageDTO.self, from: Data(text.utf8))

        switch dto.type {
        case "connected":
            guard let deviceID = dto.deviceID,
                  let peerDeviceID = dto.peerDeviceID else {
                throw RelayMessageMapperError.invalidServerMessage
            }

            return .connected(
                deviceID: deviceID,
                peerDeviceID: peerDeviceID
            )
        case "pong":
            return .pong
        case "error":
            return .error(
                code: dto.code ?? "unknown_error",
                message: dto.message ?? "Unknown WebSocket error."
            )
        case "envelope":
            guard let envelopeID = dto.envelopeID,
                  let senderDeviceID = dto.senderDeviceID,
                  let channelRawValue = dto.channel,
                  let channel = RelayChannel(rawValue: channelRawValue),
                  let contentType = dto.contentType,
                  let nonceValue = dto.nonce,
                  let headerAADValue = dto.headerAAD,
                  let ciphertextValue = dto.ciphertext,
                  let createdAt = dto.createdAt,
                  let expiresAt = dto.expiresAt else {
                throw RelayMessageMapperError.invalidEnvelope
            }

            let nonce = try Data(rawBase64Encoded: nonceValue)
            let headerAAD = try Data(rawBase64Encoded: headerAADValue)
            let ciphertext = try Data(rawBase64Encoded: ciphertextValue)
            let validatedContentType = try RelayInputValidator.envelope(
                contentType: contentType,
                nonce: nonce,
                headerAAD: headerAAD,
                ciphertext: ciphertext
            )

            return .envelope(
                RelayEnvelope(
                    id: envelopeID,
                    senderDeviceID: senderDeviceID,
                    channel: channel,
                    contentType: validatedContentType,
                    nonce: nonce,
                    headerAAD: headerAAD,
                    ciphertext: ciphertext,
                    createdAt: createdAt,
                    expiresAt: expiresAt
                )
            )
        default:
            return .error(code: "unknown_message_type", message: "Unknown WebSocket message type.")
        }
    }
}
