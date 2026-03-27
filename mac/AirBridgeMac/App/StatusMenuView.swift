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
                Text("연결된 기기: \(peerDeviceID)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if let lastClipboardSyncAt = appState.lastClipboardSyncAt {
                Text("클립보드: \(lastClipboardSyncAt.formatted(date: .omitted, time: .standard))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Text(appState.notificationAuthorizationGranted ? "알림: 허용됨" : "알림: 비허용")
                .font(.caption)
                .foregroundStyle(.secondary)

            if let latestError = appState.latestError {
                Text(latestError)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            Divider()

            SettingsLink {
                Label("AirBridge 열기", systemImage: "gearshape")
            }

            Button("재연결") {
                Task {
                    await pairingViewModel.reconnectRelay()
                }
            }
            .disabled(!appState.isPaired)

            Button("페어링 해제") {
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
