package service

import (
	"context"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/sleepysoong/air-bridge/server/internal/domain"
	"github.com/sleepysoong/air-bridge/server/internal/persistence/sqlite"
	"github.com/sleepysoong/air-bridge/server/internal/security"
)

type RelayService struct {
	store      *sqlite.Store
	messageTTL time.Duration
	now        func() time.Time
}

type QueueEnvelopeInput struct {
	SenderDeviceID    string
	RecipientDeviceID string
	Channel           domain.Channel
	ContentType       string
	Nonce             []byte
	HeaderAAD         []byte
	Ciphertext        []byte
}

func NewRelayService(store *sqlite.Store, messageTTL time.Duration) *RelayService {
	return &RelayService{
		store:      store,
		messageTTL: messageTTL,
		now:        time.Now,
	}
}

func (s *RelayService) AuthenticateDevice(ctx context.Context, deviceID string, relayToken string) (domain.Device, error) {
	if strings.TrimSpace(deviceID) == "" || strings.TrimSpace(relayToken) == "" {
		return domain.Device{}, fmt.Errorf("%w: missing websocket credentials", ErrInvalidInput)
	}

	device, err := s.store.GetDeviceByCredentials(ctx, deviceID, security.HashOpaqueToken(relayToken))
	if err != nil {
		if sqlite.IsNotFound(err) {
			return domain.Device{}, fmt.Errorf("%w: invalid device credentials", ErrUnauthorized)
		}

		return domain.Device{}, err
	}

	if device.PeerDeviceID == "" || device.PairingConfirmedAt == nil {
		return domain.Device{}, fmt.Errorf("%w: device pairing is not confirmed", ErrUnauthorized)
	}

	if touchErr := s.store.TouchDevice(ctx, device.ID, s.now().UTC()); touchErr != nil {
		return domain.Device{}, touchErr
	}

	return device, nil
}

func (s *RelayService) QueueEnvelope(ctx context.Context, input QueueEnvelopeInput) (domain.Envelope, error) {
	if strings.TrimSpace(input.ContentType) == "" {
		return domain.Envelope{}, fmt.Errorf("%w: content type is required", ErrInvalidInput)
	}

	if len(input.Nonce) == 0 || len(input.HeaderAAD) == 0 || len(input.Ciphertext) == 0 {
		return domain.Envelope{}, fmt.Errorf("%w: nonce, aad, and ciphertext are required", ErrInvalidInput)
	}

	sender, err := s.store.GetDevice(ctx, input.SenderDeviceID)
	if err != nil {
		if sqlite.IsNotFound(err) {
			return domain.Envelope{}, fmt.Errorf("%w: sender device", ErrNotFound)
		}

		return domain.Envelope{}, err
	}

	recipient, err := s.store.GetDevice(ctx, input.RecipientDeviceID)
	if err != nil {
		if sqlite.IsNotFound(err) {
			return domain.Envelope{}, fmt.Errorf("%w: recipient device", ErrNotFound)
		}

		return domain.Envelope{}, err
	}

	if sender.PeerDeviceID == "" || sender.PeerDeviceID != recipient.ID {
		return domain.Envelope{}, fmt.Errorf("%w: recipient is not paired with sender", ErrUnauthorized)
	}

	if recipient.PeerDeviceID == "" || recipient.PeerDeviceID != sender.ID {
		return domain.Envelope{}, fmt.Errorf("%w: sender is not paired with recipient", ErrUnauthorized)
	}

	if sender.PairingConfirmedAt == nil || recipient.PairingConfirmedAt == nil {
		return domain.Envelope{}, fmt.Errorf("%w: pairing is not confirmed", ErrUnauthorized)
	}

	now := s.now().UTC()
	envelopeID, err := security.NewID("env")
	if err != nil {
		return domain.Envelope{}, err
	}

	envelope := domain.Envelope{
		ID:                envelopeID,
		SenderDeviceID:    sender.ID,
		RecipientDeviceID: recipient.ID,
		Channel:           input.Channel,
		ContentType:       strings.TrimSpace(input.ContentType),
		Nonce:             append([]byte(nil), input.Nonce...),
		HeaderAAD:         append([]byte(nil), input.HeaderAAD...),
		Ciphertext:        append([]byte(nil), input.Ciphertext...),
		CreatedAt:         now,
		ExpiresAt:         now.Add(s.messageTTL),
	}

	if err := s.store.CreateEnvelope(ctx, envelope); err != nil {
		return domain.Envelope{}, err
	}

	return envelope, nil
}

func (s *RelayService) PendingEnvelopes(ctx context.Context, recipientDeviceID string) ([]domain.Envelope, error) {
	return s.store.ListPendingEnvelopes(ctx, recipientDeviceID, s.now().UTC(), 256)
}

func (s *RelayService) AcknowledgeEnvelope(ctx context.Context, recipientDeviceID string, envelopeID string) error {
	if strings.TrimSpace(envelopeID) == "" {
		return fmt.Errorf("%w: envelope id is required", ErrInvalidInput)
	}

	if err := s.store.MarkEnvelopeDelivered(ctx, envelopeID, recipientDeviceID, s.now().UTC()); err != nil {
		if sqlite.IsNotFound(err) {
			return fmt.Errorf("%w: envelope", ErrNotFound)
		}

		return err
	}

	return nil
}

func (s *RelayService) RunCleanupLoop(ctx context.Context, logger *slog.Logger, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			result, err := s.store.DeleteExpired(ctx, s.now().UTC())
			if err != nil {
				logger.Error("cleanup expired relay records", "error", err)
				continue
			}

			if result.DeletedPairingSessions == 0 && result.DeletedEnvelopes == 0 {
				continue
			}

			logger.Info(
				"cleanup expired relay records",
				"deleted_pairing_sessions",
				result.DeletedPairingSessions,
				"deleted_envelopes",
				result.DeletedEnvelopes,
			)
		}
	}
}
