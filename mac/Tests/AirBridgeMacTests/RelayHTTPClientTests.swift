import Foundation
import XCTest
@testable import AirBridgeMac

final class RelayHTTPClientTests: XCTestCase {
    override func tearDown() {
        super.tearDown()
        MockURLProtocol.handler = nil
    }

    func testCreatePairingSessionUsesExpectedRouteAndBody() async throws {
        let session = makeSession()
        let client = RelayHTTPClient(baseURL: URL(string: "http://127.0.0.1:8080")!, session: session)

        MockURLProtocol.handler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/pairing/sessions")
            XCTAssertEqual(request.httpMethod, "POST")

            let body = try XCTUnwrap(request.httpBody)
            let decodedBody = try JSONSerialization.jsonObject(with: body) as? [String: Any]
            XCTAssertEqual(decodedBody?["device_name"] as? String, "AirBridge Mac")
            XCTAssertEqual(decodedBody?["platform"] as? String, "macos")
            XCTAssertEqual(decodedBody?["public_key"] as? String, Data(repeating: 0x01, count: 32).rawBase64EncodedString)

            let response = HTTPURLResponse(url: request.url!, statusCode: 201, httpVersion: nil, headerFields: nil)!
            let data = Data(
                """
                {"pairing_session_id":"ps_1","pairing_secret":"prs_1","initiator_device_id":"dev_1","initiator_relay_token":"rt_1","expires_at":"2026-03-26T00:00:00Z"}
                """.utf8
            )
            return (response, data)
        }

        let response = try await client.createPairingSessionResponse(
            deviceName: "AirBridge Mac",
            platform: .macOS,
            publicKey: Data(repeating: 0x01, count: 32)
        )

        XCTAssertEqual(response.pairingSessionID, "ps_1")
        XCTAssertEqual(response.initiatorDeviceID, "dev_1")
    }

    func testLookupUsesPostLookupRoute() async throws {
        let session = makeSession()
        let client = RelayHTTPClient(baseURL: URL(string: "http://127.0.0.1:8080")!, session: session)

        MockURLProtocol.handler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/pairing/sessions/ps_1/lookup")
            XCTAssertEqual(request.httpMethod, "POST")

            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            let data = Data(
                """
                {"pairing_session_id":"ps_1","state":"ready","initiator_device_id":"dev_1","initiator_name":"mac","initiator_platform":"macos","initiator_public_key":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE","joiner_device_id":"dev_2","joiner_name":"android","joiner_platform":"android","joiner_public_key":"AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI","expires_at":"2026-03-26T00:00:00Z","updated_at":"2026-03-26T00:00:00Z"}
                """.utf8
            )
            return (response, data)
        }

        let snapshot = try await client.lookupPairingSession(sessionID: "ps_1", pairingSecret: "prs_1")
        XCTAssertEqual(snapshot.state, .completed)
        XCTAssertEqual(snapshot.joinerDeviceID, "dev_2")
    }

    func testServiceErrorMapsRelayErrorPayload() async {
        let session = makeSession()
        let client = RelayHTTPClient(baseURL: URL(string: "http://127.0.0.1:8080")!, session: session)

        MockURLProtocol.handler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 409, httpVersion: nil, headerFields: nil)!
            let data = Data(#"{"error":{"code":"conflict","message":"already paired"}}"#.utf8)
            return (response, data)
        }

        do {
            _ = try await client.lookupPairingSession(sessionID: "ps_1", pairingSecret: "prs_1")
            XCTFail("Expected conflict error.")
        } catch let error as RelayHTTPClientError {
            guard case .serviceError(let payload) = error else {
                return XCTFail("Expected service error payload.")
            }
            XCTAssertEqual(payload.code, "conflict")
            XCTAssertEqual(payload.message, "already paired")
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    private func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockURLProtocol.self]
        return URLSession(configuration: configuration)
    }
}

private final class MockURLProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        guard let handler = Self.handler else {
            XCTFail("Missing MockURLProtocol handler.")
            return
        }

        do {
            let (response, data) = try handler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}
