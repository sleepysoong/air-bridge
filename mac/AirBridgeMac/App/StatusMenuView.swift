import SwiftUI

struct StatusMenuView: View {
    @ObservedObject var appState: AppState
    @ObservedObject var pairingViewModel: PairingViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("AirBridge")
                .font(.headline)

            Label(appState.connectionState.summary, systemImage: appState.statusImageName)
                .font(.subheadline)

            if let peerDeviceID = appState.peerDeviceID {
                Text("Peer: \(peerDeviceID)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if let lastClipboardSyncAt = appState.lastClipboardSyncAt {
                Text("Clipboard: \(lastClipboardSyncAt.formatted(date: .omitted, time: .standard))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Text(appState.notificationAuthorizationGranted ? "Notifications: Granted" : "Notifications: Not Granted")
                .font(.caption)
                .foregroundStyle(.secondary)

            if let latestError = appState.latestError {
                Text(latestError)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            Divider()

            SettingsLink {
                Label("Open AirBridge", systemImage: "gearshape")
            }

            Button("Reconnect Relay") {
                Task {
                    await pairingViewModel.reconnectRelay()
                }
            }
            .disabled(!appState.isPaired)

            Button("Clear Pairing") {
                Task {
                    await pairingViewModel.clearPairing()
                }
            }
            .disabled(!appState.isPaired)
        }
        .padding(14)
        .frame(width: 280, alignment: .leading)
    }
}
