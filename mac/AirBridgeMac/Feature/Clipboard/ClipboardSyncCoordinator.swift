import CryptoKit
import Foundation

@MainActor
final class ClipboardSyncCoordinator {
    static let contentType = "application/json"

    typealias EnvelopeSender = @MainActor (
        _ channel: RelayChannel,
        _ contentType: String,
        _ nonce: Data,
        _ headerAAD: Data,
        _ ciphertext: Data
    ) async throws -> Void

    private let appState: AppState
    private let pasteboardMonitor: PasteboardMonitor
    private let mapper: ClipboardPayloadMapper
    private let envelopeCipher: EnvelopeCipher

    private var sendEnvelope: EnvelopeSender?
    private var activeSession: PairedDeviceSession?
    private var lastSyntheticDigest: Data?
    private var isRunning = false

    init(
        appState: AppState,
        pasteboardMonitor: PasteboardMonitor,
        mapper: ClipboardPayloadMapper,
        envelopeCipher: EnvelopeCipher
    ) {
        self.appState = appState
        self.pasteboardMonitor = pasteboardMonitor
        self.mapper = mapper
        self.envelopeCipher = envelopeCipher
    }

    func configureSendEnvelope(_ sendEnvelope: @escaping EnvelopeSender) {
        self.sendEnvelope = sendEnvelope
    }

    func start(session: PairedDeviceSession) {
        activeSession = session

        guard !isRunning else {
            return
        }

        pasteboardMonitor.onChange = { [weak self] in
            guard let self else { return }

            Task {
                await self.handleLocalPasteboardChange()
            }
        }
        pasteboardMonitor.start()
        isRunning = true
    }

    func stop() {
        activeSession = nil
        lastSyntheticDigest = nil

        if isRunning {
            pasteboardMonitor.stop()
            isRunning = false
        }
    }

    func handleIncomingEnvelope(
        _ envelope: RelayEnvelope,
        session: PairedDeviceSession
    ) async throws {
        guard envelope.senderDeviceID != session.localDeviceID else {
            return
        }

        let payload: ClipboardPayload
        if !session.pairingSessionID.isEmpty {
            payload = try envelopeCipher.decrypt(
                ClipboardPayload.self,
                envelope: envelope,
                pairingSessionID: session.pairingSessionID,
                expectedRecipientDeviceID: session.localDeviceID,
                localPrivateKeyData: session.localPrivateKeyData,
                peerPublicKeyData: session.peerPublicKeyData
            )
        } else {
            payload = try envelopeCipher.decrypt(
                ClipboardPayload.self,
                envelope: envelope,
                expectedRecipientDeviceID: session.localDeviceID,
                sessionKeyData: session.sessionKeyData
            )
        }

        lastSyntheticDigest = digest(for: payload)
        try mapper.apply(payload)
        appState.lastClipboardSyncAt = Date()
    }

    private func handleLocalPasteboardChange() async {
        guard let activeSession,
              let sendEnvelope else {
            return
        }

        do {
            guard let payload = try mapper.capturePayload() else {
                return
            }

            let currentDigest = digest(for: payload)
            if lastSyntheticDigest == currentDigest {
                lastSyntheticDigest = nil
                return
            }

            let sealedEnvelope: SealedRelayEnvelope
            if !activeSession.pairingSessionID.isEmpty {
                sealedEnvelope = try envelopeCipher.encrypt(
                    payload,
                    channel: .clipboard,
                    contentType: Self.contentType,
                    pairingSessionID: activeSession.pairingSessionID,
                    senderDeviceID: activeSession.localDeviceID,
                    recipientDeviceID: activeSession.peerDeviceID,
                    localPrivateKeyData: activeSession.localPrivateKeyData,
                    peerPublicKeyData: activeSession.peerPublicKeyData
                )
            } else {
                sealedEnvelope = try envelopeCipher.encrypt(
                    payload,
                    channel: .clipboard,
                    contentType: Self.contentType,
                    senderDeviceID: activeSession.localDeviceID,
                    recipientDeviceID: activeSession.peerDeviceID,
                    sessionKeyData: activeSession.sessionKeyData
                )
            }

            try await sendEnvelope(
                .clipboard,
                Self.contentType,
                sealedEnvelope.nonce,
                sealedEnvelope.headerAAD,
                sealedEnvelope.ciphertext
            )
            appState.lastClipboardSyncAt = Date()
        } catch {
            appState.setLatestError(error)
        }
    }

    private func digest(for payload: ClipboardPayload) -> Data {
        let payloadData = (try? JSONEncoder.airBridge.encode(payload)) ?? Data()
        return Data(SHA256.hash(data: payloadData))
    }
}
