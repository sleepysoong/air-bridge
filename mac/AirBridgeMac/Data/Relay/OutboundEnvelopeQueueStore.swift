import Foundation

struct PersistedRelayEnvelope: Codable, Equatable {
    let queueID: String
    let recipientDeviceID: String
    let channel: RelayChannel
    let contentType: String
    let nonce: String
    let headerAAD: String
    let ciphertext: String
    let createdAt: Date

    enum CodingKeys: String, CodingKey {
        case queueID = "queue_id"
        case recipientDeviceID = "recipient_device_id"
        case channel
        case contentType = "content_type"
        case nonce
        case headerAAD = "header_aad"
        case ciphertext
        case createdAt = "created_at"
    }
}

final class OutboundEnvelopeQueueStore {
    private let fileManager: FileManager
    private let fileURL: URL
    private let maxQueueSize: Int

    init(
        fileManager: FileManager = .default,
        maxQueueSize: Int = 128
    ) {
        self.fileManager = fileManager
        self.maxQueueSize = maxQueueSize

        let baseDirectory = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        let directory = baseDirectory.appendingPathComponent("AirBridgeMac", isDirectory: true)
        try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        fileURL = directory.appendingPathComponent("outbound-envelope-queue.json")
    }

    func readAll() -> [PersistedRelayEnvelope] {
        guard let data = try? Data(contentsOf: fileURL) else {
            return []
        }

        return (try? JSONDecoder.airBridge.decode([PersistedRelayEnvelope].self, from: data)) ?? []
    }

    func replaceAll(_ items: [PersistedRelayEnvelope]) {
        let trimmedItems = Array(items.suffix(maxQueueSize))
        guard !trimmedItems.isEmpty else {
            try? fileManager.removeItem(at: fileURL)
            return
        }

        if let data = try? JSONEncoder.airBridge.encode(trimmedItems) {
            try? data.write(to: fileURL, options: .atomic)
        }
    }

    func enqueue(_ item: PersistedRelayEnvelope) {
        replaceAll(readAll() + [item])
    }

    func remove(queueID: String) {
        replaceAll(readAll().filter { $0.queueID != queueID })
    }

    func clear() {
        try? fileManager.removeItem(at: fileURL)
    }
}
