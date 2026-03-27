import Foundation

enum RelayWebSocketEvent {
    case message(RelayServerMessage)
    case disconnected(Error?)
}

enum RelayWebSocketClientError: LocalizedError {
    case notConnected
    case unsupportedScheme

    var errorDescription: String? {
        switch self {
        case .notConnected:
            return "The relay socket is not connected."
        case .unsupportedScheme:
            return "The relay URL must use http or https."
        }
    }
}

@MainActor
final class RelayWebSocketClient {
    private let session: URLSession
    private let messageMapper = RelayMessageMapper()
    private var socketTask: URLSessionWebSocketTask?
    private var receiveTask: Task<Void, Never>?
    private var pingTask: Task<Void, Never>?
    private var isIntentionalDisconnect = false

    init(session: URLSession = .shared) {
        self.session = session
    }

    func connect(
        baseURL: URL,
        deviceID: String,
        relayToken: String,
        onEvent: @escaping @MainActor (RelayWebSocketEvent) async -> Void
    ) async throws {
        DesktopFileLogger.log("RelayWebSocketClient connect requested")
        disconnect()
        isIntentionalDisconnect = false

        let requestURL = try makeWebSocketURL(
            baseURL: baseURL,
            deviceID: deviceID,
            relayToken: relayToken
        )

        let task = session.webSocketTask(with: requestURL)
        socketTask = task
        task.resume()
        DesktopFileLogger.log("RelayWebSocketClient socket resumed")

        receiveTask = Task { [weak self] in
            guard let self else { return }

            do {
                while !Task.isCancelled {
                    guard let activeTask = self.socketTask else {
                        throw RelayWebSocketClientError.notConnected
                    }

                    let incomingMessage = try await activeTask.receive()
                    guard case .string(let text) = incomingMessage else {
                        DesktopFileLogger.log("RelayWebSocketClient ignored non-string message")
                        continue
                    }

                    let decodedMessage = try self.messageMapper.decode(text)
                    DesktopFileLogger.log("RelayWebSocketClient decoded incoming message")
                    await onEvent(.message(decodedMessage))
                }
            } catch {
                if Task.isCancelled || self.isIntentionalDisconnect {
                    DesktopFileLogger.log("RelayWebSocketClient receive loop cancelled intentionally")
                    return
                }

                DesktopFileLogger.log(error: error, context: "RelayWebSocketClient.receiveLoop")
                await onEvent(.disconnected(error))
            }
        }

        pingTask = Task { [weak self] in
            guard let self else { return }

            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(20))
                try? await self.send(.ping)
            }
        }
    }

    func send(_ message: RelayClientMessage) async throws {
        guard let task = socketTask else {
            throw RelayWebSocketClientError.notConnected
        }

        let text = try messageMapper.encode(message)
        try await task.send(.string(text))
        DesktopFileLogger.log("RelayWebSocketClient sent outgoing message")
    }

    func disconnect() {
        DesktopFileLogger.log("RelayWebSocketClient disconnect requested")
        isIntentionalDisconnect = true
        receiveTask?.cancel()
        pingTask?.cancel()
        receiveTask = nil
        pingTask = nil
        socketTask?.cancel(with: .goingAway, reason: nil)
        socketTask = nil
    }

    private func makeWebSocketURL(
        baseURL: URL,
        deviceID: String,
        relayToken: String
    ) throws -> URL {
        let validatedDeviceID = try RelayInputValidator.identifier(deviceID, field: "device_id")
        let validatedRelayToken = try RelayInputValidator.identifier(relayToken, field: "relay_token")

        guard var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else {
            throw RelayWebSocketClientError.unsupportedScheme
        }

        switch components.scheme {
        case "http":
            components.scheme = "ws"
        case "https":
            components.scheme = "wss"
        default:
            throw RelayWebSocketClientError.unsupportedScheme
        }

        let normalizedBasePath = components.path.hasSuffix("/") ? String(components.path.dropLast()) : components.path
        components.path = normalizedBasePath + "/api/v1/ws"
        components.queryItems = [
            URLQueryItem(name: "device_id", value: validatedDeviceID),
            URLQueryItem(name: "relay_token", value: validatedRelayToken),
        ]

        guard let url = components.url else {
            throw RelayWebSocketClientError.unsupportedScheme
        }

        return url
    }
}
