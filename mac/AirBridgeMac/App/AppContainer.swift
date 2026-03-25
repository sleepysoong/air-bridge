import AppKit
import Foundation

@MainActor
final class AppContainer {
    let appState: AppState

    private let sessionKeyStore: SessionKeyStore
    private let relayWebSocketClient: RelayWebSocketClient
    private let clipboardSyncCoordinator: ClipboardSyncCoordinator
    private let notificationMirrorCoordinator: NotificationMirrorCoordinator
    private let pairingCoordinator: PairingCoordinator

    private var didStart = false
    private var reconnectTask: Task<Void, Never>?

    init(appState: AppState) {
        self.appState = appState

        let keychainStore = KeychainStore()
        let sessionKeyStore = SessionKeyStore(keychainStore: keychainStore)
        let envelopeCipher = EnvelopeCipher()
        let relayWebSocketClient = RelayWebSocketClient()
        let clipboardSyncCoordinator = ClipboardSyncCoordinator(
            appState: appState,
            pasteboardMonitor: PasteboardMonitor(),
            mapper: ClipboardPayloadMapper(),
            envelopeCipher: envelopeCipher
        )
        let notificationMirrorCoordinator = NotificationMirrorCoordinator(
            appState: appState,
            gateway: LocalNotificationGateway(),
            envelopeCipher: envelopeCipher
        )

        self.sessionKeyStore = sessionKeyStore
        self.relayWebSocketClient = relayWebSocketClient
        self.clipboardSyncCoordinator = clipboardSyncCoordinator
        self.notificationMirrorCoordinator = notificationMirrorCoordinator
        self.pairingCoordinator = PairingCoordinator { RelayHTTPClient(baseURL: $0) }

        clipboardSyncCoordinator.configureSendEnvelope { [weak self] channel, contentType, nonce, headerAAD, ciphertext in
            guard let self else { return }
            try await self.sendEncryptedEnvelope(
                channel: channel,
                contentType: contentType,
                nonce: nonce,
                headerAAD: headerAAD,
                ciphertext: ciphertext
            )
        }
    }

    func makePairingViewModel() -> PairingViewModel {
        PairingViewModel(
            appState: appState,
            appContainer: self,
            pairingCoordinator: pairingCoordinator
        )
    }

    func startIfNeeded() async {
        guard !didStart else {
            return
        }

        didStart = true
        NSApplication.shared.setActivationPolicy(.accessory)

        do {
            let granted = try await notificationMirrorCoordinator.refreshAuthorizationStatus()
            appState.notificationAuthorizationGranted = granted
        } catch {
            appState.setLatestError(error)
        }

        do {
            if let storedSession = try sessionKeyStore.loadPairedSession() {
                appState.pairedSession = storedSession
                appState.peerDeviceID = storedSession.peerDeviceID
                clipboardSyncCoordinator.start(session: storedSession)
                await connectRelay(using: storedSession, reconnecting: false)
            }
        } catch {
            appState.setLatestError(error)
        }
    }

    func activatePairedSession(_ session: PairedDeviceSession) async throws {
        reconnectTask?.cancel()
        try sessionKeyStore.savePairedSession(session)

        appState.pairedSession = session
        appState.peerDeviceID = session.peerDeviceID
        appState.setLatestError(nil)
        appState.persistEditablePreferences()

        clipboardSyncCoordinator.start(session: session)
        await connectRelay(using: session, reconnecting: false)
    }

    func clearPairing() async {
        reconnectTask?.cancel()
        relayWebSocketClient.disconnect()
        clipboardSyncCoordinator.stop()

        do {
            try sessionKeyStore.clearPairedSession()
        } catch {
            appState.setLatestError(error)
        }

        appState.pairedSession = nil
        appState.peerDeviceID = nil
        appState.connectionState = .idle
        appState.setLatestError(nil)
    }

    func reconnectRelay() async {
        guard let pairedSession = appState.pairedSession else {
            return
        }

        await connectRelay(using: pairedSession, reconnecting: true)
    }

    private func connectRelay(using session: PairedDeviceSession, reconnecting: Bool) async {
        reconnectTask?.cancel()
        relayWebSocketClient.disconnect()
        appState.connectionState = reconnecting ? .reconnecting : .connecting

        do {
            try await relayWebSocketClient.connect(
                baseURL: session.relayBaseURL,
                deviceID: session.localDeviceID,
                relayToken: session.relayToken
            ) { [weak self] event in
                guard let self else { return }
                await self.handleRelayEvent(event, session: session)
            }
        } catch {
            appState.connectionState = .failed(error.localizedDescription)
            appState.setLatestError(error)
            scheduleReconnect(for: session)
        }
    }

    private func handleRelayEvent(_ event: RelayWebSocketEvent, session: PairedDeviceSession) async {
        guard appState.pairedSession?.localDeviceID == session.localDeviceID else {
            return
        }

        switch event {
        case .message(let message):
            switch message {
            case .connected(_, let peerDeviceID):
                appState.connectionState = .connected
                appState.peerDeviceID = peerDeviceID
                appState.setLatestError(nil)
            case .pong:
                break
            case .error(let code, let message):
                appState.connectionState = .failed(message)
                appState.setLatestError("\(code): \(message)")
            case .envelope(let envelope):
                await handleIncomingEnvelope(envelope, session: session)
            }
        case .disconnected(let error):
            if let error {
                appState.connectionState = .failed(error.localizedDescription)
                appState.setLatestError(error)
            } else {
                appState.connectionState = .disconnected
            }

            scheduleReconnect(for: session)
        }
    }

    private func handleIncomingEnvelope(_ envelope: RelayEnvelope, session: PairedDeviceSession) async {
        do {
            switch envelope.channel {
            case .clipboard:
                try await clipboardSyncCoordinator.handleIncomingEnvelope(envelope, session: session)
            case .notification:
                try await notificationMirrorCoordinator.handleIncomingEnvelope(envelope, session: session)
            }

            try await relayWebSocketClient.send(.acknowledgeEnvelope(envelopeID: envelope.id))
        } catch {
            appState.setLatestError(error)
        }
    }

    private func sendEncryptedEnvelope(
        channel: RelayChannel,
        contentType: String,
        nonce: Data,
        headerAAD: Data,
        ciphertext: Data
    ) async throws {
        guard let pairedSession = appState.pairedSession else {
            throw RelayWebSocketClientError.notConnected
        }

        try await relayWebSocketClient.send(
            .sendEnvelope(
                recipientDeviceID: pairedSession.peerDeviceID,
                channel: channel,
                contentType: contentType,
                nonce: nonce,
                headerAAD: headerAAD,
                ciphertext: ciphertext
            )
        )
    }

    private func scheduleReconnect(for session: PairedDeviceSession) {
        reconnectTask?.cancel()
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(3))

            guard let self,
                  !Task.isCancelled,
                  self.appState.pairedSession?.localDeviceID == session.localDeviceID else {
                return
            }

            await self.connectRelay(using: session, reconnecting: true)
        }
    }
}
