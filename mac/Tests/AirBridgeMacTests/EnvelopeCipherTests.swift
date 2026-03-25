import Foundation
import XCTest
@testable import AirBridgeMac

final class EnvelopeCipherTests: XCTestCase {
    func testEncryptAndDecryptRoundTrip() throws {
        let cipher = EnvelopeCipher()
        let sessionKey = Data(repeating: 0x22, count: 32)
        let payload = TestPayload(message: "hello")

        let sealed = try cipher.encrypt(
            payload,
            channel: .clipboard,
            contentType: "application/json",
            senderDeviceID: "dev_sender",
            recipientDeviceID: "dev_recipient",
            sessionKeyData: sessionKey
        )

        let envelope = RelayEnvelope(
            id: "env_1",
            senderDeviceID: "dev_sender",
            channel: .clipboard,
            contentType: "application/json",
            nonce: sealed.nonce,
            headerAAD: sealed.headerAAD,
            ciphertext: sealed.ciphertext,
            createdAt: Date(),
            expiresAt: Date().addingTimeInterval(60)
        )

        let decrypted = try cipher.decrypt(
            TestPayload.self,
            envelope: envelope,
            expectedRecipientDeviceID: "dev_recipient",
            sessionKeyData: sessionKey
        )

        XCTAssertEqual(decrypted, payload)
    }

    func testDecryptRejectsRecipientMismatch() throws {
        let cipher = EnvelopeCipher()
        let sessionKey = Data(repeating: 0x22, count: 32)
        let payload = TestPayload(message: "hello")
        let sealed = try cipher.encrypt(
            payload,
            channel: .clipboard,
            contentType: "application/json",
            senderDeviceID: "dev_sender",
            recipientDeviceID: "dev_recipient",
            sessionKeyData: sessionKey
        )

        let envelope = RelayEnvelope(
            id: "env_1",
            senderDeviceID: "dev_sender",
            channel: .clipboard,
            contentType: "application/json",
            nonce: sealed.nonce,
            headerAAD: sealed.headerAAD,
            ciphertext: sealed.ciphertext,
            createdAt: Date(),
            expiresAt: Date().addingTimeInterval(60)
        )

        XCTAssertThrowsError(
            try cipher.decrypt(
                TestPayload.self,
                envelope: envelope,
                expectedRecipientDeviceID: "dev_other",
                sessionKeyData: sessionKey
            )
        ) { error in
            XCTAssertEqual(error as? EnvelopeCipherError, .aadMismatch)
        }
    }

    func testEncryptRejectsInvalidSessionKeyLength() {
        let cipher = EnvelopeCipher()

        XCTAssertThrowsError(
            try cipher.encrypt(
                TestPayload(message: "hello"),
                channel: .clipboard,
                contentType: "application/json",
                senderDeviceID: "dev_sender",
                recipientDeviceID: "dev_recipient",
                sessionKeyData: Data(repeating: 0x11, count: 31)
            )
        ) { error in
            XCTAssertEqual(error as? EnvelopeCipherError, .invalidSessionKeyLength)
        }
    }
}

private struct TestPayload: Codable, Equatable {
    let message: String
}
