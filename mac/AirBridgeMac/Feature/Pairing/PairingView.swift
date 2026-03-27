import SwiftUI

struct PairingView: View {
    @ObservedObject var viewModel: PairingViewModel
    @ObservedObject var appState: AppState
    private let qrCodeGenerator = QRCodeGenerator()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                connectionCard

                if appState.isPaired {
                    pairedCard
                } else {
                    unpairedCard
                }

                if let latestError = appState.latestError {
                    Text(latestError)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            }
            .padding(24)
        }
    }

    private var connectionCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Relay 상태")
                .font(.title2.bold())

            LabeledContent("연결", value: appState.connectionState.summary)
            LabeledContent("Relay URL", value: appState.relayBaseURLText)
            LabeledContent("내 기기 이름", value: appState.deviceName)

            if let lastClipboardSyncAt = appState.lastClipboardSyncAt {
                LabeledContent("마지막 클립보드 동기화", value: formatted(lastClipboardSyncAt))
            }

            if let lastNotificationAt = appState.lastNotificationAt {
                LabeledContent("마지막 알림 미러링", value: formatted(lastNotificationAt))
            }

            Toggle("알림 권한 허용됨", isOn: .constant(appState.notificationAuthorizationGranted))
                .disabled(true)

            Button("알림 권한 요청할게요") {
                Task {
                    await viewModel.requestNotificationAuthorization()
                }
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private var unpairedCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("페어링 준비")
                .font(.title2.bold())

            TextField("Relay URL (예: http://192.168.0.10:8080)", text: $appState.relayBaseURLText)
                .textFieldStyle(.roundedBorder)

            TextField("내 기기 이름", text: $appState.deviceName)
                .textFieldStyle(.roundedBorder)

            Button(viewModel.activeDraft == nil ? "QR 코드 띄우기" : "QR 다시 만들기") {
                Task {
                    await viewModel.startPairing()
                }
            }
            .disabled(viewModel.isBusy)

            if let activeDraft = viewModel.activeDraft,
               let qrPayloadString = viewModel.qrPayloadString {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Android에서 이 QR을 스캔하면 바로 페어링돼요.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    QRCodeView(
                        payload: qrPayloadString,
                        generator: qrCodeGenerator
                    )
                    .frame(width: 220, height: 220)

                    LabeledContent("세션 ID", value: activeDraft.pairingSessionID)
                    LabeledContent("만료 시각", value: formatted(activeDraft.expiresAt))

                    if let latestSnapshot = viewModel.latestSnapshot,
                       let joinerName = latestSnapshot.joinerName {
                        LabeledContent("연결 중인 기기", value: joinerName)
                    }
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.black.opacity(0.04), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }

            if let pairingMessage = viewModel.pairingMessage {
                Text(pairingMessage)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private var pairedCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("연결된 기기")
                .font(.title2.bold())

            if let pairedSession = appState.pairedSession {
                LabeledContent("기기 이름", value: pairedSession.peerDeviceName)
                LabeledContent("페어링 시각", value: formatted(pairedSession.pairedAt))

                TimelineView(.periodic(from: .now, by: 1)) { context in
                    LabeledContent("지속 시간", value: elapsedDurationText(since: pairedSession.pairedAt, now: context.date))
                }

                Button("페어링 삭제", role: .destructive) {
                    Task {
                        await viewModel.clearPairing()
                    }
                }

                Text("세션 ID: \(pairedSession.pairingSessionID)")
                    .font(.footnote.monospaced())
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private func formatted(_ date: Date) -> String {
        date.formatted(date: .abbreviated, time: .standard)
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

private struct QRCodeView: View {
    let payload: String
    let generator: QRCodeGenerator

    var body: some View {
        if let image = qrImage {
            Image(nsImage: image)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
        } else {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(Color.black.opacity(0.08))
        }
    }

    private var qrImage: NSImage? {
        try? generator.makeImage(from: payload, size: CGSize(width: 220, height: 220))
    }
}
