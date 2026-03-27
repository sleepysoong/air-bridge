import AppKit
import Foundation
import UniformTypeIdentifiers

final class ClipboardPayloadMapper {
    static let maxPayloadBytes = 20 * 1024 * 1024
    private let jpegPasteboardType = NSPasteboard.PasteboardType(UTType.jpeg.identifier)

    func capturePayload(
        from pasteboard: NSPasteboard = .general
    ) throws -> ClipboardPayload? {
        guard let item = pasteboard.pasteboardItems?.first else {
            return nil
        }

        let label = pasteboard.name.rawValue

        if let htmlData = item.data(forType: .html) {
            let htmlValue = String(data: htmlData, encoding: .utf8)
            let textValue = item.string(forType: .string)
            let payload = ClipboardPayload(
                mimeType: "text/html",
                label: label,
                textValue: textValue,
                htmlValue: htmlValue
            )
            try payload.requireSupportedSize()
            return payload
        }

        if let rtfData = item.data(forType: .rtf) {
            let payload = ClipboardPayload(
                mimeType: "text/rtf",
                label: label,
                binaryBase64: rtfData.rawBase64EncodedString
            )
            try payload.requireSupportedSize()
            return payload
        }

        if let pngData = item.data(forType: .png) {
            let payload = ClipboardPayload(
                mimeType: "image/png",
                label: label,
                binaryBase64: pngData.rawBase64EncodedString
            )
            try payload.requireSupportedSize()
            return payload
        }

        if let jpegData = item.data(forType: jpegPasteboardType) {
            let payload = ClipboardPayload(
                mimeType: "image/jpeg",
                label: label,
                binaryBase64: jpegData.rawBase64EncodedString
            )
            try payload.requireSupportedSize()
            return payload
        }

        if let uriString = item.string(forType: .URL) {
            let payload = ClipboardPayload(
                mimeType: "text/uri-list",
                label: label,
                uriList: [uriString]
            )
            try payload.requireSupportedSize()
            return payload
        }

        if let string = item.string(forType: .string) {
            let payload = ClipboardPayload(
                mimeType: "text/plain",
                label: label,
                textValue: string
            )
            try payload.requireSupportedSize()
            return payload
        }

        return nil
    }

    func apply(
        _ payload: ClipboardPayload,
        to pasteboard: NSPasteboard = .general
    ) throws {
        try payload.requireSupportedSize()

        let item = NSPasteboardItem()
        var wroteAnyValue = false

        switch payload.mimeType {
        case "text/plain":
            if let textValue = payload.textValue {
                item.setString(textValue, forType: .string)
                wroteAnyValue = true
            }
        case "text/uri-list":
            if let firstURI = payload.uriList.first {
                item.setString(firstURI, forType: .URL)
                wroteAnyValue = true
            }
        case "text/html":
            if let htmlValue = payload.htmlValue {
                if let htmlData = htmlValue.data(using: .utf8) {
                    item.setData(htmlData, forType: .html)
                    wroteAnyValue = true
                }
                if let textValue = payload.textValue {
                    item.setString(textValue, forType: .string)
                }
            }
        case "text/rtf":
            if let binaryBase64 = payload.binaryBase64 {
                item.setData(try Data(rawBase64Encoded: binaryBase64), forType: .rtf)
                wroteAnyValue = true
            }
        case "image/png":
            if let binaryBase64 = payload.binaryBase64 {
                item.setData(try Data(rawBase64Encoded: binaryBase64), forType: .png)
                wroteAnyValue = true
            }
        case "image/jpeg":
            if let binaryBase64 = payload.binaryBase64 {
                item.setData(try Data(rawBase64Encoded: binaryBase64), forType: jpegPasteboardType)
                wroteAnyValue = true
            }
        default:
            break
        }

        guard wroteAnyValue else {
            throw ClipboardPayloadError.unsupportedPasteboard
        }

        pasteboard.clearContents()
        pasteboard.writeObjects([item])
    }
}
