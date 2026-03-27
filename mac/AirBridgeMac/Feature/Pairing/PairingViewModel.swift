import Combine
import Foundation

@MainActor
final class PairingViewModel: ObservableObject {
    @Published private(set) var activeDraft: PairingDraft?
    @Published private(set) var latestSnapshot: PairingSessionSnapshot?
    @Published private(set) var shortAuthenticationString: String?
    @Published private(set) var isBusy = false
    @Published private(set) var pairingMessage: String?

    private weak var appContainer: AppContainer?
    private let appState: AppState
    private let pairingCoordinator: PairingCoordinator
    private var pollingTask: Task<Void, Never>?

    init(
        appState: AppState,
        appContainer: AppContainer,
        pairingCoordinator: PairingCoordinator
    ) {
        self.appState = appState
        self.appContainer = appContainer
        self.pairingCoordinator = pairingCoordinator
    }

    deinit {
        pollingTask?.cancel()
    }

    var qrPayloadString: String? {
        guard let activeDraft else {
            return nil
        }

        let payload = pairingCoordinator.qrPayload(for: activeDraft)
        guard let data = try? JSONEncoder.airBridge.encode(payload) else {
            return nil
        }

        return String(decoding: data, as: UTF8.self)
    }

    func startPairing() async {
        DesktopFileLogger.log("PairingViewModel startPairing requested")
        guard !appState.isPaired else {
            pairingMessage = "Clear the current pairing before starting a new one."
            DesktopFileLogger.log(errorMessage: pairingMessage ?? "unknown pairing state error", context: "PairingViewModel.startPairing")
            return
        }

        guard let relayURL = normalizedRelayURL else {
            pairingMessage = "Enter a valid relay URL first."
            DesktopFileLogger.log(errorMessage: pairingMessage ?? "invalid relay url", context: "PairingViewModel.startPairing")
            return
        }

        isBusy = true
        pairingMessage = nil

        do {
            appState.persistEditablePreferences()
            let draft = try await pairingCoordinator.startPairing(
                relayBaseURL: relayURL,
                deviceName: appState.deviceName
            )
            activeDraft = draft
            latestSnapshot = nil
            shortAuthenticationString = nil
            pairingMessage = "QR is ready. Scan it from Android."
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
                pairingMessage = "Waiting for the Android device to join."
            case .ready:
                pairingMessage = result.shortAuthenticationString == nil
                    ? "Android joined. Waiting for verification material."
                    : "Compare the 6-digit code on both devices."
            case .completed:
                pairingMessage = "Pairing was completed on the relay."
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
            pairingMessage = "Pairing completed."
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
