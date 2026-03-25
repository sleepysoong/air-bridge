package service

import (
	"context"
	"crypto/subtle"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/sleepysoong/air-bridge/server/internal/domain"
	"github.com/sleepysoong/air-bridge/server/internal/persistence/sqlite"
	"github.com/sleepysoong/air-bridge/server/internal/security"
)

type PairingService struct {
	logger     *slog.Logger
	store      *sqlite.Store
	pairingTTL time.Duration
	now        func() time.Time
}

type CreatePairingSessionInput struct {
	InitiatorDeviceName string
	InitiatorPlatform   domain.Platform
	InitiatorPublicKey  []byte
}

type CreatePairingSessionResult struct {
	Session             domain.PairingSession
	InitiatorDevice     domain.Device
	PairingSecret       string
	InitiatorRelayToken string
}

type JoinPairingSessionInput struct {
	SessionID        string
	PairingSecret    string
	JoinerDeviceName string
	JoinerPlatform   domain.Platform
	JoinerPublicKey  []byte
}

type JoinPairingSessionResult struct {
	Session            domain.PairingSession
	InitiatorDeviceID  string
	JoinerDevice       domain.Device
	JoinerRelayToken   string
	InitiatorPublicKey []byte
}

func NewPairingService(logger *slog.Logger, store *sqlite.Store, pairingTTL time.Duration) *PairingService {
	if logger == nil {
		logger = slog.Default()
	}

	return &PairingService{
		logger:     logger,
		store:      store,
		pairingTTL: pairingTTL,
		now:        time.Now,
	}
}

func (s *PairingService) CreateSession(ctx context.Context, input CreatePairingSessionInput) (CreatePairingSessionResult, error) {
	deviceName, err := validateDeviceName(input.InitiatorDeviceName, "시작 기기")
	if err != nil {
		return CreatePairingSessionResult{}, err
	}

	if err := validatePublicKey(input.InitiatorPublicKey, "시작 기기 공개키"); err != nil {
		return CreatePairingSessionResult{}, err
	}

	now := s.now().UTC()
	deviceID, err := security.NewID("dev")
	if err != nil {
		return CreatePairingSessionResult{}, err
	}

	sessionID, err := security.NewID("ps")
	if err != nil {
		return CreatePairingSessionResult{}, err
	}

	pairingSecret, err := security.NewOpaqueToken("prs", 18)
	if err != nil {
		return CreatePairingSessionResult{}, err
	}

	relayToken, err := security.NewOpaqueToken("rt", 24)
	if err != nil {
		return CreatePairingSessionResult{}, err
	}

	device := domain.Device{
		ID:             deviceID,
		Name:           deviceName,
		Platform:       input.InitiatorPlatform,
		RelayTokenHash: security.HashOpaqueToken(relayToken),
		CreatedAt:      now,
		LastSeenAt:     now,
	}

	session := domain.PairingSession{
		ID:                 sessionID,
		InitiatorDeviceID:  deviceID,
		InitiatorName:      device.Name,
		InitiatorPlatform:  input.InitiatorPlatform,
		InitiatorPublicKey: append([]byte(nil), input.InitiatorPublicKey...),
		PairingSecretHash:  security.HashOpaqueToken(pairingSecret),
		State:              domain.PairingStatePending,
		ExpiresAt:          now.Add(s.pairingTTL),
		CreatedAt:          now,
		UpdatedAt:          now,
	}

	if err := s.store.CreatePairingSession(ctx, device, session); err != nil {
		return CreatePairingSessionResult{}, err
	}

	s.logger.Info(
		"페어링 세션을 생성했어요",
		"session_id",
		session.ID,
		"initiator_device_id",
		device.ID,
		"platform",
		device.Platform,
		"expires_at",
		session.ExpiresAt.Format(time.RFC3339),
	)

	return CreatePairingSessionResult{
		Session:             session,
		InitiatorDevice:     device,
		PairingSecret:       pairingSecret,
		InitiatorRelayToken: relayToken,
	}, nil
}

func (s *PairingService) GetSession(ctx context.Context, sessionID string, pairingSecret string) (domain.PairingSession, error) {
	sessionID = strings.TrimSpace(sessionID)
	if sessionID == "" {
		return domain.PairingSession{}, fmt.Errorf("%w: 페어링 세션 ID는 비어 있으면 안 돼요", ErrInvalidInput)
	}

	validatedSecret, err := validatePairingSecret(pairingSecret)
	if err != nil {
		return domain.PairingSession{}, err
	}

	session, err := s.store.GetPairingSession(ctx, sessionID)
	if err != nil {
		if sqlite.IsNotFound(err) {
			return domain.PairingSession{}, fmt.Errorf("%w: 페어링 세션을 찾을 수 없어요", ErrNotFound)
		}

		return domain.PairingSession{}, err
	}

	if subtle.ConstantTimeCompare(session.PairingSecretHash, security.HashOpaqueToken(validatedSecret)) != 1 {
		return domain.PairingSession{}, fmt.Errorf("%w: 페어링 비밀값이 일치하지 않아요", ErrUnauthorized)
	}

	if s.now().UTC().After(session.ExpiresAt) {
		return domain.PairingSession{}, fmt.Errorf("%w: 페어링 세션이 만료되었어요", ErrExpired)
	}

	return session, nil
}

func (s *PairingService) JoinSession(ctx context.Context, input JoinPairingSessionInput) (JoinPairingSessionResult, error) {
	deviceName, err := validateDeviceName(input.JoinerDeviceName, "참여 기기")
	if err != nil {
		return JoinPairingSessionResult{}, err
	}

	if err := validatePublicKey(input.JoinerPublicKey, "참여 기기 공개키"); err != nil {
		return JoinPairingSessionResult{}, err
	}

	session, err := s.GetSession(ctx, input.SessionID, input.PairingSecret)
	if err != nil {
		return JoinPairingSessionResult{}, err
	}

	if session.State != domain.PairingStatePending {
		return JoinPairingSessionResult{}, fmt.Errorf("%w: 페어링 세션에 이미 다른 기기가 참여했어요", ErrConflict)
	}

	now := s.now().UTC()
	joinerDeviceID, err := security.NewID("dev")
	if err != nil {
		return JoinPairingSessionResult{}, err
	}

	joinerRelayToken, err := security.NewOpaqueToken("rt", 24)
	if err != nil {
		return JoinPairingSessionResult{}, err
	}

	joinerDevice := domain.Device{
		ID:             joinerDeviceID,
		Name:           deviceName,
		Platform:       input.JoinerPlatform,
		PeerDeviceID:   session.InitiatorDeviceID,
		RelayTokenHash: security.HashOpaqueToken(joinerRelayToken),
		CreatedAt:      now,
		LastSeenAt:     now,
	}

	session.JoinerDeviceID = joinerDevice.ID
	session.JoinerName = joinerDevice.Name
	session.JoinerPlatform = input.JoinerPlatform
	session.JoinerPublicKey = append([]byte(nil), input.JoinerPublicKey...)
	session.UpdatedAt = now

	if err := s.store.JoinPairingSession(ctx, session, joinerDevice, now); err != nil {
		if sqlite.IsNotFound(err) {
			return JoinPairingSessionResult{}, fmt.Errorf("%w: 이 페어링 세션에는 참여할 수 없어요", ErrConflict)
		}

		return JoinPairingSessionResult{}, err
	}

	session.State = domain.PairingStateReady

	s.logger.Info(
		"페어링 세션에 참여했어요",
		"session_id",
		session.ID,
		"initiator_device_id",
		session.InitiatorDeviceID,
		"joiner_device_id",
		joinerDevice.ID,
		"platform",
		joinerDevice.Platform,
	)

	return JoinPairingSessionResult{
		Session:            session,
		InitiatorDeviceID:  session.InitiatorDeviceID,
		JoinerDevice:       joinerDevice,
		JoinerRelayToken:   joinerRelayToken,
		InitiatorPublicKey: append([]byte(nil), session.InitiatorPublicKey...),
	}, nil
}

func (s *PairingService) CompleteSession(ctx context.Context, sessionID string, pairingSecret string) (domain.PairingSession, error) {
	session, err := s.GetSession(ctx, sessionID, pairingSecret)
	if err != nil {
		return domain.PairingSession{}, err
	}

	switch session.State {
	case domain.PairingStatePending:
		return domain.PairingSession{}, fmt.Errorf("%w: 참여 기기가 아직 연결되지 않았어요", ErrConflict)
	case domain.PairingStateCompleted:
		return domain.PairingSession{}, fmt.Errorf("%w: 페어링 세션이 이미 완료되었어요", ErrConflict)
	}

	now := s.now().UTC()
	if err := s.store.CompletePairingSession(ctx, session, now); err != nil {
		if sqlite.IsNotFound(err) {
			return domain.PairingSession{}, fmt.Errorf("%w: 페어링 세션을 찾을 수 없어요", ErrNotFound)
		}

		return domain.PairingSession{}, err
	}

	session.State = domain.PairingStateCompleted
	session.UpdatedAt = now
	session.CompletedAt = &now

	s.logger.Info(
		"페어링을 완료했어요",
		"session_id",
		session.ID,
		"initiator_device_id",
		session.InitiatorDeviceID,
		"joiner_device_id",
		session.JoinerDeviceID,
		"completed_at",
		now.Format(time.RFC3339),
	)

	return session, nil
}

func validatePublicKey(publicKey []byte, label string) error {
	if len(publicKey) == 0 {
		return fmt.Errorf("%w: %s 값은 비어 있으면 안 돼요", ErrInvalidInput, label)
	}

	if len(publicKey) != domain.X25519PublicKeyLength {
		return fmt.Errorf(
			"%w: %s 값은 %d바이트여야 해요",
			ErrInvalidInput,
			label,
			domain.X25519PublicKeyLength,
		)
	}

	return nil
}
