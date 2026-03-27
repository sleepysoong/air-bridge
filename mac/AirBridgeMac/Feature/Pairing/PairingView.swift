import SwiftUI

struct PairingView: View {
    @ObservedObject var viewModel: PairingViewModel
    @ObservedObject var appState: AppState
    private let qrCodeGenerator = QRCodeGenerator()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                connectionCard
                pairingCard

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
            LabeledContent("기기 이름", value: appState.deviceName)

            if let peerDeviceID = appState.peerDeviceID {
                LabeledContent("연결된 기기", value: peerDeviceID)
            }

            if let pairedSession = appState.pairedSession {
                LabeledContent("페어링 시각", value: formatted(pairedSession.pairedAt))
            }

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

    private var pairingCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("페어링")
                .font(.title2.bold())

            TextField("Relay URL (예: http://192.168.0.10:8080)", text: $appState.relayBaseURLText)
                .textFieldStyle(.roundedBorder)

            TextField("기기 이름", text: $appState.deviceName)
                .textFieldStyle(.roundedBorder)

            HStack(spacing: 12) {
                Button("페어링 시작할게요") {
                    Task {
                        await viewModel.startPairing()
                    }
                }
                .disabled(viewModel.isBusy || appState.isPaired)

                Button("상태 새로고침") {
                    Task {
                        await viewModel.refreshPairing()
                    }
                }
                .disabled(viewModel.activeDraft == nil)

                Button("페어링 완료할게요") {
                    Task {
                        await viewModel.completePairing()
                    }
                }
                .disabled(viewModel.shortAuthenticationString == nil || viewModel.isBusy)

                Button("초기화") {
                    viewModel.cancelDraft()
                }
                .disabled(viewModel.activeDraft == nil && !appState.isPaired)

                Button("저장된 페어링 삭제") {
                    Task {
                        await viewModel.clearPairing()
                    }
                }
                .disabled(!appState.isPaired)
            }

            if let activeDraft = viewModel.activeDraft {
                VStack(alignment: .leading, spacing: 12) {
                    LabeledContent("세션 ID", value: activeDraft.pairingSessionID)
                    LabeledContent("만료 시각", value: formatted(activeDraft.expiresAt))

                    if let latestSnapshot = viewModel.latestSnapshot {
                        LabeledContent("Relay 상태", value: latestSnapshot.state.rawValue)

                        if let joinerName = latestSnapshot.joinerName {
                            LabeledContent("Android 기기", value: joinerName)
                        }
                    }

                    if let shortAuthenticationString = viewModel.shortAuthenticationString {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("6자리 확인 코드 (선택)")
                                .font(.headline)
                            Text(shortAuthenticationString)
                                .font(.system(size: 36, weight: .semibold, design: .monospaced))
                        }
                    }

                    if let qrPayloadString = viewModel.qrPayloadString {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("페어링 QR")
                                .font(.headline)

                            QRCodeView(
                                payload: qrPayloadString,
                                generator: qrCodeGenerator
                            )
                                .frame(width: 200, height: 200)

                            Text(qrPayloadString)
                                .font(.footnote.monospaced())
                                .textSelection(.enabled)
                                .foregroundStyle(.secondary)
                        }
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

    private func formatted(_ date: Date) -> String {
        date.formatted(date: .abbreviated, time: .standard)
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
        try? generator.makeImage(from: payload, size: CGSize(width: 200, height: 200))
    }
}
