import CryptoKit
import Foundation

enum PairingCoordinatorError: LocalizedError {
    case invalidRelayURL
    case peerKeyMissing
    case sessionNotReady

    var errorDescription: String? {
        switch self {
        case .invalidRelayURL:
            return "먼저 올바른 Relay URL을 입력해야 해요."
        case .peerKeyMissing:
            return "Android의 공개키가 아직 준비되지 않았어요."
        case .sessionNotReady:
            return "페어링 세션이 아직 완료 준비가 되지 않았어요."
        }
    }
}

@MainActor
final class PairingCoordinator {
    private let clientFactory: (URL) -> any RelayPairingClient

    init(clientFactory: @escaping (URL) -> any RelayPairingClient) {
        self.clientFactory = clientFactory
    }

    func startPairing(relayBaseURL: URL, deviceName: String) async throws -> PairingDraft {
        let validatedDeviceName = try RelayInputValidator.deviceName(deviceName)
        let privateKey = Curve25519.KeyAgreement.PrivateKey()
        let publicKeyData = try RelayInputValidator.publicKey(privateKey.publicKey.rawRepresentation)
        let client = clientFactory(relayBaseURL)
        let response = try await client.createPairingSessionResponse(
            deviceName: validatedDeviceName,
            platform: AirBridgePlatform.macOS,
            publicKey: publicKeyData
        )

        return PairingDraft(
            relayBaseURL: relayBaseURL,
            deviceName: validatedDeviceName,
            pairingSessionID: response.pairingSessionID,
            pairingSecret: response.pairingSecret,
            localDeviceID: response.initiatorDeviceID,
            relayToken: response.initiatorRelayToken,
            localPrivateKeyData: privateKey.rawRepresentation,
            localPublicKeyData: publicKeyData,
            expiresAt: response.expiresAt
        )
    }

    func qrPayload(for draft: PairingDraft) -> PairingQRCodePayload {
        PairingQRCodePayload(
            relayBaseURL: draft.relayBaseURL,
            pairingSessionID: draft.pairingSessionID,
            pairingSecret: draft.pairingSecret,
            initiatorDeviceID: draft.localDeviceID,
            initiatorPublicKey: draft.localPublicKeyData.rawBase64EncodedString
        )
    }

    func lookupPairing(for draft: PairingDraft) async throws -> PairingLookupResult {
        let client = clientFactory(draft.relayBaseURL)
        let snapshot = try await client.lookupPairingSession(
            sessionID: try RelayInputValidator.identifier(draft.pairingSessionID, field: "pairing_session_id"),
            pairingSecret: try RelayInputValidator.pairingSecret(draft.pairingSecret)
        )

        let verificationCode: String?
        if let peerPublicKeyData = snapshot.joinerPublicKey {
            verificationCode = try makeShortAuthenticationString(
                localPrivateKeyData: draft.localPrivateKeyData,
                localPublicKeyData: draft.localPublicKeyData,
                peerPublicKeyData: peerPublicKeyData
            )
        } else {
            verificationCode = nil
        }

        return PairingLookupResult(
            snapshot: snapshot,
            shortAuthenticationString: verificationCode
        )
    }

    func completePairing(draft: PairingDraft, snapshot: PairingSessionSnapshot) async throws -> PairedDeviceSession {
        guard let peerDeviceID = snapshot.joinerDeviceID,
              let peerPublicKeyData = snapshot.joinerPublicKey else {
            throw PairingCoordinatorError.peerKeyMissing
        }

        guard snapshot.state == .ready || snapshot.state == .completed else {
            throw PairingCoordinatorError.sessionNotReady
        }

        let completedAt: Date
        if snapshot.state == .completed, let existingCompletedAt = snapshot.completedAt {
            completedAt = existingCompletedAt
        } else {
            let client = clientFactory(draft.relayBaseURL)
            let completion = try await client.completePairingSession(
                sessionID: try RelayInputValidator.identifier(draft.pairingSessionID, field: "pairing_session_id"),
                pairingSecret: try RelayInputValidator.pairingSecret(draft.pairingSecret)
            )
            completedAt = completion.completedAt
        }

        let sessionKeyData = try makeSessionKeyData(
            localPrivateKeyData: draft.localPrivateKeyData,
            peerPublicKeyData: peerPublicKeyData
        )

        return PairedDeviceSession(
            relayBaseURL: draft.relayBaseURL,
            localDeviceID: draft.localDeviceID,
            peerDeviceID: peerDeviceID,
            relayToken: draft.relayToken,
            sessionKeyData: sessionKeyData,
            localPrivateKeyData: draft.localPrivateKeyData,
            localPublicKeyData: draft.localPublicKeyData,
            peerPublicKeyData: peerPublicKeyData,
            pairedAt: completedAt
        )
    }

    private func makeSessionKeyData(
        localPrivateKeyData: Data,
        peerPublicKeyData: Data
    ) throws -> Data {
        let sharedSecret = try deriveSharedSecret(
            localPrivateKeyData: localPrivateKeyData,
            peerPublicKeyData: peerPublicKeyData
        )
        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: Data("air-bridge-session-v1".utf8),
            outputByteCount: 32
        )

        return symmetricKey.dataRepresentation
    }

    private func makeShortAuthenticationString(
        localPrivateKeyData: Data,
        localPublicKeyData: Data,
        peerPublicKeyData: Data
    ) throws -> String {
        let sharedSecret = try deriveSharedSecret(
            localPrivateKeyData: localPrivateKeyData,
            peerPublicKeyData: peerPublicKeyData
        )
        let orderedPublicKeys = [localPublicKeyData, peerPublicKeyData].sorted {
            $0.lexicographicallyPrecedes($1)
        }
        let info = Data("air-bridge-sas-v1".utf8) + orderedPublicKeys[0] + orderedPublicKeys[1]
        let verificationKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: info,
            outputByteCount: 4
        )
        let bytes = verificationKey.dataRepresentation.prefix(4)
        let value = bytes.reduce(UInt32.zero) { partialResult, byte in
            (partialResult << 8) | UInt32(byte)
        } % 1_000_000

        return String(format: "%06u", value)
    }

    private func deriveSharedSecret(
        localPrivateKeyData: Data,
        peerPublicKeyData: Data
    ) throws -> SharedSecret {
        let privateKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: localPrivateKeyData)
        let peerPublicKey = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerPublicKeyData)
        return try privateKey.sharedSecretFromKeyAgreement(with: peerPublicKey)
    }
}

private extension SymmetricKey {
    var dataRepresentation: Data {
        withUnsafeBytes { Data($0) }
    }
}
