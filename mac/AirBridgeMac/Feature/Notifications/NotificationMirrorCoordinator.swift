import Foundation

@MainActor
final class NotificationMirrorCoordinator {
    static let contentType = "application/vnd.airbridge.notification+json"
    static let legacyContentType = "application/json"

    private let appState: AppState
    private let gateway: LocalNotificationGateway
    private let envelopeCipher: EnvelopeCipher

    init(
        appState: AppState,
        gateway: LocalNotificationGateway,
        envelopeCipher: EnvelopeCipher
    ) {
        self.appState = appState
        self.gateway = gateway
        self.envelopeCipher = envelopeCipher
    }

    nonisolated func requestAuthorization() async throws -> Bool {
        try await gateway.requestAuthorization()
    }

    nonisolated func refreshAuthorizationStatus() async throws -> Bool {
        let status = await gateway.currentAuthorizationStatus()
        switch status {
        case .notDetermined:
            return false
        case .authorized, .provisional, .ephemeral:
            return true
        case .denied:
            return false
        @unknown default:
            return false
        }
    }

    func handleIncomingEnvelope(
        _ envelope: RelayEnvelope,
        session: PairedDeviceSession
    ) async throws {
        guard [Self.contentType, Self.legacyContentType].contains(envelope.contentType) else {
            throw NSError(
                domain: "AirBridge.NotificationMirrorCoordinator",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "지원하지 않는 알림 content type이에요: \(envelope.contentType)"]
            )
        }

        let payload: NotificationPayload
        if !session.pairingSessionID.isEmpty {
            payload = try envelopeCipher.decrypt(
                NotificationPayload.self,
                envelope: envelope,
                pairingSessionID: session.pairingSessionID,
                expectedRecipientDeviceID: session.localDeviceID,
                localPrivateKeyData: session.localPrivateKeyData,
                peerPublicKeyData: session.peerPublicKeyData
            )
        } else {
            payload = try envelopeCipher.decrypt(
                NotificationPayload.self,
                envelope: envelope,
                expectedRecipientDeviceID: session.localDeviceID,
                sessionKeyData: session.sessionKeyData
            )
        }

        try await gateway.apply(payload)
        appState.lastNotificationAt = Date()
    }
}
