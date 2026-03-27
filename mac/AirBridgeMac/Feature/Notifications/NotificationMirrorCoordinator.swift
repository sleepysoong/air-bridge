import Foundation

@MainActor
final class NotificationMirrorCoordinator {
    static let contentType = "application/vnd.airbridge.notification+json"

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
        let payload = try envelopeCipher.decrypt(
            NotificationPayload.self,
            envelope: envelope,
            expectedRecipientDeviceID: session.localDeviceID,
            sessionKeyData: session.sessionKeyData
        )

        try await gateway.apply(payload)
        appState.lastNotificationAt = Date()
    }
}
