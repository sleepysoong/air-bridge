package httpapi

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"github.com/sleepysoong/air-bridge/server/internal/config"
	"github.com/sleepysoong/air-bridge/server/internal/domain"
	"github.com/sleepysoong/air-bridge/server/internal/service"
)

type Server struct {
	logger         *slog.Logger
	config         config.Config
	pairingService *service.PairingService
	relayService   *service.RelayService
	hub            *connectionHub
	mux            *http.ServeMux
}

func NewServer(
	logger *slog.Logger,
	cfg config.Config,
	pairingService *service.PairingService,
	relayService *service.RelayService,
) *Server {
	server := &Server{
		logger:         logger,
		config:         cfg,
		pairingService: pairingService,
		relayService:   relayService,
		hub:            newConnectionHub(logger, cfg.WebSocketWriteTimeout),
		mux:            http.NewServeMux(),
	}

	server.routes()

	return server
}

func (s *Server) Handler() http.Handler {
	return s.mux
}

func (s *Server) routes() {
	s.mux.HandleFunc("GET /healthz", s.handleHealth)
	s.mux.HandleFunc("POST /api/v1/pairing/sessions", s.handleCreatePairingSession)
	s.mux.HandleFunc("GET /api/v1/pairing/sessions/{sessionID}", s.handleGetPairingSession)
	s.mux.HandleFunc("POST /api/v1/pairing/sessions/{sessionID}/join", s.handleJoinPairingSession)
	s.mux.HandleFunc("POST /api/v1/pairing/sessions/{sessionID}/complete", s.handleCompletePairingSession)
	s.mux.HandleFunc("GET /api/v1/ws", s.handleWebSocket)
}

func (s *Server) handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"status": "ok",
	})
}

type createPairingSessionRequest struct {
	DeviceName string `json:"device_name"`
	Platform   string `json:"platform"`
	PublicKey  string `json:"public_key"`
}

type createPairingSessionResponse struct {
	PairingSessionID    string `json:"pairing_session_id"`
	PairingSecret       string `json:"pairing_secret"`
	InitiatorDeviceID   string `json:"initiator_device_id"`
	InitiatorRelayToken string `json:"initiator_relay_token"`
	ExpiresAt           string `json:"expires_at"`
}

func (s *Server) handleCreatePairingSession(w http.ResponseWriter, r *http.Request) {
	var request createPairingSessionRequest
	if err := decodeJSON(r, &request); err != nil {
		s.writeError(w, http.StatusBadRequest, "invalid_json", err.Error())
		return
	}

	platform, err := domain.ParsePlatform(request.Platform)
	if err != nil {
		s.writeError(w, http.StatusBadRequest, "invalid_platform", err.Error())
		return
	}

	publicKey, err := decodeBase64Field(request.PublicKey, "public_key")
	if err != nil {
		s.writeError(w, http.StatusBadRequest, "invalid_public_key", err.Error())
		return
	}

	result, err := s.pairingService.CreateSession(r.Context(), service.CreatePairingSessionInput{
		InitiatorDeviceName: request.DeviceName,
		InitiatorPlatform:   platform,
		InitiatorPublicKey:  publicKey,
	})
	if err != nil {
		s.writeServiceError(w, err)
		return
	}

	writeJSON(w, http.StatusCreated, createPairingSessionResponse{
		PairingSessionID:    result.Session.ID,
		PairingSecret:       result.PairingSecret,
		InitiatorDeviceID:   result.InitiatorDevice.ID,
		InitiatorRelayToken: result.InitiatorRelayToken,
		ExpiresAt:           result.Session.ExpiresAt.Format(time.RFC3339),
	})
}

type getPairingSessionResponse struct {
	PairingSessionID   string `json:"pairing_session_id"`
	State              string `json:"state"`
	InitiatorDeviceID  string `json:"initiator_device_id"`
	InitiatorName      string `json:"initiator_name"`
	InitiatorPlatform  string `json:"initiator_platform"`
	InitiatorPublicKey string `json:"initiator_public_key"`
	JoinerDeviceID     string `json:"joiner_device_id,omitempty"`
	JoinerName         string `json:"joiner_name,omitempty"`
	JoinerPlatform     string `json:"joiner_platform,omitempty"`
	JoinerPublicKey    string `json:"joiner_public_key,omitempty"`
	ExpiresAt          string `json:"expires_at"`
	UpdatedAt          string `json:"updated_at"`
	CompletedAt        string `json:"completed_at,omitempty"`
}

func (s *Server) handleGetPairingSession(w http.ResponseWriter, r *http.Request) {
	sessionID := r.PathValue("sessionID")
	pairingSecret := r.URL.Query().Get("pairing_secret")

	session, err := s.pairingService.GetSession(r.Context(), sessionID, pairingSecret)
	if err != nil {
		s.writeServiceError(w, err)
		return
	}

	response := getPairingSessionResponse{
		PairingSessionID:   session.ID,
		State:              string(session.State),
		InitiatorDeviceID:  session.InitiatorDeviceID,
		InitiatorName:      session.InitiatorName,
		InitiatorPlatform:  string(session.InitiatorPlatform),
		InitiatorPublicKey: base64.RawStdEncoding.EncodeToString(session.InitiatorPublicKey),
		ExpiresAt:          session.ExpiresAt.Format(time.RFC3339),
		UpdatedAt:          session.UpdatedAt.Format(time.RFC3339),
	}

	if session.JoinerDeviceID != "" {
		response.JoinerDeviceID = session.JoinerDeviceID
		response.JoinerName = session.JoinerName
		response.JoinerPlatform = string(session.JoinerPlatform)
		response.JoinerPublicKey = base64.RawStdEncoding.EncodeToString(session.JoinerPublicKey)
	}

	if session.CompletedAt != nil {
		response.CompletedAt = session.CompletedAt.Format(time.RFC3339)
	}

	writeJSON(w, http.StatusOK, response)
}

type joinPairingSessionRequest struct {
	PairingSecret string `json:"pairing_secret"`
	DeviceName    string `json:"device_name"`
	Platform      string `json:"platform"`
	PublicKey     string `json:"public_key"`
}

type joinPairingSessionResponse struct {
	PairingSessionID   string `json:"pairing_session_id"`
	JoinerDeviceID     string `json:"joiner_device_id"`
	JoinerRelayToken   string `json:"joiner_relay_token"`
	InitiatorDeviceID  string `json:"initiator_device_id"`
	InitiatorPublicKey string `json:"initiator_public_key"`
	ExpiresAt          string `json:"expires_at"`
}

func (s *Server) handleJoinPairingSession(w http.ResponseWriter, r *http.Request) {
	var request joinPairingSessionRequest
	if err := decodeJSON(r, &request); err != nil {
		s.writeError(w, http.StatusBadRequest, "invalid_json", err.Error())
		return
	}

	platform, err := domain.ParsePlatform(request.Platform)
	if err != nil {
		s.writeError(w, http.StatusBadRequest, "invalid_platform", err.Error())
		return
	}

	publicKey, err := decodeBase64Field(request.PublicKey, "public_key")
	if err != nil {
		s.writeError(w, http.StatusBadRequest, "invalid_public_key", err.Error())
		return
	}

	result, err := s.pairingService.JoinSession(r.Context(), service.JoinPairingSessionInput{
		SessionID:        r.PathValue("sessionID"),
		PairingSecret:    request.PairingSecret,
		JoinerDeviceName: request.DeviceName,
		JoinerPlatform:   platform,
		JoinerPublicKey:  publicKey,
	})
	if err != nil {
		s.writeServiceError(w, err)
		return
	}

	writeJSON(w, http.StatusOK, joinPairingSessionResponse{
		PairingSessionID:   result.Session.ID,
		JoinerDeviceID:     result.JoinerDevice.ID,
		JoinerRelayToken:   result.JoinerRelayToken,
		InitiatorDeviceID:  result.InitiatorDeviceID,
		InitiatorPublicKey: base64.RawStdEncoding.EncodeToString(result.InitiatorPublicKey),
		ExpiresAt:          result.Session.ExpiresAt.Format(time.RFC3339),
	})
}

type completePairingSessionRequest struct {
	PairingSecret string `json:"pairing_secret"`
}

func (s *Server) handleCompletePairingSession(w http.ResponseWriter, r *http.Request) {
	var request completePairingSessionRequest
	if err := decodeJSON(r, &request); err != nil {
		s.writeError(w, http.StatusBadRequest, "invalid_json", err.Error())
		return
	}

	session, err := s.pairingService.CompleteSession(r.Context(), r.PathValue("sessionID"), request.PairingSecret)
	if err != nil {
		s.writeServiceError(w, err)
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"pairing_session_id": session.ID,
		"state":              session.State,
		"completed_at":       session.CompletedAt.Format(time.RFC3339),
	})
}

func (s *Server) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	deviceID := r.URL.Query().Get("device_id")
	relayToken := r.URL.Query().Get("relay_token")

	device, err := s.relayService.AuthenticateDevice(r.Context(), deviceID, relayToken)
	if err != nil {
		s.writeServiceError(w, err)
		return
	}

	connection, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		InsecureSkipVerify: false,
	})
	if err != nil {
		s.logger.Error("accept websocket", "error", err)
		return
	}
	defer connection.Close(websocket.StatusNormalClosure, "connection closed")

	client := newClientConnection(connection, s.config.WebSocketWriteTimeout)
	removeConnection := s.hub.add(device.ID, client)
	defer removeConnection()

	if err := client.write(r.Context(), serverMessage{
		Type:         "connected",
		DeviceID:     device.ID,
		PeerDeviceID: device.PeerDeviceID,
	}); err != nil {
		s.logger.Error("write websocket connected message", "device_id", device.ID, "error", err)
		return
	}

	if err := s.sendPendingEnvelopes(r.Context(), device.ID, client); err != nil {
		s.logger.Error("send pending envelopes", "device_id", device.ID, "error", err)
		return
	}

	for {
		var message clientMessage
		if err := wsjson.Read(r.Context(), connection, &message); err != nil {
			if websocket.CloseStatus(err) == websocket.StatusNormalClosure {
				return
			}

			if errors.Is(err, context.Canceled) {
				return
			}

			s.logger.Info("websocket read loop closed", "device_id", device.ID, "error", err)
			return
		}

		switch message.Type {
		case "ping":
			if err := client.write(r.Context(), serverMessage{Type: "pong"}); err != nil {
				s.logger.Error("write websocket pong", "device_id", device.ID, "error", err)
				return
			}
		case "send_envelope":
			if err := s.handleClientEnvelope(r.Context(), device, message); err != nil {
				client.write(r.Context(), serverMessage{
					Type:    "error",
					Code:    "send_failed",
					Message: err.Error(),
				})
			}
		case "ack_envelope":
			if err := s.relayService.AcknowledgeEnvelope(r.Context(), device.ID, message.EnvelopeID); err != nil {
				client.write(r.Context(), serverMessage{
					Type:    "error",
					Code:    "ack_failed",
					Message: err.Error(),
				})
			}
		default:
			client.write(r.Context(), serverMessage{
				Type:    "error",
				Code:    "unknown_message_type",
				Message: "unknown websocket message type",
			})
		}
	}
}

func (s *Server) handleClientEnvelope(ctx context.Context, sender domain.Device, message clientMessage) error {
	channel, err := domain.ParseChannel(message.Channel)
	if err != nil {
		return err
	}

	nonce, err := decodeBase64Field(message.Nonce, "nonce")
	if err != nil {
		return err
	}

	headerAAD, err := decodeBase64Field(message.HeaderAAD, "header_aad")
	if err != nil {
		return err
	}

	ciphertext, err := decodeBase64Field(message.Ciphertext, "ciphertext")
	if err != nil {
		return err
	}

	envelope, err := s.relayService.QueueEnvelope(ctx, service.QueueEnvelopeInput{
		SenderDeviceID:    sender.ID,
		RecipientDeviceID: message.RecipientDeviceID,
		Channel:           channel,
		ContentType:       message.ContentType,
		Nonce:             nonce,
		HeaderAAD:         headerAAD,
		Ciphertext:        ciphertext,
	})
	if err != nil {
		return err
	}

	s.hub.sendToDevice(envelope.RecipientDeviceID, envelopeToServerMessage(envelope))

	return nil
}

func (s *Server) sendPendingEnvelopes(ctx context.Context, deviceID string, client *clientConnection) error {
	envelopes, err := s.relayService.PendingEnvelopes(ctx, deviceID)
	if err != nil {
		return err
	}

	for _, envelope := range envelopes {
		if err := client.write(ctx, envelopeToServerMessage(envelope)); err != nil {
			return err
		}
	}

	return nil
}

func (s *Server) writeServiceError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, service.ErrInvalidInput):
		s.writeError(w, http.StatusBadRequest, "invalid_input", err.Error())
	case errors.Is(err, service.ErrUnauthorized):
		s.writeError(w, http.StatusUnauthorized, "unauthorized", err.Error())
	case errors.Is(err, service.ErrNotFound):
		s.writeError(w, http.StatusNotFound, "not_found", err.Error())
	case errors.Is(err, service.ErrConflict):
		s.writeError(w, http.StatusConflict, "conflict", err.Error())
	case errors.Is(err, service.ErrExpired):
		s.writeError(w, http.StatusGone, "expired", err.Error())
	default:
		s.logger.Error("unhandled service error", "error", err)
		s.writeError(w, http.StatusInternalServerError, "internal_error", "internal server error")
	}
}

func (s *Server) writeError(w http.ResponseWriter, statusCode int, code string, message string) {
	writeJSON(w, statusCode, map[string]any{
		"error": map[string]string{
			"code":    code,
			"message": message,
		},
	})
}

type clientMessage struct {
	Type              string `json:"type"`
	EnvelopeID        string `json:"envelope_id,omitempty"`
	RecipientDeviceID string `json:"recipient_device_id,omitempty"`
	Channel           string `json:"channel,omitempty"`
	ContentType       string `json:"content_type,omitempty"`
	Nonce             string `json:"nonce,omitempty"`
	HeaderAAD         string `json:"header_aad,omitempty"`
	Ciphertext        string `json:"ciphertext,omitempty"`
}

type serverMessage struct {
	Type           string `json:"type"`
	Code           string `json:"code,omitempty"`
	Message        string `json:"message,omitempty"`
	DeviceID       string `json:"device_id,omitempty"`
	PeerDeviceID   string `json:"peer_device_id,omitempty"`
	EnvelopeID     string `json:"envelope_id,omitempty"`
	SenderDeviceID string `json:"sender_device_id,omitempty"`
	Channel        string `json:"channel,omitempty"`
	ContentType    string `json:"content_type,omitempty"`
	Nonce          string `json:"nonce,omitempty"`
	HeaderAAD      string `json:"header_aad,omitempty"`
	Ciphertext     string `json:"ciphertext,omitempty"`
	CreatedAt      string `json:"created_at,omitempty"`
	ExpiresAt      string `json:"expires_at,omitempty"`
}

func envelopeToServerMessage(envelope domain.Envelope) serverMessage {
	return serverMessage{
		Type:           "envelope",
		EnvelopeID:     envelope.ID,
		SenderDeviceID: envelope.SenderDeviceID,
		Channel:        string(envelope.Channel),
		ContentType:    envelope.ContentType,
		Nonce:          base64.RawStdEncoding.EncodeToString(envelope.Nonce),
		HeaderAAD:      base64.RawStdEncoding.EncodeToString(envelope.HeaderAAD),
		Ciphertext:     base64.RawStdEncoding.EncodeToString(envelope.Ciphertext),
		CreatedAt:      envelope.CreatedAt.Format(time.RFC3339),
		ExpiresAt:      envelope.ExpiresAt.Format(time.RFC3339),
	}
}

func decodeJSON(r *http.Request, destination any) error {
	defer r.Body.Close()

	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()

	if err := decoder.Decode(destination); err != nil {
		return err
	}

	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		return errors.New("request body must contain a single JSON object")
	}

	return nil
}

func writeJSON(w http.ResponseWriter, statusCode int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)
	_ = json.NewEncoder(w).Encode(value)
}

func decodeBase64Field(value string, fieldName string) ([]byte, error) {
	trimmed := strings.TrimSpace(value)
	if trimmed == "" {
		return nil, errors.New(fieldName + " is required")
	}

	decoded, err := base64.RawStdEncoding.DecodeString(trimmed)
	if err != nil {
		return nil, err
	}

	return decoded, nil
}

type clientConnection struct {
	connection   *websocket.Conn
	writeTimeout time.Duration
	writeMu      sync.Mutex
}

func newClientConnection(connection *websocket.Conn, writeTimeout time.Duration) *clientConnection {
	return &clientConnection{
		connection:   connection,
		writeTimeout: writeTimeout,
	}
}

func (c *clientConnection) write(ctx context.Context, message serverMessage) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()

	writeCtx, cancel := context.WithTimeout(ctx, c.writeTimeout)
	defer cancel()

	return wsjson.Write(writeCtx, c.connection, message)
}

type connectionHub struct {
	logger       *slog.Logger
	writeTimeout time.Duration
	mu           sync.RWMutex
	clients      map[string]map[*clientConnection]struct{}
}

func newConnectionHub(logger *slog.Logger, writeTimeout time.Duration) *connectionHub {
	return &connectionHub{
		logger:       logger,
		writeTimeout: writeTimeout,
		clients:      make(map[string]map[*clientConnection]struct{}),
	}
}

func (h *connectionHub) add(deviceID string, client *clientConnection) func() {
	h.mu.Lock()
	defer h.mu.Unlock()

	deviceClients, ok := h.clients[deviceID]
	if !ok {
		deviceClients = make(map[*clientConnection]struct{})
		h.clients[deviceID] = deviceClients
	}

	deviceClients[client] = struct{}{}

	return func() {
		h.mu.Lock()
		defer h.mu.Unlock()

		deviceClients, ok := h.clients[deviceID]
		if !ok {
			return
		}

		delete(deviceClients, client)
		if len(deviceClients) == 0 {
			delete(h.clients, deviceID)
		}
	}
}

func (h *connectionHub) sendToDevice(deviceID string, message serverMessage) {
	h.mu.RLock()
	deviceClients := h.clients[deviceID]
	clients := make([]*clientConnection, 0, len(deviceClients))
	for client := range deviceClients {
		clients = append(clients, client)
	}
	h.mu.RUnlock()

	for _, client := range clients {
		if err := client.write(context.Background(), message); err != nil {
			h.logger.Error("push websocket message", "device_id", deviceID, "error", err)
		}
	}
}
