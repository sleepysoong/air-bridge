import Foundation
import XCTest
@testable import AirBridgeMac

final class RelayInputValidatorTests: XCTestCase {
    func testDeviceNameTrimsWhitespace() throws {
        let validated = try RelayInputValidator.deviceName("  AirBridge Mac  ")
        XCTAssertEqual(validated, "AirBridge Mac")
    }

    func testDeviceNameRejectsEmptyValue() {
        XCTAssertThrowsError(try RelayInputValidator.deviceName("   ")) { error in
            XCTAssertEqual(error as? RelayValidationError, .emptyValue(field: "device_name"))
        }
    }

    func testEnvelopeRejectsOversizedCiphertext() {
        let ciphertext = Data(repeating: 0x01, count: RelayLimits.maxCiphertextBytes + 1)

        XCTAssertThrowsError(
            try RelayInputValidator.envelope(
                contentType: "application/json",
                nonce: Data([0x00]),
                headerAAD: Data([0x01]),
                ciphertext: ciphertext
            )
        ) { error in
            XCTAssertEqual(
                error as? RelayValidationError,
                .valueTooLarge(field: "ciphertext", limit: RelayLimits.maxCiphertextBytes)
            )
        }
    }

    func testPublicKeyRejectsWrongLength() {
        XCTAssertThrowsError(try RelayInputValidator.publicKey(Data(repeating: 0x01, count: 31))) { error in
            XCTAssertEqual(
                error as? RelayValidationError,
                .invalidPublicKeyLength(expected: RelayLimits.x25519PublicKeyBytes)
            )
        }
    }
}
