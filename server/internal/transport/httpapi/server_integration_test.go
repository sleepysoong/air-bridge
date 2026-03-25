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
		t.Fatalf("GET /healthz 요청에 실패했어요: %v", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		t.Fatalf("상태 코드는 %d 이어야 해요. 실제 값: %d", http.StatusOK, response.StatusCode)
	}

	var payload map[string]string
	decodeResponseBody(t, response, &payload)

	if payload["status"] != "ok" {
		t.Fatalf("health 응답 payload 가 예상과 달라요: %#v", payload)
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
		t.Fatalf("페어링 세션 생성 응답에 필요한 값이 빠져 있어요: %#v", created)
	}

	initialSession := harness.getPairingSession(t, created.PairingSessionID, created.PairingSecret)
	if initialSession.State != "pending" {
		t.Fatalf("초기 상태는 %q 이어야 해요. 실제 값: %q", "pending", initialSession.State)
	}

	if initialSession.InitiatorName != "sleepysoong-macbook-air" {
		t.Fatalf("시작 기기 이름이 예상과 달라요: %q", initialSession.InitiatorName)
	}

	joined := harness.joinPairingSession(t, created.PairingSessionID, joinPairingSessionRequest{
		PairingSecret: created.PairingSecret,
		DeviceName:    "pixel-android",
		Platform:      "android",
		PublicKey:     encodeBase64(strings.Repeat("j", 32)),
	})

	if joined.JoinerDeviceID == "" || joined.JoinerRelayToken == "" {
		t.Fatalf("페어링 참여 응답에 필요한 값이 빠져 있어요: %#v", joined)
	}

	readySession := harness.getPairingSession(t, created.PairingSessionID, created.PairingSecret)
	if readySession.State != "ready" {
		t.Fatalf("참여 뒤 상태는 %q 이어야 해요. 실제 값: %q", "ready", readySession.State)
	}

	if readySession.JoinerName != "pixel-android" {
		t.Fatalf("참여 기기 이름이 예상과 달라요: %q", readySession.JoinerName)
	}

	completed := harness.completePairingSession(t, created.PairingSessionID, completePairingSessionRequest{
		PairingSecret: created.PairingSecret,
	})

	if completed["state"] != "completed" {
		t.Fatalf("완료 응답 상태가 예상과 달라요: %#v", completed)
	}

	finalSession := harness.getPairingSession(t, created.PairingSessionID, created.PairingSecret)
	if finalSession.State != "completed" {
		t.Fatalf("최종 상태는 %q 이어야 해요. 실제 값: %q", "completed", finalSession.State)
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
	defer connection.Close(websocket.StatusNormalClosure, "테스트를 마칠게요")

	connected := harness.readServerMessage(t, connection)
	if connected.Type != "connected" {
		t.Fatalf("페어링 완료 뒤 WebSocket connected 메시지가 예상과 달라요: %#v", connected)
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
	defer recipientConnection.Close(websocket.StatusNormalClosure, "테스트를 마칠게요")

	senderConnection := harness.openWebSocket(t, created.InitiatorDeviceID, created.InitiatorRelayToken)
	defer senderConnection.Close(websocket.StatusNormalClosure, "테스트를 마칠게요")

	recipientConnected := harness.readServerMessage(t, recipientConnection)
	if recipientConnected.Type != "connected" {
		t.Fatalf("수신 측 connected 메시지가 예상과 달라요: %#v", recipientConnected)
	}

	senderConnected := harness.readServerMessage(t, senderConnection)
	if senderConnected.Type != "connected" {
		t.Fatalf("발신 측 connected 메시지가 예상과 달라요: %#v", senderConnected)
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
		t.Fatalf("전달된 WebSocket 메시지 타입이 예상과 달라요: %#v", deliveredEnvelope)
	}

	if deliveredEnvelope.SenderDeviceID != created.InitiatorDeviceID {
		t.Fatalf("발신 기기 ID는 %q 이어야 해요. 실제 값: %q", created.InitiatorDeviceID, deliveredEnvelope.SenderDeviceID)
	}

	if deliveredEnvelope.Channel != "clipboard" {
		t.Fatalf("채널은 %q 이어야 해요. 실제 값: %q", "clipboard", deliveredEnvelope.Channel)
	}

	if deliveredEnvelope.ContentType != "application/json" {
		t.Fatalf("content_type 은 %q 이어야 해요. 실제 값: %q", "application/json", deliveredEnvelope.ContentType)
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
		t.Fatalf("만료 필터를 적용한 pending envelope 조회에 실패했어요: %v", err)
	}

	if len(pendingEnvelopes) != 1 {
		t.Fatalf("활성 pending envelope 개수는 1이어야 해요. 실제 값: %d", len(pendingEnvelopes))
	}

	if pendingEnvelopes[0].ID != "env_active" {
		t.Fatalf("만료 필터 뒤 남아야 하는 envelope 는 %q 이어야 해요. 실제 값: %q", "env_active", pendingEnvelopes[0].ID)
	}

	recipientConnection := harness.openWebSocket(t, joined.JoinerDeviceID, joined.JoinerRelayToken)
	defer recipientConnection.Close(websocket.StatusNormalClosure, "테스트를 마칠게요")

	connected := harness.readServerMessage(t, recipientConnection)
	if connected.Type != "connected" {
		t.Fatalf("connected 메시지가 예상과 달라요: %#v", connected)
	}

	deliveredEnvelope := harness.readServerMessage(t, recipientConnection)
	if deliveredEnvelope.EnvelopeID != "env_active" {
		t.Fatalf("전달된 pending envelope 는 %q 이어야 해요. 실제 값: %q", "env_active", deliveredEnvelope.EnvelopeID)
	}

	harness.writeClientMessage(t, recipientConnection, clientMessage{
		Type:       "ack_envelope",
		EnvelopeID: deliveredEnvelope.EnvelopeID,
	})

	harness.waitForNoPendingEnvelopes(t, joined.JoinerDeviceID, time.Second)
}

func TestLookupPairingSessionRejectsOversizedBody(t *testing.T) {
	harness := newIntegrationHarness(t)

	created := harness.createPairingSession(t, createPairingSessionRequest{
		DeviceName: "sleepysoong-macbook-air",
		Platform:   "macos",
		PublicKey:  encodeBase64(strings.Repeat("i", 32)),
	})

	bodyBytes, err := json.Marshal(lookupPairingSessionRequest{
		PairingSecret: strings.Repeat("x", int(maxPairingRequestBodyBytes)),
	})
	if err != nil {
		t.Fatalf("lookup 요청 본문을 직렬화하지 못했어요: %v", err)
	}

	request, err := http.NewRequest(
		http.MethodPost,
		harness.httpServer.URL+"/api/v1/pairing/sessions/"+created.PairingSessionID+"/lookup",
		bytes.NewReader(bodyBytes),
	)
	if err != nil {
		t.Fatalf("lookup 요청 객체를 만들지 못했어요: %v", err)
	}
	request.Header.Set("Content-Type", "application/json")

	response, err := harness.client.Do(request)
	if err != nil {
		t.Fatalf("oversized lookup 요청을 보내지 못했어요: %v", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusBadRequest {
		body, _ := io.ReadAll(response.Body)
		t.Fatalf("oversized lookup 상태 코드는 %d 이어야 해요. 실제 값: %d body=%s", http.StatusBadRequest, response.StatusCode, strings.TrimSpace(string(body)))
	}
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
		t.Fatalf("SQLite 테스트 저장소를 열지 못했어요: %v", err)
	}

	t.Cleanup(func() {
		if err := store.Close(); err != nil {
			t.Fatalf("SQLite 테스트 저장소를 닫지 못했어요: %v", err)
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
	pairingService := service.NewPairingService(logger, store, cfg.PairingTTL)
	relayService := service.NewRelayService(logger, store, cfg.MessageTTL)
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

	var payload getPairingSessionResponse
	h.doJSONRequest(
		t,
		http.MethodPost,
		"/api/v1/pairing/sessions/"+sessionID+"/lookup",
		lookupPairingSessionRequest{PairingSecret: pairingSecret},
		http.StatusOK,
		&payload,
	)

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
		t.Fatalf("요청 본문을 직렬화하지 못했어요: %v", err)
	}

	request, err := http.NewRequest(method, h.httpServer.URL+path, bytes.NewReader(bodyBytes))
	if err != nil {
		t.Fatalf("요청 객체를 만들지 못했어요: %v", err)
	}
	request.Header.Set("Content-Type", "application/json")

	response, err := h.client.Do(request)
	if err != nil {
		t.Fatalf("요청을 보내지 못했어요: %v", err)
	}
	defer response.Body.Close()

	if response.StatusCode != expectedStatusCode {
		body, _ := io.ReadAll(response.Body)
		t.Fatalf("상태 코드는 %d 이어야 해요. 실제 값: %d body=%s", expectedStatusCode, response.StatusCode, strings.TrimSpace(string(body)))
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
		t.Fatalf("WebSocket 연결을 열지 못했어요: %v", err)
	}

	return connection
}

func (h *integrationHarness) expectWebSocketUnauthorized(t *testing.T, deviceID string, relayToken string) {
	t.Helper()

	connection, response, err := h.dialWebSocket(deviceID, relayToken)
	if connection != nil {
		connection.Close(websocket.StatusNormalClosure, "예상하지 않은 성공이라서 닫을게요")
		t.Fatal("페어링 완료 전에는 WebSocket 인증이 실패해야 해요")
	}

	if err == nil {
		t.Fatal("페어링 완료 전에는 WebSocket 연결이 실패해야 해요")
	}

	if response == nil {
		t.Fatalf("권한 오류 HTTP 응답이 와야 해요. 실제 값: %v", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusUnauthorized {
		body, _ := io.ReadAll(response.Body)
		t.Fatalf("WebSocket 인증 상태 코드는 %d 이어야 해요. 실제 값: %d body=%s", http.StatusUnauthorized, response.StatusCode, strings.TrimSpace(string(body)))
	}
}

func (h *integrationHarness) readServerMessage(t *testing.T, connection *websocket.Conn) serverMessage {
	t.Helper()

	message, err := h.readServerMessageWithTimeout(connection, 2*time.Second)
	if err != nil {
		t.Fatalf("WebSocket 메시지를 읽지 못했어요: %v", err)
	}

	return message
}

func (h *integrationHarness) writeClientMessage(t *testing.T, connection *websocket.Conn, message clientMessage) {
	t.Helper()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	if err := wsjson.Write(ctx, connection, message); err != nil {
		t.Fatalf("WebSocket 메시지를 쓰지 못했어요: %v", err)
	}
}

func (h *integrationHarness) seedEnvelope(t *testing.T, envelope domain.Envelope) {
	t.Helper()

	if err := h.store.CreateEnvelope(context.Background(), envelope); err != nil {
		t.Fatalf("테스트용 envelope를 넣지 못했어요: %v", err)
	}
}

func decodeResponseBody(t *testing.T, response *http.Response, destination any) {
	t.Helper()

	if err := json.NewDecoder(response.Body).Decode(destination); err != nil {
		t.Fatalf("응답 본문을 디코딩하지 못했어요: %v", err)
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
			t.Fatalf("대기 중인 envelope를 조회하지 못했어요: %v", err)
		}

		if len(pendingEnvelopes) == 0 {
			return
		}

		time.Sleep(20 * time.Millisecond)
	}

	pendingEnvelopes, err := h.store.ListPendingEnvelopes(context.Background(), recipientDeviceID, time.Now().UTC(), 16)
	if err != nil {
		t.Fatalf("대기 시간 뒤 pending envelope를 조회하지 못했어요: %v", err)
	}

	t.Fatalf("대기 시간 뒤 pending envelope 개수는 0이어야 해요. 실제 값: %d", len(pendingEnvelopes))
}
