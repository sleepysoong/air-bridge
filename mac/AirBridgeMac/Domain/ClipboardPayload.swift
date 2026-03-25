import Foundation

enum ClipboardPayloadError: LocalizedError, Equatable {
    case unsupportedPasteboard
    case payloadTooLarge

    var errorDescription: String? {
        switch self {
        case .unsupportedPasteboard:
            return "The current clipboard contents do not contain a supported format."
        case .payloadTooLarge:
            return "The canonical clipboard payload exceeds the 20 MB limit."
        }
    }
}

struct ClipboardPayload: Codable, Equatable {
    let originDeviceID: String
    let createdAt: Date
    let items: [ClipboardPayloadItem]

    var totalBytes: Int {
        items.reduce(0) { $0 + $1.data.count }
    }
}

struct ClipboardPayloadItem: Codable, Equatable, Identifiable {
    let mimeType: String
    let base64Value: String

    var id: String {
        "\(mimeType):\(base64Value.prefix(24))"
    }

    var data: Data {
        (try? Data(rawBase64Encoded: base64Value)) ?? Data()
    }

    static func fromData(_ data: Data, mimeType: String) -> ClipboardPayloadItem {
        ClipboardPayloadItem(
            mimeType: mimeType,
            base64Value: data.rawBase64EncodedString
        )
    }
}
