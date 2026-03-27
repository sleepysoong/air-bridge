import Combine
import Foundation

@MainActor
final class AppState: ObservableObject {
    enum RelayConnectionState: Equatable {
        case idle
        case connecting
        case connected
        case reconnecting
        case disconnected
        case failed(String)

        var summary: String {
            switch self {
            case .idle:
                return "Idle"
            case .connecting:
                return "Connecting"
            case .connected:
                return "Connected"
            case .reconnecting:
                return "Reconnecting"
            case .disconnected:
                return "Disconnected"
            case .failed(let message):
                return "Failed: \(message)"
            }
        }

        var symbolName: String {
            switch self {
            case .connected:
                return "bolt.horizontal.circle.fill"
            case .connecting, .reconnecting:
                return "bolt.horizontal.circle"
            case .failed:
                return "exclamationmark.triangle.fill"
            case .idle, .disconnected:
                return "bolt.slash.circle"
            }
        }
    }

    private let preferences: AppPreferences

    @Published var relayBaseURLText: String
    @Published var deviceName: String
    @Published var connectionState: RelayConnectionState = .idle
    @Published var pairedSession: PairedDeviceSession?
    @Published var peerDeviceID: String?
    @Published var notificationAuthorizationGranted = false
    @Published var lastClipboardSyncAt: Date?
    @Published var lastNotificationAt: Date?
    @Published var latestError: String?

    init(preferences: AppPreferences = AppPreferences()) {
        self.preferences = preferences

        relayBaseURLText = preferences.relayBaseURLText
            ?? ProcessInfo.processInfo.environment["AIR_BRIDGE_RELAY_BASE_URL"]
            ?? "http://127.0.0.1:8080"

        let storedDeviceName = preferences.deviceName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if storedDeviceName.isEmpty {
            let hostName = Host.current().localizedName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            deviceName = hostName.isEmpty ? "AirBridge Mac" : hostName
        } else {
            deviceName = storedDeviceName
        }
    }

    var statusImageName: String {
        connectionState.symbolName
    }

    var isPaired: Bool {
        pairedSession != nil
    }

    func setLatestError(_ message: String?) {
        latestError = message
        if let message {
            DesktopFileLogger.log(errorMessage: message, context: "AppState")
        }
    }

    func setLatestError(_ error: Error) {
        latestError = error.localizedDescription
        DesktopFileLogger.log(error: error, context: "AppState")
    }

    func persistEditablePreferences() {
        preferences.relayBaseURLText = relayBaseURLText.trimmingCharacters(in: .whitespacesAndNewlines)
        preferences.deviceName = deviceName.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
