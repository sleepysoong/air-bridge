import CryptoKit
import Foundation
import XCTest
@testable import AirBridgeMac

@MainActor
final class PairingCoordinatorTests: XCTestCase {
    func testStartPairingTrimsDeviceNameAndGeneratesLocalKeys() async throws {
        let fakeClient = FakeRelayPairingClient()
        fakeClient.createResponse = CreatePairingSessionResponse(
            pairingSessionID: "ps_1",
            pairingSecret: "prs_1",
            initiatorDeviceID: "dev_1",
            initiatorRelayToken: "rt_1",
            expiresAt: Date(timeIntervalSince1970: 0)
        )
        let coordinator = PairingCoordinator { _ in fakeClient }

        let draft = try await coordinator.startPairing(
            relayBaseURL: URL(string: "http://127.0.0.1:8080")!,
            deviceName: "  AirBridge Mac  "
        )

        XCTAssertEqual(draft.deviceName, "AirBridge Mac")
        XCTAssertEqual(draft.localPublicKeyData.count, RelayLimits.x25519PublicKeyBytes)
        XCTAssertEqual(draft.localPrivateKeyData.count, RelayLimits.x25519PublicKeyBytes)
    }

    func testLookupProducesSixDigitVerificationCode() async throws {
        let fakeClient = FakeRelayPairingClient()
        let localPrivateKey = Curve25519.KeyAgreement.PrivateKey()
        let peerPrivateKey = Curve25519.KeyAgreement.PrivateKey()
        let coordinator = PairingCoordinator { _ in fakeClient }
        let draft = PairingDraft(
            relayBaseURL: URL(string: "http://127.0.0.1:8080")!,
            deviceName: "AirBridge Mac",
            pairingSessionID: "ps_1",
            pairingSecret: "prs_1",
            localDeviceID: "dev_1",
            relayToken: "rt_1",
            localPrivateKeyData: localPrivateKey.rawRepresentation,
            localPublicKeyData: localPrivateKey.publicKey.rawRepresentation,
            expiresAt: Date(timeIntervalSince1970: 0)
        )
        fakeClient.lookupResponse = PairingSessionSnapshot(
            pairingSessionID: "ps_1",
            state: .ready,
            initiatorDeviceID: "dev_1",
            initiatorName: "mac",
            initiatorPlatform: .macOS,
            initiatorPublicKey: localPrivateKey.publicKey.rawRepresentation,
            joinerDeviceID: "dev_2",
            joinerName: "android",
            joinerPlatform: .android,
            joinerPublicKey: peerPrivateKey.publicKey.rawRepresentation,
            expiresAt: Date(timeIntervalSince1970: 0),
            updatedAt: Date(timeIntervalSince1970: 0),
            completedAt: nil
        )

        let result = try await coordinator.lookupPairing(for: draft)

        XCTAssertEqual(result.shortAuthenticationString?.count, 6)
    }

    func testCompleteUsesExistingCompletedSnapshotWithoutCallingCompleteEndpoint() async throws {
        let fakeClient = FakeRelayPairingClient()
        let localPrivateKey = Curve25519.KeyAgreement.PrivateKey()
        let peerPrivateKey = Curve25519.KeyAgreement.PrivateKey()
        let completedAt = Date(timeIntervalSince1970: 42)
        let coordinator = PairingCoordinator { _ in fakeClient }
        let draft = PairingDraft(
            relayBaseURL: URL(string: "http://127.0.0.1:8080")!,
            deviceName: "AirBridge Mac",
            pairingSessionID: "ps_1",
            pairingSecret: "prs_1",
            localDeviceID: "dev_1",
            relayToken: "rt_1",
            localPrivateKeyData: localPrivateKey.rawRepresentation,
            localPublicKeyData: localPrivateKey.publicKey.rawRepresentation,
            expiresAt: Date(timeIntervalSince1970: 0)
        )
        let snapshot = PairingSessionSnapshot(
            pairingSessionID: "ps_1",
            state: .completed,
            initiatorDeviceID: "dev_1",
            initiatorName: "mac",
            initiatorPlatform: .macOS,
            initiatorPublicKey: localPrivateKey.publicKey.rawRepresentation,
            joinerDeviceID: "dev_2",
            joinerName: "android",
            joinerPlatform: .android,
            joinerPublicKey: peerPrivateKey.publicKey.rawRepresentation,
            expiresAt: Date(timeIntervalSince1970: 0),
            updatedAt: Date(timeIntervalSince1970: 0),
            completedAt: completedAt
        )

        let pairedSession = try await coordinator.completePairing(draft: draft, snapshot: snapshot)

        XCTAssertEqual(pairedSession.peerDeviceID, "dev_2")
        XCTAssertEqual(pairedSession.pairingSessionID, "ps_1")
        XCTAssertEqual(pairedSession.pairedAt, completedAt)
        XCTAssertEqual(fakeClient.completeCallCount, 0)
    }
}

private final class FakeRelayPairingClient: RelayPairingClient {
    var createResponse = CreatePairingSessionResponse(
        pairingSessionID: "ps_default",
        pairingSecret: "prs_default",
        initiatorDeviceID: "dev_default",
        initiatorRelayToken: "rt_default",
        expiresAt: Date(timeIntervalSince1970: 0)
    )
    var lookupResponse = PairingSessionSnapshot(
        pairingSessionID: "ps_default",
        state: .pending,
        initiatorDeviceID: "dev_default",
        initiatorName: "mac",
        initiatorPlatform: .macOS,
        initiatorPublicKey: Data(repeating: 0x01, count: 32),
        joinerDeviceID: nil,
        joinerName: nil,
        joinerPlatform: nil,
        joinerPublicKey: nil,
        expiresAt: Date(timeIntervalSince1970: 0),
        updatedAt: Date(timeIntervalSince1970: 0),
        completedAt: nil
    )
    var completeResponse = CompletePairingSessionResponse(
        pairingSessionID: "ps_default",
        state: .completed,
        completedAt: Date(timeIntervalSince1970: 100)
    )
    var completeCallCount = 0

    func createPairingSessionResponse(
        deviceName: String,
        platform: AirBridgePlatform,
        publicKey: Data
    ) async throws -> CreatePairingSessionResponse {
        createResponse
    }

    func lookupPairingSession(
        sessionID: String,
        pairingSecret: String
    ) async throws -> PairingSessionSnapshot {
        lookupResponse
    }

    func completePairingSession(
        sessionID: String,
        pairingSecret: String
    ) async throws -> CompletePairingSessionResponse {
        completeCallCount += 1
        return completeResponse
    }
}
