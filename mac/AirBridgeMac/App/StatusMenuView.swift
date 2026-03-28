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

            if let pairedSession = appState.pairedSession {
                Text("연결된 기기: \(pairedSession.peerDeviceName)")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Text("페어링 시각: \(pairedSession.pairedAt.formatted(date: .abbreviated, time: .standard))")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Text("지속 시간: \(elapsedDurationText(since: pairedSession.pairedAt, now: Date()))")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Button("페어링 삭제", role: .destructive) {
                    Task {
                        await pairingViewModel.clearPairing()
                    }
                }
            } else {
                Text("내 기기 이름: \(appState.deviceName)")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Text("Relay: \(appState.relayBaseURLText)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if let latestError = appState.latestError {
                Text(latestError)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            Divider()

            SettingsLink {
                Label(appState.isPaired ? "상세 보기" : "페어링 QR 열기", systemImage: appState.isPaired ? "gearshape" : "qrcode")
            }

            Divider()

            Button("종료") {
                NSApplication.shared.terminate(nil)
            }
            .keyboardShortcut("q")
        }
        .padding(14)
        .frame(width: 300, alignment: .leading)
    }

    private func elapsedDurationText(since start: Date, now: Date) -> String {
        let totalSeconds = max(Int(now.timeIntervalSince(start)), 0)
        let days = totalSeconds / 86_400
        let hours = (totalSeconds % 86_400) / 3_600
        let minutes = (totalSeconds % 3_600) / 60
        let seconds = totalSeconds % 60

        return String(format: "%d일 %02d시간 %02d분 %02d초", days, hours, minutes, seconds)
    }
}
