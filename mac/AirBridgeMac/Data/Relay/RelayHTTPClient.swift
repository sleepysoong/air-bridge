import Foundation

protocol RelayPairingClient {
    func createPairingSessionResponse(
        deviceName: String,
        platform: AirBridgePlatform,
        publicKey: Data
    ) async throws -> CreatePairingSessionResponse

    func lookupPairingSession(
        sessionID: String,
        pairingSecret: String
    ) async throws -> PairingSessionSnapshot

    func completePairingSession(
        sessionID: String,
        pairingSecret: String
    ) async throws -> CompletePairingSessionResponse
}

enum RelayHTTPClientError: LocalizedError {
    case invalidBaseURL
    case invalidResponse
    case serviceError(RelayServiceErrorPayload)

    var errorDescription: String? {
        switch self {
        case .invalidBaseURL:
            return "Relay URL이 올바르지 않아요."
        case .invalidResponse:
            return "Relay가 올바르지 않은 HTTP 응답을 반환했어요."
        case .serviceError(let payload):
            return payload.message
        }
    }
}

final class RelayHTTPClient: RelayPairingClient {
    private let baseURL: URL
    private let session: URLSession

    init(baseURL: URL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    func createPairingSessionResponse(
        deviceName: String,
        platform: AirBridgePlatform,
        publicKey: Data
    ) async throws -> CreatePairingSessionResponse {
        let validatedDeviceName = try RelayInputValidator.deviceName(deviceName)
        let validatedPublicKey = try RelayInputValidator.publicKey(publicKey)

        let response: CreatePairingSessionResponse = try await send(
            path: "/api/v1/pairing/sessions",
            method: "POST",
            body: CreatePairingSessionRequest(
                deviceName: validatedDeviceName,
                platform: platform.rawValue,
                publicKey: validatedPublicKey.rawBase64EncodedString
            )
        )

        return response
    }

    func lookupPairingSession(
        sessionID: String,
        pairingSecret: String
    ) async throws -> PairingSessionSnapshot {
        let validatedSessionID = try RelayInputValidator.identifier(sessionID, field: "pairing_session_id")
        let validatedPairingSecret = try RelayInputValidator.pairingSecret(pairingSecret)

        let response: LookupPairingSessionResponse = try await send(
            path: "/api/v1/pairing/sessions/\(validatedSessionID)/lookup",
            method: "POST",
            body: LookupPairingSessionRequest(pairingSecret: validatedPairingSecret)
        )

        return PairingSessionSnapshot(
            pairingSessionID: response.pairingSessionID,
            state: response.state,
            initiatorDeviceID: response.initiatorDeviceID,
            initiatorName: response.initiatorName,
            initiatorPlatform: response.initiatorPlatform,
            initiatorPublicKey: try Data(rawBase64Encoded: response.initiatorPublicKey),
            joinerDeviceID: response.joinerDeviceID,
            joinerName: response.joinerName,
            joinerPlatform: response.joinerPlatform,
            joinerPublicKey: try response.joinerPublicKey.map(Data.init(rawBase64Encoded:)),
            expiresAt: response.expiresAt,
            updatedAt: response.updatedAt,
            completedAt: response.completedAt
        )
    }

    func completePairingSession(
        sessionID: String,
        pairingSecret: String
    ) async throws -> CompletePairingSessionResponse {
        let validatedSessionID = try RelayInputValidator.identifier(sessionID, field: "pairing_session_id")
        let validatedPairingSecret = try RelayInputValidator.pairingSecret(pairingSecret)

        let response: CompletePairingSessionResponse = try await send(
            path: "/api/v1/pairing/sessions/\(validatedSessionID)/complete",
            method: "POST",
            body: CompletePairingSessionRequest(pairingSecret: validatedPairingSecret)
        )

        return response
    }

    private func send<Body: Encodable, Response: Decodable>(
        path: String,
        method: String,
        body: Body
    ) async throws -> Response {
        let requestURL = try makeURL(path: path)
        var request = URLRequest(url: requestURL)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = try JSONEncoder.airBridge.encode(body)

        let (data, response) = try await session.data(for: request)
        return try decodeResponse(data: data, response: response)
    }

    private func decodeResponse<Response: Decodable>(
        data: Data,
        response: URLResponse
    ) throws -> Response {
        guard let httpResponse = response as? HTTPURLResponse else {
            throw RelayHTTPClientError.invalidResponse
        }

        guard (200 ... 299).contains(httpResponse.statusCode) else {
            if let errorResponse = try? JSONDecoder.airBridge.decode(RelayHTTPErrorResponse.self, from: data) {
                throw RelayHTTPClientError.serviceError(errorResponse.error)
            }

            throw RelayHTTPClientError.invalidResponse
        }

        return try JSONDecoder.airBridge.decode(Response.self, from: data)
    }

    private func makeURL(path: String) throws -> URL {
        guard var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else {
            throw RelayHTTPClientError.invalidBaseURL
        }

        let normalizedBasePath = components.path.hasSuffix("/") ? String(components.path.dropLast()) : components.path
        components.path = normalizedBasePath + path

        guard let url = components.url else {
            throw RelayHTTPClientError.invalidBaseURL
        }

        return url
    }
}

private struct CreatePairingSessionRequest: Encodable {
    let deviceName: String
    let platform: String
    let publicKey: String

    enum CodingKeys: String, CodingKey {
        case deviceName = "device_name"
        case platform
        case publicKey = "public_key"
    }
}

struct CreatePairingSessionResponse: Decodable {
    let pairingSessionID: String
    let pairingSecret: String
    let initiatorDeviceID: String
    let initiatorRelayToken: String
    let expiresAt: Date

    enum CodingKeys: String, CodingKey {
        case pairingSessionID = "pairing_session_id"
        case pairingSecret = "pairing_secret"
        case initiatorDeviceID = "initiator_device_id"
        case initiatorRelayToken = "initiator_relay_token"
        case expiresAt = "expires_at"
    }
}

private struct LookupPairingSessionRequest: Encodable {
    let pairingSecret: String

    enum CodingKeys: String, CodingKey {
        case pairingSecret = "pairing_secret"
    }
}

private struct LookupPairingSessionResponse: Decodable {
    let pairingSessionID: String
    let state: PairingSessionState
    let initiatorDeviceID: String
    let initiatorName: String
    let initiatorPlatform: AirBridgePlatform
    let initiatorPublicKey: String
    let joinerDeviceID: String?
    let joinerName: String?
    let joinerPlatform: AirBridgePlatform?
    let joinerPublicKey: String?
    let expiresAt: Date
    let updatedAt: Date
    let completedAt: Date?

    enum CodingKeys: String, CodingKey {
        case pairingSessionID = "pairing_session_id"
        case state
        case initiatorDeviceID = "initiator_device_id"
        case initiatorName = "initiator_name"
        case initiatorPlatform = "initiator_platform"
        case initiatorPublicKey = "initiator_public_key"
        case joinerDeviceID = "joiner_device_id"
        case joinerName = "joiner_name"
        case joinerPlatform = "joiner_platform"
        case joinerPublicKey = "joiner_public_key"
        case expiresAt = "expires_at"
        case updatedAt = "updated_at"
        case completedAt = "completed_at"
    }
}

struct CompletePairingSessionResponse: Decodable {
    let pairingSessionID: String
    let state: PairingSessionState
    let completedAt: Date

    enum CodingKeys: String, CodingKey {
        case pairingSessionID = "pairing_session_id"
        case state
        case completedAt = "completed_at"
    }
}

private struct CompletePairingSessionRequest: Encodable {
    let pairingSecret: String

    enum CodingKeys: String, CodingKey {
        case pairingSecret = "pairing_secret"
    }
}
