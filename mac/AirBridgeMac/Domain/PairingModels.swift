import Foundation

enum AirBridgePlatform: String, Codable, CaseIterable {
    case macOS = "macos"
    case android = "android"
}

enum PairingSessionState: String, Codable {
    case pending
    case completed

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let rawValue = try container.decode(String.self)

        switch rawValue {
        case "pending":
            self = .pending
        case "ready", "completed":
            self = .completed
        default:
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "지원하지 않는 페어링 상태예요: \(rawValue)")
        }
    }
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
    let relayAddresses: [String]
    let pairingSessionID: String
    let pairingSecret: String
    let initiatorDeviceID: String
    let initiatorPublicKey: String

    enum CodingKeys: String, CodingKey {
        case relayAddresses = "relay_addresses"
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

struct PairedDeviceSession: Codable, Equatable {
    let relayBaseURL: URL
    let pairingSessionID: String
    let localDeviceID: String
    let peerDeviceID: String
    let peerDeviceName: String
    let relayToken: String
    let sessionKeyData: Data
    let localPrivateKeyData: Data
    let localPublicKeyData: Data
    let peerPublicKeyData: Data
    let pairedAt: Date

    enum CodingKeys: String, CodingKey {
        case relayBaseURL
        case pairingSessionID
        case localDeviceID
        case peerDeviceID
        case peerDeviceName
        case relayToken
        case sessionKeyData
        case localPrivateKeyData
        case localPublicKeyData
        case peerPublicKeyData
        case pairedAt
    }

    init(
        relayBaseURL: URL,
        pairingSessionID: String,
        localDeviceID: String,
        peerDeviceID: String,
        peerDeviceName: String,
        relayToken: String,
        sessionKeyData: Data,
        localPrivateKeyData: Data,
        localPublicKeyData: Data,
        peerPublicKeyData: Data,
        pairedAt: Date
    ) {
        self.relayBaseURL = relayBaseURL
        self.pairingSessionID = pairingSessionID
        self.localDeviceID = localDeviceID
        self.peerDeviceID = peerDeviceID
        self.peerDeviceName = peerDeviceName
        self.relayToken = relayToken
        self.sessionKeyData = sessionKeyData
        self.localPrivateKeyData = localPrivateKeyData
        self.localPublicKeyData = localPublicKeyData
        self.peerPublicKeyData = peerPublicKeyData
        self.pairedAt = pairedAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        relayBaseURL = try container.decode(URL.self, forKey: .relayBaseURL)
        pairingSessionID = try container.decodeIfPresent(String.self, forKey: .pairingSessionID) ?? ""
        localDeviceID = try container.decode(String.self, forKey: .localDeviceID)
        peerDeviceID = try container.decode(String.self, forKey: .peerDeviceID)
        peerDeviceName = try container.decodeIfPresent(String.self, forKey: .peerDeviceName) ?? peerDeviceID
        relayToken = try container.decode(String.self, forKey: .relayToken)
        sessionKeyData = try container.decode(Data.self, forKey: .sessionKeyData)
        localPrivateKeyData = try container.decode(Data.self, forKey: .localPrivateKeyData)
        localPublicKeyData = try container.decode(Data.self, forKey: .localPublicKeyData)
        peerPublicKeyData = try container.decode(Data.self, forKey: .peerPublicKeyData)
        pairedAt = try container.decode(Date.self, forKey: .pairedAt)
    }
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
            return "Base64 값이 올바르지 않아요."
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
