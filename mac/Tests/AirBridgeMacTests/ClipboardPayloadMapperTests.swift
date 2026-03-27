import AppKit
import Foundation
import XCTest
@testable import AirBridgeMac

final class ClipboardPayloadMapperTests: XCTestCase {
    func testApplyAndCapturePlainTextPayloadRoundTrips() throws {
        let mapper = ClipboardPayloadMapper()
        let pasteboard = NSPasteboard(name: NSPasteboard.Name("airbridge.tests.\(UUID().uuidString)"))
        let payload = ClipboardPayload(
            mimeType: "text/plain",
            textValue: "hello"
        )

        try mapper.apply(payload, to: pasteboard)
        let captured = try XCTUnwrap(mapper.capturePayload(from: pasteboard))

        XCTAssertEqual(captured.mimeType, "text/plain")
        XCTAssertEqual(captured.textValue, "hello")
    }

    func testApplyRejectsUnsupportedPayload() {
        let mapper = ClipboardPayloadMapper()
        let pasteboard = NSPasteboard(name: NSPasteboard.Name("airbridge.tests.\(UUID().uuidString)"))
        let payload = ClipboardPayload(
            mimeType: "application/octet-stream",
            binaryBase64: Data([0x01]).rawBase64EncodedString
        )

        XCTAssertThrowsError(try mapper.apply(payload, to: pasteboard)) { error in
            XCTAssertEqual(error as? ClipboardPayloadError, .unsupportedPasteboard)
        }
    }
}
