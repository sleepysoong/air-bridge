import AppKit
import Foundation
import XCTest
@testable import AirBridgeMac

final class ClipboardPayloadMapperTests: XCTestCase {
    func testApplyAndCapturePlainTextPayloadRoundTrips() throws {
        let mapper = ClipboardPayloadMapper()
        let pasteboard = NSPasteboard(name: NSPasteboard.Name("airbridge.tests.\(UUID().uuidString)"))
        let payload = ClipboardPayload(
            originDeviceID: "dev_mac",
            createdAt: Date(timeIntervalSince1970: 0),
            items: [
                .fromData(Data("hello".utf8), mimeType: "text/plain"),
            ]
        )

        try mapper.apply(payload, to: pasteboard)
        let captured = try XCTUnwrap(mapper.capturePayload(originDeviceID: "dev_mac", from: pasteboard))

        XCTAssertEqual(captured.items.first?.mimeType, "text/plain")
        XCTAssertEqual(String(data: captured.items.first?.data ?? Data(), encoding: .utf8), "hello")
    }

    func testApplyRejectsUnsupportedPayload() {
        let mapper = ClipboardPayloadMapper()
        let pasteboard = NSPasteboard(name: NSPasteboard.Name("airbridge.tests.\(UUID().uuidString)"))
        let payload = ClipboardPayload(
            originDeviceID: "dev_mac",
            createdAt: Date(timeIntervalSince1970: 0),
            items: [
                ClipboardPayloadItem(mimeType: "application/octet-stream", base64Value: Data([0x01]).rawBase64EncodedString),
            ]
        )

        XCTAssertThrowsError(try mapper.apply(payload, to: pasteboard)) { error in
            XCTAssertEqual(error as? ClipboardPayloadError, .unsupportedPasteboard)
        }
    }
}
