package httpapi

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
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

const (
	maxPairingRequestBodyBytes = 16 * 1024
	maxWebSocketMessageBytes   = 28 * 1024 * 1024
)

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
	s.mux.HandleFunc("GET /api/v1/server/info", s.handleServerInfo)
	s.mux.HandleFunc("POST /api/v1/pairing/sessions", s.handleCreatePairingSession)
	s.mux.HandleFunc("POST /api/v1/pairing/sessions/{sessionID}/lookup", s.handleLookupPairingSession)
	s.mux.HandleFunc("POST /api/v1/pairing/sessions/{sessionID}/join", s.handleJoinPairingSession)
	s.mux.HandleFunc("POST /api/v1/pairing/sessions/{sessionID}/complete", s.handleCompletePairingSession)
	s.mux.HandleFunc("GET /api/v1/ws", s.handleWebSocket)
}

func (s *Server) handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"status": "ok",
	})
}

type serverInfoResponse struct {
	Addresses []string `json:"addresses"`
}

func (s *Server) handleServerInfo(w http.ResponseWriter, _ *http.Request) {
	addresses := s.collectServerAddresses()
	writeJSON(w, http.StatusOK, serverInfoResponse{
		Addresses: addresses,
	})
}

func (s *Server) collectServerAddresses() []string {
	port := s.config.HTTPAddress
	if strings.HasPrefix(port, ":") {
		port = port[1:]
	} else if idx := strings.LastIndex(port, ":"); idx != -1 {
		port = port[idx+1:]
	}

	var addresses []string
	var mu sync.Mutex
	var wg sync.WaitGroup

	wg.Add(1)
	go func() {
		defer wg.Done()
		interfaces, err := net.Interfaces()
		if err != nil {
			s.logger.Warn("네트워크 인터페이스 목록을 가져오지 못했어요", "error", err)
			return
		}

		for _, iface := range interfaces {
			if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
				continue
			}

			addrs, err := iface.Addrs()
			if err != nil {
				continue
			}

			for _, addr := range addrs {
				var ip net.IP
				switch v := addr.(type) {
				case *net.IPNet:
					ip = v.IP
				case *net.IPAddr:
					ip = v.IP
				}

				if ip == nil || ip.IsLoopback() {
					continue
				}

				if ip4 := ip.To4(); ip4 != nil {
					mu.Lock()
					addresses = append(addresses, fmt.Sprintf("http://%s:%s", ip4.String(), port))
					mu.Unlock()
				}
			}
		}
	}()

	wg.Add(1)
	go func() {
		defer wg.Done()
		publicIP := s.fetchPublicIP()
		if publicIP != "" {
			mu.Lock()
			addresses = append(addresses, fmt.Sprintf("http://%s:%s", publicIP, port))
			mu.Unlock()
		}
	}()

	wg.Wait()

	return addresses
}

func (s *Server) fetchPublicIP() string {
	client := http.Client{
		Timeout: 2 * time.Second,
	}

	resp, err := client.Get("https://api.ipify.org")
	if err != nil {
		s.logger.Warn("외부 IP를 가져오지 못했어요 (api.ipify.org)", "error", err)
		resp, err = client.Get("https://ifconfig.me/ip")
		if err != nil {
			s.logger.Warn("외부 IP를 가져오지 못했어요 (ifconfig.me)", "error", err)
			return ""
		}
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		s.logger.Warn("외부 IP 조회 응답 상태 코드가 올바르지 않아요", "status", resp.StatusCode)
		return ""
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		s.logger.Warn("외부 IP 조회 응답 본문을 읽지 못했어요", "error", err)
		return ""
	}

	ip := strings.TrimSpace(string(body))
	if net.ParseIP(ip) == nil {
		s.logger.Warn("가져온 외부 IP의 형식이 올바르지 않아요", "ip", ip)
		return ""
	}

	return ip
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
	if err := decodeJSON(w, r, &request, maxPairingRequestBodyBytes); err != nil {
		s.writeError(w, http.StatusBadRequest, "invalid_json", err.Error())
		s.logger.Warn("페어링 세션 생성 요청 본문이 올바르지 않아요", "remote_addr", r.RemoteAddr, "path", r.URL.Path, "error", err)
		return
	}

	platform, err := domain.ParsePlatform(request.Platform)
	if err != nil {
		s.writeError(w, http.StatusBadRequest, "invalid_platform", err.Error())
		s.logger.Warn("페어링 세션 생성 요청의 플랫폼 값이 올바르지 않아요", "remote_addr", r.RemoteAddr, "path", r.URL.Path, "platform", request.Platform, "error", err)
		return
	}

	publicKey, err := decodeBase64Field(request.PublicKey, "public_key")
	if err != nil {
		s.writeError(w, http.StatusBadRequest, "invalid_public_key", err.Error())
		s.logger.Warn("페어링 세션 생성 요청의 공개키 값이 올바르지 않아요", "remote_addr", r.RemoteAddr, "path", r.URL.Path, "error", err)
		return
	}

	result, err := s.pairingService.CreateSession(r.Context(), service.CreatePairingSessionInput{
		InitiatorDeviceName: request.DeviceName,
		InitiatorPlatform:   platform,
		InitiatorPublicKey:  publicKey,
	})
	if err != nil {
		s.writeServiceError(w, r, err)
		return
	}

	s.logger.Info(
		"페어링 세션 생성 요청을 처리했어요",
		"session_id",
		result.Session.ID,
		"device_id",
		result.InitiatorDevice.ID,
		"platform",
		platform,
		"remote_addr",
		r.RemoteAddr,
	)

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

type lookupPairingSessionRequest struct {
	PairingSecret string `json:"pairing_secret"`
}

func (s *Server) handleLookupPairingSession(w http.ResponseWriter, r *http.Request) {
	var request lookupPairingSessionRequest
	if err := decodeJSON(w, r, &request, maxPairingRequestBodyBytes); err != nil {
		s.writeError(w, http.StatusBadRequest, "invalid_json", err.Error())
		s.logger.Warn("페어링 세션 조회 요청 본문이 올바르지 않아요", "remote_addr", r.RemoteAddr, "path", r.URL.Path, "error", err)
		return
	}

	sessionID := r.PathValue("sessionID")
	session, err := s.pairingService.GetSession(r.Context(), sessionID, request.PairingSecret)
	if err != nil {
		s.writeServiceError(w, r, err)
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

	s.logger.Info(
		"페어링 세션 조회 요청을 처리했어요",
		"session_id",
		session.ID,
		"state",
		session.State,
		"remote_addr",
		r.RemoteAddr,
	)

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
	if err := decodeJSON(w, r, &request, maxPairingRequestBodyBytes); err != nil {
		s.writeError(w, http.StatusBadRequest, "invalid_json", err.Error())
		s.logger.Warn("페어링 참여 요청 본문이 올바르지 않아요", "remote_addr", r.RemoteAddr, "path", r.URL.Path, "error", err)
		return
	}

	platform, err := domain.ParsePlatform(request.Platform)
	if err != nil {
		s.writeError(w, http.StatusBadRequest, "invalid_platform", err.Error())
		s.logger.Warn("페어링 참여 요청의 플랫폼 값이 올바르지 않아요", "remote_addr", r.RemoteAddr, "path", r.URL.Path, "platform", request.Platform, "error", err)
		return
	}

	publicKey, err := decodeBase64Field(request.PublicKey, "public_key")
	if err != nil {
		s.writeError(w, http.StatusBadRequest, "invalid_public_key", err.Error())
		s.logger.Warn("페어링 참여 요청의 공개키 값이 올바르지 않아요", "remote_addr", r.RemoteAddr, "path", r.URL.Path, "error", err)
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
		s.writeServiceError(w, r, err)
		return
	}

	s.logger.Info(
		"페어링 참여 요청을 처리했어요",
		"session_id",
		result.Session.ID,
		"joiner_device_id",
		result.JoinerDevice.ID,
		"initiator_device_id",
		result.InitiatorDeviceID,
		"platform",
		platform,
		"remote_addr",
		r.RemoteAddr,
	)

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
	if err := decodeJSON(w, r, &request, maxPairingRequestBodyBytes); err != nil {
		s.writeError(w, http.StatusBadRequest, "invalid_json", err.Error())
		s.logger.Warn("페어링 완료 요청 본문이 올바르지 않아요", "remote_addr", r.RemoteAddr, "path", r.URL.Path, "error", err)
		return
	}

	session, err := s.pairingService.CompleteSession(r.Context(), r.PathValue("sessionID"), request.PairingSecret)
	if err != nil {
		s.writeServiceError(w, r, err)
		return
	}

	s.logger.Info(
		"페어링 완료 요청을 처리했어요",
		"session_id",
		session.ID,
		"initiator_device_id",
		session.InitiatorDeviceID,
		"joiner_device_id",
		session.JoinerDeviceID,
		"remote_addr",
		r.RemoteAddr,
	)

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
		s.writeServiceError(w, r, err)
		return
	}

	connection, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		InsecureSkipVerify: false,
	})
	if err != nil {
		s.logger.Error("WebSocket 연결을 수락하지 못했어요", "device_id", deviceID, "remote_addr", r.RemoteAddr, "error", err)
		return
	}
	defer connection.Close(websocket.StatusNormalClosure, "연결을 정리할게요")
	connection.SetReadLimit(maxWebSocketMessageBytes)

	client := newClientConnection(connection, s.config.WebSocketWriteTimeout)
	removeConnection := s.hub.add(device.ID, client)
	defer removeConnection()

	s.logger.Info(
		"WebSocket 연결을 수락했어요",
		"device_id",
		device.ID,
		"peer_device_id",
		device.PeerDeviceID,
		"remote_addr",
		r.RemoteAddr,
	)

	if err := client.write(r.Context(), serverMessage{
		Type:         "connected",
		DeviceID:     device.ID,
		PeerDeviceID: device.PeerDeviceID,
	}); err != nil {
		s.logger.Error("WebSocket 연결 확인 메시지를 보내지 못했어요", "device_id", device.ID, "error", err)
		return
	}

	pendingCount, err := s.sendPendingEnvelopes(r.Context(), device.ID, client)
	if err != nil {
		s.logger.Error("대기 중인 envelope를 보내지 못했어요", "device_id", device.ID, "error", err)
		return
	}

	if pendingCount > 0 {
		s.logger.Info("대기 중인 envelope를 전송했어요", "device_id", device.ID, "pending_count", pendingCount)
	}

	for {
		var message clientMessage
		if err := wsjson.Read(r.Context(), connection, &message); err != nil {
			if websocket.CloseStatus(err) == websocket.StatusNormalClosure {
				s.logger.Info("WebSocket 연결이 정상 종료되었어요", "device_id", device.ID, "remote_addr", r.RemoteAddr)
				return
			}

			if errors.Is(err, context.Canceled) {
				s.logger.Info("WebSocket 연결을 취소했어요", "device_id", device.ID, "remote_addr", r.RemoteAddr)
				return
			}

			s.logger.Warn("WebSocket 메시지를 읽지 못해서 연결을 종료해요", "device_id", device.ID, "remote_addr", r.RemoteAddr, "error", err)
			return
		}

		switch message.Type {
		case "ping":
			if err := client.write(r.Context(), serverMessage{Type: "pong"}); err != nil {
				s.logger.Error("WebSocket pong 메시지를 보내지 못했어요", "device_id", device.ID, "error", err)
				return
			}
		case "send_envelope":
			if err := s.handleClientEnvelope(r.Context(), device, message); err != nil {
				s.logger.Warn(
					"envelope 전송 요청을 처리하지 못했어요",
					"device_id",
					device.ID,
					"recipient_device_id",
					message.RecipientDeviceID,
					"channel",
					message.Channel,
					"error",
					err,
				)
				client.write(r.Context(), serverMessage{
					Type:    "error",
					Code:    "send_failed",
					Message: err.Error(),
				})
			}
		case "ack_envelope":
			if err := s.relayService.AcknowledgeEnvelope(r.Context(), device.ID, message.EnvelopeID); err != nil {
				s.logger.Warn(
					"envelope 전달 확인 요청을 처리하지 못했어요",
					"device_id",
					device.ID,
					"envelope_id",
					message.EnvelopeID,
					"error",
					err,
				)
				client.write(r.Context(), serverMessage{
					Type:    "error",
					Code:    "ack_failed",
					Message: err.Error(),
				})
				continue
			}

			s.logger.Info("envelope 전달 확인 요청을 처리했어요", "device_id", device.ID, "envelope_id", message.EnvelopeID)
		default:
			s.logger.Warn("알 수 없는 WebSocket 메시지 타입을 받았어요", "device_id", device.ID, "message_type", message.Type)
			client.write(r.Context(), serverMessage{
				Type:    "error",
				Code:    "unknown_message_type",
				Message: "알 수 없는 WebSocket 메시지 타입이에요",
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

	s.logger.Info(
		"envelope 전송 요청을 처리했어요",
		"envelope_id",
		envelope.ID,
		"sender_device_id",
		envelope.SenderDeviceID,
		"recipient_device_id",
		envelope.RecipientDeviceID,
		"channel",
		envelope.Channel,
	)

	s.hub.sendToDevice(envelope.RecipientDeviceID, envelopeToServerMessage(envelope))

	return nil
}

func (s *Server) sendPendingEnvelopes(ctx context.Context, deviceID string, client *clientConnection) (int, error) {
	envelopes, err := s.relayService.PendingEnvelopes(ctx, deviceID)
	if err != nil {
		return 0, err
	}

	for _, envelope := range envelopes {
		if err := client.write(ctx, envelopeToServerMessage(envelope)); err != nil {
			return 0, err
		}
	}

	return len(envelopes), nil
}

func (s *Server) writeServiceError(w http.ResponseWriter, r *http.Request, err error) {
	logLevel := slog.LevelWarn
	statusCode := http.StatusInternalServerError
	errorCode := "internal_error"
	message := "내부 서버 오류가 발생했어요"

	switch {
	case errors.Is(err, service.ErrInvalidInput):
		statusCode = http.StatusBadRequest
		errorCode = "invalid_input"
		message = err.Error()
	case errors.Is(err, service.ErrUnauthorized):
		statusCode = http.StatusUnauthorized
		errorCode = "unauthorized"
		message = err.Error()
	case errors.Is(err, service.ErrNotFound):
		statusCode = http.StatusNotFound
		errorCode = "not_found"
		message = err.Error()
	case errors.Is(err, service.ErrConflict):
		statusCode = http.StatusConflict
		errorCode = "conflict"
		message = err.Error()
	case errors.Is(err, service.ErrExpired):
		statusCode = http.StatusGone
		errorCode = "expired"
		message = err.Error()
	default:
		logLevel = slog.LevelError
	}

	s.logger.Log(
		r.Context(),
		logLevel,
		"요청 처리 중 오류가 발생했어요",
		"method",
		r.Method,
		"path",
		r.URL.Path,
		"remote_addr",
		r.RemoteAddr,
		"error_code",
		errorCode,
		"status_code",
		statusCode,
		"error",
		err,
	)

	s.writeError(w, statusCode, errorCode, message)
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

func decodeJSON(w http.ResponseWriter, r *http.Request, destination any, maxBytes int64) error {
	body := r.Body
	if maxBytes > 0 {
		body = http.MaxBytesReader(w, r.Body, maxBytes)
	}
	defer body.Close()

	decoder := json.NewDecoder(body)
	decoder.DisallowUnknownFields()

	if err := decoder.Decode(destination); err != nil {
		var maxBytesError *http.MaxBytesError
		if errors.As(err, &maxBytesError) {
			return errors.New("요청 본문 크기가 서버 제한을 초과했어요")
		}

		return errors.New("요청 본문의 JSON 형식이 올바르지 않아요")
	}

	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		return errors.New("요청 본문에는 JSON 객체 하나만 담아야 해요")
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
		return nil, fmt.Errorf("%s 값은 비어 있으면 안 돼요", humanReadableFieldName(fieldName))
	}

	decoded, err := base64.RawStdEncoding.DecodeString(trimmed)
	if err != nil {
		return nil, fmt.Errorf("%s 값은 올바른 base64 형식이어야 해요", humanReadableFieldName(fieldName))
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
			h.logger.Error("WebSocket 메시지를 밀어 넣지 못했어요", "device_id", deviceID, "error", err)
		}
	}
}

func humanReadableFieldName(fieldName string) string {
	switch fieldName {
	case "public_key":
		return "공개키"
	case "pairing_secret":
		return "페어링 비밀값"
	case "content_type":
		return "콘텐츠 타입"
	case "nonce":
		return "nonce"
	case "header_aad":
		return "헤더 AAD"
	case "ciphertext":
		return "암호문"
	default:
		return fieldName
	}
}
