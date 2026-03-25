import Foundation
import XCTest
@testable import AirBridgeMac

final class RelayMessageMapperTests: XCTestCase {
    func testEncodeSendEnvelopeUsesRawBase64() throws {
        let mapper = RelayMessageMapper()
        let message = try mapper.encode(
            .sendEnvelope(
                recipientDeviceID: "dev_peer",
                channel: .clipboard,
                contentType: "application/json",
                nonce: Data([0x01, 0x02, 0x03]),
                headerAAD: Data([0x04, 0x05]),
                ciphertext: Data([0x06, 0x07, 0x08, 0x09])
            )
        )

        XCTAssertTrue(message.contains("\"recipient_device_id\":\"dev_peer\""))
        XCTAssertTrue(message.contains("\"nonce\":\"AQID\""))
        XCTAssertFalse(message.contains("="))
    }

    func testDecodeEnvelopeRoundTripsValues() throws {
        let mapper = RelayMessageMapper()
        let rawMessage = """
        {"type":"envelope","envelope_id":"env_1","sender_device_id":"dev_sender","channel":"clipboard","content_type":"application/json","nonce":"AQID","header_aad":"BAU","ciphertext":"BgcICQ","created_at":"2026-03-26T00:00:00Z","expires_at":"2026-03-27T00:00:00Z"}
        """

        let decoded = try mapper.decode(rawMessage)

        guard case .envelope(let envelope) = decoded else {
            return XCTFail("Expected envelope message.")
        }

        XCTAssertEqual(envelope.id, "env_1")
        XCTAssertEqual(envelope.senderDeviceID, "dev_sender")
        XCTAssertEqual(envelope.channel, .clipboard)
        XCTAssertEqual(envelope.nonce, Data([0x01, 0x02, 0x03]))
    }

    func testDecodeRejectsMissingConnectedFields() {
        let mapper = RelayMessageMapper()

        XCTAssertThrowsError(try mapper.decode(#"{"type":"connected","device_id":"dev_only"}"#)) { error in
            XCTAssertEqual(error as? RelayMessageMapperError, .invalidServerMessage)
        }
    }
}
