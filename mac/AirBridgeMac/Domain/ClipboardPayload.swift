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
