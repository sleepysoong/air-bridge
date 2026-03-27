import Foundation

enum ClipboardPayloadError: LocalizedError, Equatable {
    case unsupportedPasteboard
    case payloadTooLarge

    var errorDescription: String? {
        switch self {
        case .unsupportedPasteboard:
            return "현재 클립보드에 지원되는 형식이 없어요."
        case .payloadTooLarge:
            return "클립보드 페이로드가 20MB 제한을 초과했어요."
        }
    }
}

struct ClipboardPayload: Codable, Equatable {
    let schemaVersion: Int
    let mimeType: String
    let label: String?
    let textValue: String?
    let htmlValue: String?
    let uriList: [String]
    let binaryBase64: String?

    enum CodingKeys: String, CodingKey {
        case schemaVersion = "schema_version"
        case mimeType = "mime_type"
        case label
        case textValue = "text_value"
        case htmlValue = "html_value"
        case uriList = "uri_list"
        case binaryBase64 = "binary_base64"
    }

    init(
        schemaVersion: Int = 1,
        mimeType: String,
        label: String? = nil,
        textValue: String? = nil,
        htmlValue: String? = nil,
        uriList: [String] = [],
        binaryBase64: String? = nil
    ) {
        self.schemaVersion = schemaVersion
        self.mimeType = mimeType
        self.label = label
        self.textValue = textValue
        self.htmlValue = htmlValue
        self.uriList = uriList
        self.binaryBase64 = binaryBase64
    }

    var estimatedPayloadBytes: Int {
        if let binaryBase64 {
            return (try? Data(rawBase64Encoded: binaryBase64).count) ?? 0
        }

        if let htmlValue {
            return htmlValue.lengthOfBytes(using: .utf8)
        }

        if let textValue {
            return textValue.lengthOfBytes(using: .utf8)
        }

        if !uriList.isEmpty {
            return uriList.joined(separator: "\n").lengthOfBytes(using: .utf8)
        }

        return 0
    }

    func requireSupportedSize() throws {
        guard estimatedPayloadBytes <= RelayLimits.maxNormalizedPayloadBytes else {
            throw ClipboardPayloadError.payloadTooLarge
        }
    }
}
