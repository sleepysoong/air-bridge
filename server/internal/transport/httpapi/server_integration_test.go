package httpapi

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"github.com/sleepysoong/air-bridge/server/internal/config"
	"github.com/sleepysoong/air-bridge/server/internal/domain"
	"github.com/sleepysoong/air-bridge/server/internal/persistence/sqlite"
	"github.com/sleepysoong/air-bridge/server/internal/service"
)

func TestHealthzReturnsOK(t *testing.T) {
	harness := newIntegrationHarness(t)

	response, err := harness.client.Get(harness.httpServer.URL + "/healthz")
	if err != nil {
		t.Fatalf("GET /healthz failed: %v", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		t.Fatalf("unexpected status code: got %d want %d", response.StatusCode, http.StatusOK)
	}

	var payload map[string]string
	decodeResponseBody(t, response, &payload)

	if payload["status"] != "ok" {
		t.Fatalf("unexpected health payload: %#v", payload)
	}
}

func TestPairingEndpointsLifecycle(t *testing.T) {
	harness := newIntegrationHarness(t)

	created := harness.createPairingSession(t, createPairingSessionRequest{
		DeviceName: "sleepysoong-macbook-air",
		Platform:   "macos",
		PublicKey:  encodeBase64(strings.Repeat("i", 32)),
	})

	if created.PairingSessionID == "" || created.PairingSecret == "" || created.InitiatorDeviceID == "" || created.InitiatorRelayToken == "" {
		t.Fatalf("create pairing session returned incomplete payload: %#v", created)
	}

	initialSession := harness.getPairingSession(t, created.PairingSessionID, created.PairingSecret)
	if initialSession.State != "pending" {
		t.Fatalf("unexpected initial state: got %q want %q", initialSession.State, "pending")
	}

	if initialSession.InitiatorName != "sleepysoong-macbook-air" {
		t.Fatalf("unexpected initiator name: got %q", initialSession.InitiatorName)
	}

	joined := harness.joinPairingSession(t, created.PairingSessionID, joinPairingSessionRequest{
		PairingSecret: created.PairingSecret,
		DeviceName:    "pixel-android",
		Platform:      "android",
		PublicKey:     encodeBase64(strings.Repeat("j", 32)),
	})

	if joined.JoinerDeviceID == "" || joined.JoinerRelayToken == "" {
		t.Fatalf("join pairing session returned incomplete payload: %#v", joined)
	}

	readySession := harness.getPairingSession(t, created.PairingSessionID, created.PairingSecret)
	if readySession.State != "ready" {
		t.Fatalf("unexpected ready state: got %q want %q", readySession.State, "ready")
	}

	if readySession.JoinerName != "pixel-android" {
		t.Fatalf("unexpected joiner name: got %q", readySession.JoinerName)
	}

	completed := harness.completePairingSession(t, created.PairingSessionID, completePairingSessionRequest{
		PairingSecret: created.PairingSecret,
	})

	if completed["state"] != "completed" {
		t.Fatalf("unexpected completed state payload: %#v", completed)
	}

	finalSession := harness.getPairingSession(t, created.PairingSessionID, created.PairingSecret)
	if finalSession.State != "completed" {
		t.Fatalf("unexpected final state: got %q want %q", finalSession.State, "completed")
	}
}

func TestWebSocketAuthenticationRequiresCompletedPairing(t *testing.T) {
	harness := newIntegrationHarness(t)

	created := harness.createPairingSession(t, createPairingSessionRequest{
		DeviceName: "sleepysoong-macbook-air",
		Platform:   "macos",
		PublicKey:  encodeBase64(strings.Repeat("i", 32)),
	})

	harness.joinPairingSession(t, created.PairingSessionID, joinPairingSessionRequest{
		PairingSecret: created.PairingSecret,
		DeviceName:    "pixel-android",
		Platform:      "android",
		PublicKey:     encodeBase64(strings.Repeat("j", 32)),
	})

	harness.expectWebSocketUnauthorized(t, created.InitiatorDeviceID, created.InitiatorRelayToken)

	harness.completePairingSession(t, created.PairingSessionID, completePairingSessionRequest{
		PairingSecret: created.PairingSecret,
	})

	connection := harness.openWebSocket(t, created.InitiatorDeviceID, created.InitiatorRelayToken)
	defer connection.Close(websocket.StatusNormalClosure, "test complete")

	connected := harness.readServerMessage(t, connection)
	if connected.Type != "connected" {
		t.Fatalf("unexpected websocket connected message after pairing completion: %#v", connected)
	}
}

func TestWebSocketSendReceiveAndAckFlow(t *testing.T) {
	harness := newIntegrationHarness(t)

	created := harness.createPairingSession(t, createPairingSessionRequest{
		DeviceName: "sleepysoong-macbook-air",
		Platform:   "macos",
		PublicKey:  encodeBase64(strings.Repeat("i", 32)),
	})

	joined := harness.joinPairingSession(t, created.PairingSessionID, joinPairingSessionRequest{
		PairingSecret: created.PairingSecret,
		DeviceName:    "pixel-android",
		Platform:      "android",
		PublicKey:     encodeBase64(strings.Repeat("j", 32)),
	})

	harness.completePairingSession(t, created.PairingSessionID, completePairingSessionRequest{
		PairingSecret: created.PairingSecret,
	})

	recipientConnection := harness.openWebSocket(t, joined.JoinerDeviceID, joined.JoinerRelayToken)
	defer recipientConnection.Close(websocket.StatusNormalClosure, "test complete")

	senderConnection := harness.openWebSocket(t, created.InitiatorDeviceID, created.InitiatorRelayToken)
	defer senderConnection.Close(websocket.StatusNormalClosure, "test complete")

	recipientConnected := harness.readServerMessage(t, recipientConnection)
	if recipientConnected.Type != "connected" {
		t.Fatalf("unexpected recipient connected message: %#v", recipientConnected)
	}

	senderConnected := harness.readServerMessage(t, senderConnection)
	if senderConnected.Type != "connected" {
		t.Fatalf("unexpected sender connected message: %#v", senderConnected)
	}

	sendEnvelope := clientMessage{
		Type:              "send_envelope",
		RecipientDeviceID: joined.JoinerDeviceID,
		Channel:           "clipboard",
		ContentType:       "application/json",
		Nonce:             encodeBase64("nonce-123456"),
		HeaderAAD:         encodeBase64(`{"channel":"clipboard"}`),
		Ciphertext:        encodeBase64(`{"ciphertext":"payload"}`),
	}

	harness.writeClientMessage(t, senderConnection, sendEnvelope)

	deliveredEnvelope := harness.readServerMessage(t, recipientConnection)
	if deliveredEnvelope.Type != "envelope" {
		t.Fatalf("unexpected websocket delivery type: %#v", deliveredEnvelope)
	}

	if deliveredEnvelope.SenderDeviceID != created.InitiatorDeviceID {
		t.Fatalf("unexpected sender id: got %q want %q", deliveredEnvelope.SenderDeviceID, created.InitiatorDeviceID)
	}

	if deliveredEnvelope.Channel != "clipboard" {
		t.Fatalf("unexpected channel: got %q want %q", deliveredEnvelope.Channel, "clipboard")
	}

	if deliveredEnvelope.ContentType != "application/json" {
		t.Fatalf("unexpected content type: got %q want %q", deliveredEnvelope.ContentType, "application/json")
	}

	harness.writeClientMessage(t, recipientConnection, clientMessage{
		Type:       "ack_envelope",
		EnvelopeID: deliveredEnvelope.EnvelopeID,
	})

	harness.waitForNoPendingEnvelopes(t, joined.JoinerDeviceID, time.Second)
}

func TestPendingEnvelopeDeliveryExcludesExpiredEnvelopes(t *testing.T) {
	harness := newIntegrationHarness(t)

	created := harness.createPairingSession(t, createPairingSessionRequest{
		DeviceName: "sleepysoong-macbook-air",
		Platform:   "macos",
		PublicKey:  encodeBase64(strings.Repeat("i", 32)),
	})

	joined := harness.joinPairingSession(t, created.PairingSessionID, joinPairingSessionRequest{
		PairingSecret: created.PairingSecret,
		DeviceName:    "pixel-android",
		Platform:      "android",
		PublicKey:     encodeBase64(strings.Repeat("j", 32)),
	})

	harness.completePairingSession(t, created.PairingSessionID, completePairingSessionRequest{
		PairingSecret: created.PairingSecret,
	})

	now := time.Now().UTC()
	harness.seedEnvelope(t, domain.Envelope{
		ID:                "env_expired",
		SenderDeviceID:    created.InitiatorDeviceID,
		RecipientDeviceID: joined.JoinerDeviceID,
		Channel:           domain.ChannelClipboard,
		ContentType:       "application/json",
		Nonce:             []byte("nonce-expired"),
		HeaderAAD:         []byte(`{"state":"expired"}`),
		Ciphertext:        []byte(`{"value":"expired"}`),
		CreatedAt:         now.Add(-2 * time.Minute),
		ExpiresAt:         now.Add(-time.Minute),
	})
	harness.seedEnvelope(t, domain.Envelope{
		ID:                "env_active",
		SenderDeviceID:    created.InitiatorDeviceID,
		RecipientDeviceID: joined.JoinerDeviceID,
		Channel:           domain.ChannelClipboard,
		ContentType:       "application/json",
		Nonce:             []byte("nonce-active"),
		HeaderAAD:         []byte(`{"state":"active"}`),
		Ciphertext:        []byte(`{"value":"active"}`),
		CreatedAt:         now.Add(-time.Minute),
		ExpiresAt:         now.Add(time.Minute),
	})

	pendingEnvelopes, err := harness.store.ListPendingEnvelopes(context.Background(), joined.JoinerDeviceID, now, 16)
	if err != nil {
		t.Fatalf("list pending envelopes with expiry filter failed: %v", err)
	}

	if len(pendingEnvelopes) != 1 {
		t.Fatalf("expected one active pending envelope, got %d", len(pendingEnvelopes))
	}

	if pendingEnvelopes[0].ID != "env_active" {
		t.Fatalf("unexpected pending envelope after expiry filter: got %q want %q", pendingEnvelopes[0].ID, "env_active")
	}

	recipientConnection := harness.openWebSocket(t, joined.JoinerDeviceID, joined.JoinerRelayToken)
	defer recipientConnection.Close(websocket.StatusNormalClosure, "test complete")

	connected := harness.readServerMessage(t, recipientConnection)
	if connected.Type != "connected" {
		t.Fatalf("unexpected websocket connected message: %#v", connected)
	}

	deliveredEnvelope := harness.readServerMessage(t, recipientConnection)
	if deliveredEnvelope.EnvelopeID != "env_active" {
		t.Fatalf("unexpected pending envelope delivery: got %q want %q", deliveredEnvelope.EnvelopeID, "env_active")
	}

	harness.writeClientMessage(t, recipientConnection, clientMessage{
		Type:       "ack_envelope",
		EnvelopeID: deliveredEnvelope.EnvelopeID,
	})

	harness.waitForNoPendingEnvelopes(t, joined.JoinerDeviceID, time.Second)
}

type integrationHarness struct {
	client     *http.Client
	httpServer *httptest.Server
	store      *sqlite.Store
}

func newIntegrationHarness(t *testing.T) *integrationHarness {
	t.Helper()

	databasePath := filepath.Join(t.TempDir(), "relay-test.db")
	store, err := sqlite.Open(databasePath)
	if err != nil {
		t.Fatalf("open sqlite store: %v", err)
	}

	t.Cleanup(func() {
		if err := store.Close(); err != nil {
			t.Fatalf("close sqlite store: %v", err)
		}
	})

	cfg := config.Config{
		HTTPAddress:           "127.0.0.1:0",
		DatabasePath:          databasePath,
		PairingTTL:            10 * time.Minute,
		MessageTTL:            24 * time.Hour,
		CleanupInterval:       time.Minute,
		WebSocketWriteTimeout: 2 * time.Second,
		ShutdownTimeout:       2 * time.Second,
	}

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	pairingService := service.NewPairingService(store, cfg.PairingTTL)
	relayService := service.NewRelayService(store, cfg.MessageTTL)
	server := NewServer(logger, cfg, pairingService, relayService)

	httpServer := httptest.NewServer(server.Handler())
	t.Cleanup(httpServer.Close)

	return &integrationHarness{
		client:     httpServer.Client(),
		httpServer: httpServer,
		store:      store,
	}
}

func (h *integrationHarness) createPairingSession(t *testing.T, request createPairingSessionRequest) createPairingSessionResponse {
	t.Helper()

	var response createPairingSessionResponse
	h.doJSONRequest(t, http.MethodPost, "/api/v1/pairing/sessions", request, http.StatusCreated, &response)

	return response
}

func (h *integrationHarness) getPairingSession(t *testing.T, sessionID string, pairingSecret string) getPairingSessionResponse {
	t.Helper()

	targetURL := h.httpServer.URL + "/api/v1/pairing/sessions/" + sessionID + "?pairing_secret=" + url.QueryEscape(pairingSecret)

	response, err := h.client.Get(targetURL)
	if err != nil {
		t.Fatalf("GET pairing session failed: %v", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(response.Body)
		t.Fatalf("unexpected get pairing session status: got %d want %d body=%s", response.StatusCode, http.StatusOK, strings.TrimSpace(string(body)))
	}

	var payload getPairingSessionResponse
	decodeResponseBody(t, response, &payload)

	return payload
}

func (h *integrationHarness) joinPairingSession(t *testing.T, sessionID string, request joinPairingSessionRequest) joinPairingSessionResponse {
	t.Helper()

	var response joinPairingSessionResponse
	h.doJSONRequest(t, http.MethodPost, "/api/v1/pairing/sessions/"+sessionID+"/join", request, http.StatusOK, &response)

	return response
}

func (h *integrationHarness) completePairingSession(t *testing.T, sessionID string, request completePairingSessionRequest) map[string]string {
	t.Helper()

	var response map[string]string
	h.doJSONRequest(t, http.MethodPost, "/api/v1/pairing/sessions/"+sessionID+"/complete", request, http.StatusOK, &response)

	return response
}

func (h *integrationHarness) doJSONRequest(
	t *testing.T,
	method string,
	path string,
	requestBody any,
	expectedStatusCode int,
	responseBody any,
) {
	t.Helper()

	bodyBytes, err := json.Marshal(requestBody)
	if err != nil {
		t.Fatalf("marshal request body: %v", err)
	}

	request, err := http.NewRequest(method, h.httpServer.URL+path, bytes.NewReader(bodyBytes))
	if err != nil {
		t.Fatalf("create request: %v", err)
	}
	request.Header.Set("Content-Type", "application/json")

	response, err := h.client.Do(request)
	if err != nil {
		t.Fatalf("do request: %v", err)
	}
	defer response.Body.Close()

	if response.StatusCode != expectedStatusCode {
		body, _ := io.ReadAll(response.Body)
		t.Fatalf("unexpected status: got %d want %d body=%s", response.StatusCode, expectedStatusCode, strings.TrimSpace(string(body)))
	}

	decodeResponseBody(t, response, responseBody)
}

func (h *integrationHarness) openWebSocket(t *testing.T, deviceID string, relayToken string) *websocket.Conn {
	t.Helper()

	connection, response, err := h.dialWebSocket(deviceID, relayToken)
	if err != nil {
		if response != nil && response.Body != nil {
			defer response.Body.Close()
		}
		t.Fatalf("dial websocket: %v", err)
	}

	return connection
}

func (h *integrationHarness) expectWebSocketUnauthorized(t *testing.T, deviceID string, relayToken string) {
	t.Helper()

	connection, response, err := h.dialWebSocket(deviceID, relayToken)
	if connection != nil {
		connection.Close(websocket.StatusNormalClosure, "unexpected success")
		t.Fatal("expected websocket authentication failure before pairing completion")
	}

	if err == nil {
		t.Fatal("expected websocket dial to fail before pairing completion")
	}

	if response == nil {
		t.Fatalf("expected unauthorized HTTP response, got dial error without response: %v", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusUnauthorized {
		body, _ := io.ReadAll(response.Body)
		t.Fatalf("unexpected websocket auth status: got %d want %d body=%s", response.StatusCode, http.StatusUnauthorized, strings.TrimSpace(string(body)))
	}
}

func (h *integrationHarness) readServerMessage(t *testing.T, connection *websocket.Conn) serverMessage {
	t.Helper()

	message, err := h.readServerMessageWithTimeout(connection, 2*time.Second)
	if err != nil {
		t.Fatalf("read websocket message: %v", err)
	}

	return message
}

func (h *integrationHarness) writeClientMessage(t *testing.T, connection *websocket.Conn, message clientMessage) {
	t.Helper()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	if err := wsjson.Write(ctx, connection, message); err != nil {
		t.Fatalf("write websocket message: %v", err)
	}
}

func (h *integrationHarness) seedEnvelope(t *testing.T, envelope domain.Envelope) {
	t.Helper()

	if err := h.store.CreateEnvelope(context.Background(), envelope); err != nil {
		t.Fatalf("seed envelope: %v", err)
	}
}

func decodeResponseBody(t *testing.T, response *http.Response, destination any) {
	t.Helper()

	if err := json.NewDecoder(response.Body).Decode(destination); err != nil {
		t.Fatalf("decode response body: %v", err)
	}
}

func encodeBase64(raw string) string {
	return base64.RawStdEncoding.EncodeToString([]byte(raw))
}

func (h *integrationHarness) dialWebSocket(deviceID string, relayToken string) (*websocket.Conn, *http.Response, error) {
	webSocketURL := "ws" + strings.TrimPrefix(h.httpServer.URL, "http") + "/api/v1/ws?device_id=" + url.QueryEscape(deviceID) + "&relay_token=" + url.QueryEscape(relayToken)

	return websocket.Dial(context.Background(), webSocketURL, nil)
}

func (h *integrationHarness) readServerMessageWithTimeout(connection *websocket.Conn, timeout time.Duration) (serverMessage, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	var message serverMessage
	if err := wsjson.Read(ctx, connection, &message); err != nil {
		if errors.Is(err, context.DeadlineExceeded) {
			return serverMessage{}, err
		}

		return serverMessage{}, err
	}

	return message, nil
}

func (h *integrationHarness) waitForNoPendingEnvelopes(t *testing.T, recipientDeviceID string, timeout time.Duration) {
	t.Helper()

	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		pendingEnvelopes, err := h.store.ListPendingEnvelopes(context.Background(), recipientDeviceID, time.Now().UTC(), 16)
		if err != nil {
			t.Fatalf("list pending envelopes: %v", err)
		}

		if len(pendingEnvelopes) == 0 {
			return
		}

		time.Sleep(20 * time.Millisecond)
	}

	pendingEnvelopes, err := h.store.ListPendingEnvelopes(context.Background(), recipientDeviceID, time.Now().UTC(), 16)
	if err != nil {
		t.Fatalf("list pending envelopes after timeout: %v", err)
	}

	t.Fatalf("expected no pending envelopes after timeout, got %d", len(pendingEnvelopes))
}
