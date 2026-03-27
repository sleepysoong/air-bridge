import Combine
import Foundation

@MainActor
final class PairingViewModel: ObservableObject {
    @Published private(set) var activeDraft: PairingDraft?
    @Published private(set) var relayAddresses: [String] = []
    @Published private(set) var latestSnapshot: PairingSessionSnapshot?
    @Published private(set) var isBusy = false
    @Published private(set) var pairingMessage: String?

    private weak var appContainer: AppContainer?
    private let appState: AppState
    private let pairingCoordinator: PairingCoordinator
    private let clientFactory: (URL) -> any RelayPairingClient
    private var pollingTask: Task<Void, Never>?

    init(
        appState: AppState,
        appContainer: AppContainer,
        pairingCoordinator: PairingCoordinator,
        clientFactory: @escaping (URL) -> any RelayPairingClient
    ) {
        self.appState = appState
        self.appContainer = appContainer
        self.pairingCoordinator = pairingCoordinator
        self.clientFactory = clientFactory
    }

    deinit {
        pollingTask?.cancel()
    }

    var qrPayloadString: String? {
        guard let activeDraft, !relayAddresses.isEmpty else {
            return nil
        }

        let payload = pairingCoordinator.qrPayload(for: activeDraft, relayAddresses: relayAddresses)
        var components = URLComponents()
        components.scheme = "airbridge"
        components.host = "pair"
        components.queryItems = [
            URLQueryItem(name: "relay_addresses", value: payload.relayAddresses.joined(separator: ",")),
            URLQueryItem(name: "pairing_session_id", value: payload.pairingSessionID),
            URLQueryItem(name: "pairing_secret", value: payload.pairingSecret),
            URLQueryItem(name: "initiator_device_id", value: payload.initiatorDeviceID),
            URLQueryItem(name: "initiator_name", value: appState.deviceName),
            URLQueryItem(name: "initiator_public_key", value: payload.initiatorPublicKey),
        ]

        guard let deeplink = components.url?.absoluteString else {
            return nil
        }

        return deeplink
    }

    func startPairing() async {
        DesktopFileLogger.log("PairingViewModel startPairing requested")
        guard !appState.isPaired else {
            pairingMessage = "새 페어링을 시작하기 전에 현재 페어링을 먼저 해제해야 해요."
            DesktopFileLogger.log(errorMessage: pairingMessage ?? "unknown pairing state error", context: "PairingViewModel.startPairing")
            return
        }

        guard let relayURL = normalizedRelayURL else {
            pairingMessage = "먼저 올바른 relay URL을 입력해야 해요."
            DesktopFileLogger.log(errorMessage: pairingMessage ?? "invalid relay url", context: "PairingViewModel.startPairing")
            return
        }

        isBusy = true
        pairingMessage = nil

        do {
            appState.persistEditablePreferences()

            let client = clientFactory(relayURL)
            let addresses = try await client.fetchServerAddresses()
            relayAddresses = addresses
            DesktopFileLogger.log("Fetched relay addresses: \(addresses)")

            let draft = try await pairingCoordinator.startPairing(
                relayBaseURL: relayURL,
                deviceName: appState.deviceName
            )
            activeDraft = draft
            latestSnapshot = nil
            pairingMessage = "QR이 준비됐어요. Android에서 스캔해 주세요."
            DesktopFileLogger.log("Pairing draft created successfully")
            beginPolling()
        } catch {
            pairingMessage = error.localizedDescription
            appState.setLatestError(error)
        }

        isBusy = false
    }

    func refreshPairing() async {
        guard let activeDraft else {
            return
        }

        DesktopFileLogger.log("PairingViewModel refreshPairing requested")

        do {
            let snapshot = try await pairingCoordinator.lookupPairing(for: activeDraft)
            latestSnapshot = snapshot

            switch snapshot.state {
            case .pending:
                pairingMessage = "Android 기기가 참여할 때까지 기다리고 있어요."
            case .completed:
                try await finalizePairing(draft: activeDraft, snapshot: snapshot)
            }
            DesktopFileLogger.log("Pairing refresh completed with state \(String(describing: snapshot.state))")
        } catch {
            pairingMessage = error.localizedDescription
            appState.setLatestError(error)
        }
    }

    func cancelDraft() {
        stopPolling()
        activeDraft = nil
        relayAddresses = []
        latestSnapshot = nil
        pairingMessage = nil
    }

    func clearPairing() async {
        cancelDraft()
        await appContainer?.clearPairing()
    }

    func reconnectRelay() async {
        await appContainer?.reconnectRelay()
    }

    func requestNotificationAuthorization() async {
        DesktopFileLogger.log("PairingViewModel requestNotificationAuthorization requested")
        await appContainer?.requestNotificationAuthorization()
    }

    private var normalizedRelayURL: URL? {
        let trimmed = appState.relayBaseURLText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed),
              let scheme = url.scheme,
              scheme == "http" || scheme == "https",
              url.host != nil else {
            return nil
        }

        return url
    }

    private func beginPolling() {
        stopPolling()
        pollingTask = Task { [weak self] in
            guard let self else { return }

            while !Task.isCancelled, self.activeDraft != nil {
                await self.refreshPairing()
                try? await Task.sleep(for: .seconds(2))
            }
        }
    }

    private func stopPolling() {
        pollingTask?.cancel()
        pollingTask = nil
    }

    private func finalizePairing(draft: PairingDraft, snapshot: PairingSessionSnapshot) async throws {
        let pairedSession = try pairingCoordinator.completePairing(
            draft: draft,
            snapshot: snapshot
        )
        try await appContainer?.activatePairedSession(pairedSession)
        pairingMessage = "페어링이 완료됐어요."
        DesktopFileLogger.log("Pairing completed successfully")
        stopPolling()
        activeDraft = nil
        relayAddresses = []
        latestSnapshot = nil
    }
}
