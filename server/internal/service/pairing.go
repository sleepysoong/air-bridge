package service

import (
	"context"
	"crypto/subtle"
	"fmt"
	"strings"
	"time"

	"github.com/sleepysoong/air-bridge/server/internal/domain"
	"github.com/sleepysoong/air-bridge/server/internal/persistence/sqlite"
	"github.com/sleepysoong/air-bridge/server/internal/security"
)

type PairingService struct {
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

func NewPairingService(store *sqlite.Store, pairingTTL time.Duration) *PairingService {
	return &PairingService{
		store:      store,
		pairingTTL: pairingTTL,
		now:        time.Now,
	}
}

func (s *PairingService) CreateSession(ctx context.Context, input CreatePairingSessionInput) (CreatePairingSessionResult, error) {
	if strings.TrimSpace(input.InitiatorDeviceName) == "" {
		return CreatePairingSessionResult{}, fmt.Errorf("%w: initiator device name is required", ErrInvalidInput)
	}

	if err := validatePublicKey(input.InitiatorPublicKey, "initiator public key"); err != nil {
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
		Name:           strings.TrimSpace(input.InitiatorDeviceName),
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

	return CreatePairingSessionResult{
		Session:             session,
		InitiatorDevice:     device,
		PairingSecret:       pairingSecret,
		InitiatorRelayToken: relayToken,
	}, nil
}

func (s *PairingService) GetSession(ctx context.Context, sessionID string, pairingSecret string) (domain.PairingSession, error) {
	session, err := s.store.GetPairingSession(ctx, sessionID)
	if err != nil {
		if sqlite.IsNotFound(err) {
			return domain.PairingSession{}, fmt.Errorf("%w: pairing session", ErrNotFound)
		}

		return domain.PairingSession{}, err
	}

	if subtle.ConstantTimeCompare(session.PairingSecretHash, security.HashOpaqueToken(pairingSecret)) != 1 {
		return domain.PairingSession{}, fmt.Errorf("%w: pairing secret mismatch", ErrUnauthorized)
	}

	if s.now().UTC().After(session.ExpiresAt) {
		return domain.PairingSession{}, fmt.Errorf("%w: pairing session expired", ErrExpired)
	}

	return session, nil
}

func (s *PairingService) JoinSession(ctx context.Context, input JoinPairingSessionInput) (JoinPairingSessionResult, error) {
	if strings.TrimSpace(input.JoinerDeviceName) == "" {
		return JoinPairingSessionResult{}, fmt.Errorf("%w: joiner device name is required", ErrInvalidInput)
	}

	if err := validatePublicKey(input.JoinerPublicKey, "joiner public key"); err != nil {
		return JoinPairingSessionResult{}, err
	}

	session, err := s.GetSession(ctx, input.SessionID, input.PairingSecret)
	if err != nil {
		return JoinPairingSessionResult{}, err
	}

	if session.State != domain.PairingStatePending {
		return JoinPairingSessionResult{}, fmt.Errorf("%w: pairing session is already joined", ErrConflict)
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
		Name:           strings.TrimSpace(input.JoinerDeviceName),
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
			return JoinPairingSessionResult{}, fmt.Errorf("%w: pairing session not joinable", ErrConflict)
		}

		return JoinPairingSessionResult{}, err
	}

	session.State = domain.PairingStateReady

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
		return domain.PairingSession{}, fmt.Errorf("%w: joiner has not connected yet", ErrConflict)
	case domain.PairingStateCompleted:
		return domain.PairingSession{}, fmt.Errorf("%w: pairing session is already completed", ErrConflict)
	}

	now := s.now().UTC()
	if err := s.store.CompletePairingSession(ctx, session, now); err != nil {
		if sqlite.IsNotFound(err) {
			return domain.PairingSession{}, fmt.Errorf("%w: pairing session", ErrNotFound)
		}

		return domain.PairingSession{}, err
	}

	session.State = domain.PairingStateCompleted
	session.UpdatedAt = now
	session.CompletedAt = &now

	return session, nil
}

func validatePublicKey(publicKey []byte, label string) error {
	if len(publicKey) == 0 {
		return fmt.Errorf("%w: %s is required", ErrInvalidInput, label)
	}

	if len(publicKey) != domain.X25519PublicKeyLength {
		return fmt.Errorf(
			"%w: %s must be %d bytes",
			ErrInvalidInput,
			label,
			domain.X25519PublicKeyLength,
		)
	}

	return nil
}
