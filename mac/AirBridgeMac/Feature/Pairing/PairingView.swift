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
            Text("Relay Status")
                .font(.title2.bold())

            LabeledContent("Connection", value: appState.connectionState.summary)
            LabeledContent("Relay URL", value: appState.relayBaseURLText)
            LabeledContent("Device Name", value: appState.deviceName)

            if let peerDeviceID = appState.peerDeviceID {
                LabeledContent("Peer Device", value: peerDeviceID)
            }

            if let pairedSession = appState.pairedSession {
                LabeledContent("Paired At", value: formatted(pairedSession.pairedAt))
            }

            if let lastClipboardSyncAt = appState.lastClipboardSyncAt {
                LabeledContent("Last Clipboard Sync", value: formatted(lastClipboardSyncAt))
            }

            if let lastNotificationAt = appState.lastNotificationAt {
                LabeledContent("Last Notification Mirror", value: formatted(lastNotificationAt))
            }

            Toggle("Notification Permission Granted", isOn: .constant(appState.notificationAuthorizationGranted))
                .disabled(true)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private var pairingCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Pairing")
                .font(.title2.bold())

            TextField("Relay URL", text: $appState.relayBaseURLText)
                .textFieldStyle(.roundedBorder)

            TextField("Device Name", text: $appState.deviceName)
                .textFieldStyle(.roundedBorder)

            HStack(spacing: 12) {
                Button("Start Pairing") {
                    Task {
                        await viewModel.startPairing()
                    }
                }
                .disabled(viewModel.isBusy || appState.isPaired)

                Button("Refresh Status") {
                    Task {
                        await viewModel.refreshPairing()
                    }
                }
                .disabled(viewModel.activeDraft == nil)

                Button("Complete Pairing") {
                    Task {
                        await viewModel.completePairing()
                    }
                }
                .disabled(viewModel.shortAuthenticationString == nil || viewModel.isBusy)

                Button("Reset") {
                    viewModel.cancelDraft()
                }
                .disabled(viewModel.activeDraft == nil && !appState.isPaired)

                Button("Clear Saved Pairing") {
                    Task {
                        await viewModel.clearPairing()
                    }
                }
                .disabled(!appState.isPaired)
            }

            if let activeDraft = viewModel.activeDraft {
                VStack(alignment: .leading, spacing: 12) {
                    LabeledContent("Session ID", value: activeDraft.pairingSessionID)
                    LabeledContent("Expires At", value: formatted(activeDraft.expiresAt))

                    if let latestSnapshot = viewModel.latestSnapshot {
                        LabeledContent("Relay State", value: latestSnapshot.state.rawValue)

                        if let joinerName = latestSnapshot.joinerName {
                            LabeledContent("Android Device", value: joinerName)
                        }
                    }

                    if let shortAuthenticationString = viewModel.shortAuthenticationString {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("6-Digit Verification")
                                .font(.headline)
                            Text(shortAuthenticationString)
                                .font(.system(size: 36, weight: .semibold, design: .monospaced))
                        }
                    }

                    if let qrPayloadString = viewModel.qrPayloadString {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Pairing QR")
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
