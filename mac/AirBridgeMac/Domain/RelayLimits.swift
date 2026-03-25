import Foundation

enum RelayLimits {
    static let maxPairingRequestBodyBytes = 16 * 1024
    static let maxDeviceNameRunes = 128
    static let maxPairingSecretRunes = 128
    static let x25519PublicKeyBytes = 32
    static let maxContentTypeBytes = 255
    static let maxNonceBytes = 64
    static let maxHeaderAADBytes = 16 * 1024
    static let maxNormalizedPayloadBytes = 20 * 1024 * 1024
    static let maxCiphertextBytes = maxNormalizedPayloadBytes + 16
    static let maxWebSocketMessageBytes = 36 * 1024 * 1024
}

enum RelayValidationError: LocalizedError, Equatable {
    case emptyValue(field: String)
    case valueTooLarge(field: String, limit: Int)
    case invalidPublicKeyLength(expected: Int)

    var errorDescription: String? {
        switch self {
        case .emptyValue(let field):
            return "\(field) must not be empty."
        case .valueTooLarge(let field, let limit):
            return "\(field) exceeds the relay limit of \(limit)."
        case .invalidPublicKeyLength(let expected):
            return "The X25519 public key must be exactly \(expected) bytes."
        }
    }
}

enum RelayInputValidator {
    static func deviceName(_ raw: String) throws -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw RelayValidationError.emptyValue(field: "device_name")
        }

        guard trimmed.count <= RelayLimits.maxDeviceNameRunes else {
            throw RelayValidationError.valueTooLarge(
                field: "device_name",
                limit: RelayLimits.maxDeviceNameRunes
            )
        }

        return trimmed
    }

    static func pairingSecret(_ raw: String) throws -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw RelayValidationError.emptyValue(field: "pairing_secret")
        }

        guard trimmed.count <= RelayLimits.maxPairingSecretRunes else {
            throw RelayValidationError.valueTooLarge(
                field: "pairing_secret",
                limit: RelayLimits.maxPairingSecretRunes
            )
        }

        return trimmed
    }

    static func identifier(_ raw: String, field: String) throws -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw RelayValidationError.emptyValue(field: field)
        }

        return trimmed
    }

    static func publicKey(_ value: Data) throws -> Data {
        guard value.count == RelayLimits.x25519PublicKeyBytes else {
            throw RelayValidationError.invalidPublicKeyLength(expected: RelayLimits.x25519PublicKeyBytes)
        }

        return value
    }

    static func contentType(_ raw: String) throws -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw RelayValidationError.emptyValue(field: "content_type")
        }

        guard trimmed.utf8.count <= RelayLimits.maxContentTypeBytes else {
            throw RelayValidationError.valueTooLarge(
                field: "content_type",
                limit: RelayLimits.maxContentTypeBytes
            )
        }

        return trimmed
    }

    static func opaqueBytes(_ value: Data, field: String, maxBytes: Int) throws {
        guard !value.isEmpty else {
            throw RelayValidationError.emptyValue(field: field)
        }

        guard value.count <= maxBytes else {
            throw RelayValidationError.valueTooLarge(field: field, limit: maxBytes)
        }
    }

    static func envelope(contentType: String, nonce: Data, headerAAD: Data, ciphertext: Data) throws -> String {
        let validatedContentType = try self.contentType(contentType)
        try opaqueBytes(nonce, field: "nonce", maxBytes: RelayLimits.maxNonceBytes)
        try opaqueBytes(headerAAD, field: "header_aad", maxBytes: RelayLimits.maxHeaderAADBytes)
        try opaqueBytes(ciphertext, field: "ciphertext", maxBytes: RelayLimits.maxCiphertextBytes)
        return validatedContentType
    }
}
