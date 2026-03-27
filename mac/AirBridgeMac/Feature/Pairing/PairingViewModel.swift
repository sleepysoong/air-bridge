import Combine
import Foundation

@MainActor
final class PairingViewModel: ObservableObject {
    @Published private(set) var activeDraft: PairingDraft?
    @Published private(set) var relayAddresses: [String] = []
    @Published private(set) var latestSnapshot: PairingSessionSnapshot?
    @Published private(set) var shortAuthenticationString: String?
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
        guard let data = try? JSONEncoder.airBridge.encode(payload) else {
            return nil
        }

        return String(decoding: data, as: UTF8.self)
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
            shortAuthenticationString = nil
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
            let result = try await pairingCoordinator.lookupPairing(for: activeDraft)
            latestSnapshot = result.snapshot
            shortAuthenticationString = result.shortAuthenticationString

            switch result.snapshot.state {
            case .pending:
                pairingMessage = "Android 기기가 참여할 때까지 기다리고 있어요."
            case .ready:
                pairingMessage = result.shortAuthenticationString == nil
                    ? "Android가 참여했어요. 인증 코드를 기다리고 있어요."
                    : "양쪽 기기에서 6자리 코드를 비교해 주세요."
            case .completed:
                pairingMessage = "Relay에서 페어링이 완료됐어요."
                stopPolling()
            }
            DesktopFileLogger.log("Pairing refresh completed with state \(String(describing: result.snapshot.state))")
        } catch {
            pairingMessage = error.localizedDescription
            appState.setLatestError(error)
        }
    }

    func completePairing() async {
        guard let activeDraft, let latestSnapshot else {
            return
        }

        DesktopFileLogger.log("PairingViewModel completePairing requested")

        isBusy = true

        do {
            let pairedSession = try await pairingCoordinator.completePairing(
                draft: activeDraft,
                snapshot: latestSnapshot
            )
            try await appContainer?.activatePairedSession(pairedSession)
            pairingMessage = "페어링이 완료됐어요."
            DesktopFileLogger.log("Pairing completed successfully")
            stopPolling()
            self.activeDraft = nil
            self.latestSnapshot = nil
            self.shortAuthenticationString = nil
        } catch {
            pairingMessage = error.localizedDescription
            appState.setLatestError(error)
        }

        isBusy = false
    }

    func cancelDraft() {
        stopPolling()
        activeDraft = nil
        relayAddresses = []
        latestSnapshot = nil
        shortAuthenticationString = nil
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
}
