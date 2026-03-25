import Foundation

enum SessionKeyStoreError: LocalizedError {
    case decodeFailed

    var errorDescription: String? {
        switch self {
        case .decodeFailed:
            return "The stored AirBridge session could not be decoded."
        }
    }
}

final class SessionKeyStore {
    private let keychainStore: KeychainStore
    private let account = "paired-session"

    init(keychainStore: KeychainStore) {
        self.keychainStore = keychainStore
    }

    func savePairedSession(_ session: PairedDeviceSession) throws {
        let data = try JSONEncoder.airBridge.encode(session)
        try keychainStore.save(data, account: account)
    }

    func loadPairedSession() throws -> PairedDeviceSession? {
        guard let data = try keychainStore.load(account: account) else {
            return nil
        }

        guard let session = try? JSONDecoder.airBridge.decode(PairedDeviceSession.self, from: data) else {
            throw SessionKeyStoreError.decodeFailed
        }

        return session
    }

    func clearPairedSession() throws {
        try keychainStore.delete(account: account)
    }
}
