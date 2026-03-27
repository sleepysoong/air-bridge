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
        DesktopFileLogger.log("AppContainer init started")

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
        DesktopFileLogger.log("AppContainer init completed")

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
            DesktopFileLogger.log("AppContainer startIfNeeded skipped because already started")
            return
        }

        didStart = true
        DesktopFileLogger.log("AppContainer startIfNeeded started")
        NSApplication.shared.setActivationPolicy(.accessory)

        do {
            let granted = try await notificationMirrorCoordinator.refreshAuthorizationStatus()
            appState.notificationAuthorizationGranted = granted
            DesktopFileLogger.log("Notification authorization refreshed: \(granted)")
        } catch {
            appState.setLatestError(error)
        }

        do {
            if let storedSession = try sessionKeyStore.loadPairedSession() {
                DesktopFileLogger.log("Stored paired session found, starting clipboard sync and relay connect")
                appState.pairedSession = storedSession
                appState.peerDeviceID = storedSession.peerDeviceID
                clipboardSyncCoordinator.start(session: storedSession)
                await connectRelay(using: storedSession, reconnecting: false)
            } else {
                DesktopFileLogger.log("No stored paired session found")
            }
        } catch {
            appState.setLatestError(error)
        }

        DesktopFileLogger.log("AppContainer startIfNeeded finished")
    }

    func activatePairedSession(_ session: PairedDeviceSession) async throws {
        DesktopFileLogger.log("Activating paired session")
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
        DesktopFileLogger.log("Clearing pairing state")
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
            DesktopFileLogger.log("Reconnect requested without paired session")
            return
        }

        DesktopFileLogger.log("Reconnect requested for relay")
        await connectRelay(using: pairedSession, reconnecting: true)
    }

    func requestNotificationAuthorization() async {
        DesktopFileLogger.log("Manual notification authorization request started")

        do {
            let granted = try await notificationMirrorCoordinator.requestAuthorization()
            appState.notificationAuthorizationGranted = granted
            DesktopFileLogger.log("Manual notification authorization request finished: \(granted)")
        } catch {
            appState.setLatestError(error)
        }
    }

    private func connectRelay(using session: PairedDeviceSession, reconnecting: Bool) async {
        reconnectTask?.cancel()
        relayWebSocketClient.disconnect()
        appState.connectionState = reconnecting ? .reconnecting : .connecting
        DesktopFileLogger.log("Relay connect started. reconnecting=\(reconnecting)")

        do {
            try await relayWebSocketClient.connect(
                baseURL: session.relayBaseURL,
                deviceID: session.localDeviceID,
                relayToken: session.relayToken
            ) { [weak self] event in
                guard let self else { return }
                await self.handleRelayEvent(event, session: session)
            }
            DesktopFileLogger.log("Relay connect call completed")
        } catch {
            appState.connectionState = .failed(error.localizedDescription)
            appState.setLatestError(error)
            scheduleReconnect(for: session)
        }
    }

    private func handleRelayEvent(_ event: RelayWebSocketEvent, session: PairedDeviceSession) async {
        guard appState.pairedSession?.localDeviceID == session.localDeviceID else {
            DesktopFileLogger.log("Ignored relay event for stale session")
            return
        }

        switch event {
        case .message(let message):
            switch message {
            case .connected(_, let peerDeviceID):
                DesktopFileLogger.log("Relay connected event received")
                appState.connectionState = .connected
                appState.peerDeviceID = peerDeviceID
                appState.setLatestError(nil)
            case .pong:
                DesktopFileLogger.log("Relay pong received")
                break
            case .error(let code, let message):
                DesktopFileLogger.log(errorMessage: "\(code): \(message)", context: "RelayEvent")
                appState.connectionState = .failed(message)
                appState.setLatestError("\(code): \(message)")
            case .envelope(let envelope):
                DesktopFileLogger.log("Relay envelope received for channel \(String(describing: envelope.channel))")
                await handleIncomingEnvelope(envelope, session: session)
            }
        case .disconnected(let error):
            DesktopFileLogger.log(errorMessage: error?.localizedDescription ?? "socket disconnected without error", context: "RelayDisconnect")
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
                DesktopFileLogger.log("Handling incoming clipboard envelope")
                try await clipboardSyncCoordinator.handleIncomingEnvelope(envelope, session: session)
            case .notification:
                DesktopFileLogger.log("Handling incoming notification envelope")
                try await notificationMirrorCoordinator.handleIncomingEnvelope(envelope, session: session)
            }

            try await relayWebSocketClient.send(.acknowledgeEnvelope(envelopeID: envelope.id))
            DesktopFileLogger.log("Acknowledged incoming envelope")
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
        DesktopFileLogger.log("Scheduling relay reconnect in 3 seconds")
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
