import AppKit
import Foundation
import UniformTypeIdentifiers

final class ClipboardPayloadMapper {
    static let maxPayloadBytes = 20 * 1024 * 1024
    private let jpegPasteboardType = NSPasteboard.PasteboardType(UTType.jpeg.identifier)

    func capturePayload(
        originDeviceID: String,
        from pasteboard: NSPasteboard = .general
    ) throws -> ClipboardPayload? {
        guard let item = pasteboard.pasteboardItems?.first else {
            return nil
        }

        var payloadItems: [ClipboardPayloadItem] = []

        if let string = item.string(forType: .string), let data = string.data(using: .utf8) {
            payloadItems.append(.fromData(data, mimeType: "text/plain"))
        }

        if let uriList = item.string(forType: .URL), let data = uriList.data(using: .utf8) {
            payloadItems.append(.fromData(data, mimeType: "text/uri-list"))
        }

        if let htmlData = item.data(forType: .html) {
            payloadItems.append(.fromData(htmlData, mimeType: "text/html"))
        }

        if let rtfData = item.data(forType: .rtf) {
            payloadItems.append(.fromData(rtfData, mimeType: "text/rtf"))
        }

        if let pngData = item.data(forType: .png) {
            payloadItems.append(.fromData(pngData, mimeType: "image/png"))
        }

        if let jpegData = item.data(forType: jpegPasteboardType) {
            payloadItems.append(.fromData(jpegData, mimeType: "image/jpeg"))
        }

        guard !payloadItems.isEmpty else {
            return nil
        }

        let payload = ClipboardPayload(
            originDeviceID: originDeviceID,
            createdAt: Date(),
            items: payloadItems
        )

        guard payload.totalBytes <= Self.maxPayloadBytes else {
            throw ClipboardPayloadError.payloadTooLarge
        }

        return payload
    }

    func apply(
        _ payload: ClipboardPayload,
        to pasteboard: NSPasteboard = .general
    ) throws {
        guard !payload.items.isEmpty else {
            throw ClipboardPayloadError.unsupportedPasteboard
        }

        let item = NSPasteboardItem()
        var wroteAnyValue = false

        for payloadItem in payload.items {
            let data = payloadItem.data

            switch payloadItem.mimeType {
            case "text/plain":
                if let string = String(data: data, encoding: .utf8) {
                    item.setString(string, forType: .string)
                    wroteAnyValue = true
                }
            case "text/uri-list":
                if let string = String(data: data, encoding: .utf8) {
                    item.setString(string, forType: .URL)
                    wroteAnyValue = true
                }
            case "text/html":
                item.setData(data, forType: .html)
                wroteAnyValue = true
            case "text/rtf":
                item.setData(data, forType: .rtf)
                wroteAnyValue = true
            case "image/png":
                item.setData(data, forType: .png)
                wroteAnyValue = true
            case "image/jpeg":
                item.setData(data, forType: jpegPasteboardType)
                wroteAnyValue = true
            default:
                break
            }
        }

        guard wroteAnyValue else {
            throw ClipboardPayloadError.unsupportedPasteboard
        }

        pasteboard.clearContents()
        pasteboard.writeObjects([item])
    }
}
