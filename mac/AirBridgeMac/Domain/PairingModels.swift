import Foundation

enum AirBridgePlatform: String, Codable, CaseIterable {
    case macOS = "macos"
    case android = "android"
}

enum PairingSessionState: String, Codable {
    case pending
    case ready
    case completed
}

enum RelayChannel: String, Codable {
    case clipboard
    case notification
}

struct PairingDraft: Equatable {
    let relayBaseURL: URL
    let deviceName: String
    let pairingSessionID: String
    let pairingSecret: String
    let localDeviceID: String
    let relayToken: String
    let localPrivateKeyData: Data
    let localPublicKeyData: Data
    let expiresAt: Date
}

struct PairingQRCodePayload: Codable, Equatable {
    let relayURL: URL
    let pairingSessionID: String
    let pairingSecret: String
    let initiatorDeviceID: String
    let initiatorPublicKey: String

    enum CodingKeys: String, CodingKey {
        case relayURL = "relay_url"
        case pairingSessionID = "pairing_session_id"
        case pairingSecret = "pairing_secret"
        case initiatorDeviceID = "initiator_device_id"
        case initiatorPublicKey = "initiator_public_key"
    }
}

struct PairingSessionSnapshot: Codable, Equatable {
    let pairingSessionID: String
    let state: PairingSessionState
    let initiatorDeviceID: String
    let initiatorName: String
    let initiatorPlatform: AirBridgePlatform
    let initiatorPublicKey: Data
    let joinerDeviceID: String?
    let joinerName: String?
    let joinerPlatform: AirBridgePlatform?
    let joinerPublicKey: Data?
    let expiresAt: Date
    let updatedAt: Date
    let completedAt: Date?
}

struct PairingLookupResult: Equatable {
    let snapshot: PairingSessionSnapshot
    let shortAuthenticationString: String?
}

struct PairedDeviceSession: Codable, Equatable {
    let relayBaseURL: URL
    let localDeviceID: String
    let peerDeviceID: String
    let relayToken: String
    let sessionKeyData: Data
    let localPrivateKeyData: Data
    let localPublicKeyData: Data
    let peerPublicKeyData: Data
    let pairedAt: Date
}

struct RelayEnvelope: Equatable {
    let id: String
    let senderDeviceID: String
    let channel: RelayChannel
    let contentType: String
    let nonce: Data
    let headerAAD: Data
    let ciphertext: Data
    let createdAt: Date
    let expiresAt: Date
}

struct RelayServiceErrorPayload: Decodable, Error, Equatable {
    let code: String
    let message: String
}

struct RelayHTTPErrorResponse: Decodable {
    let error: RelayServiceErrorPayload
}

enum Base64CodingError: LocalizedError {
    case invalidValue

    var errorDescription: String? {
        switch self {
        case .invalidValue:
            return "Base64 value is invalid."
        }
    }
}

extension Data {
    var rawBase64EncodedString: String {
        base64EncodedString().replacingOccurrences(of: "=", with: "")
    }

    init(rawBase64Encoded value: String) throws {
        let paddingCount = (4 - value.count % 4) % 4
        let paddedValue = value + String(repeating: "=", count: paddingCount)

        guard let data = Data(base64Encoded: paddedValue) else {
            throw Base64CodingError.invalidValue
        }

        self = data
    }
}

extension JSONEncoder {
    static let airBridge: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }()
}

extension JSONDecoder {
    static let airBridge: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }()
}
