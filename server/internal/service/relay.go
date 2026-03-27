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
	logger     *slog.Logger
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

func NewRelayService(logger *slog.Logger, store *sqlite.Store, messageTTL time.Duration) *RelayService {
	if logger == nil {
		logger = slog.Default()
	}

	return &RelayService{
		logger:     logger,
		store:      store,
		messageTTL: messageTTL,
		now:        time.Now,
	}
}

func (s *RelayService) AuthenticateDevice(ctx context.Context, deviceID string, relayToken string) (domain.Device, error) {
	if strings.TrimSpace(deviceID) == "" || strings.TrimSpace(relayToken) == "" {
		return domain.Device{}, fmt.Errorf("%w: WebSocket 인증 정보가 비어 있어요", ErrInvalidInput)
	}

	device, err := s.store.GetDeviceByCredentials(ctx, deviceID, security.HashOpaqueToken(relayToken))
	if err != nil {
		if sqlite.IsNotFound(err) {
			return domain.Device{}, fmt.Errorf("%w: 기기 인증 정보가 올바르지 않아요", ErrUnauthorized)
		}

		return domain.Device{}, err
	}

	if device.PeerDeviceID == "" || device.PairingConfirmedAt == nil {
		return domain.Device{}, fmt.Errorf("%w: 기기 페어링이 아직 활성화되지 않았어요", ErrUnauthorized)
	}

	if touchErr := s.store.TouchDevice(ctx, device.ID, s.now().UTC()); touchErr != nil {
		return domain.Device{}, touchErr
	}

	s.logger.Info(
		"기기 인증을 완료했어요",
		"device_id",
		device.ID,
		"peer_device_id",
		device.PeerDeviceID,
		"platform",
		device.Platform,
	)

	return device, nil
}

func (s *RelayService) QueueEnvelope(ctx context.Context, input QueueEnvelopeInput) (domain.Envelope, error) {
	senderDeviceID := strings.TrimSpace(input.SenderDeviceID)
	recipientDeviceID := strings.TrimSpace(input.RecipientDeviceID)
	if senderDeviceID == "" || recipientDeviceID == "" {
		return domain.Envelope{}, fmt.Errorf("%w: 발신 기기와 수신 기기 ID는 모두 있어야 해요", ErrInvalidInput)
	}

	contentType, err := validateContentType(input.ContentType)
	if err != nil {
		return domain.Envelope{}, err
	}

	if err := validateOpaqueBytes(input.Nonce, "nonce", MaxNonceBytes); err != nil {
		return domain.Envelope{}, err
	}

	if err := validateOpaqueBytes(input.HeaderAAD, "헤더 AAD", MaxHeaderAADBytes); err != nil {
		return domain.Envelope{}, err
	}

	if err := validateOpaqueBytes(input.Ciphertext, "암호문", MaxCiphertextBytes); err != nil {
		return domain.Envelope{}, err
	}

	sender, err := s.store.GetDevice(ctx, senderDeviceID)
	if err != nil {
		if sqlite.IsNotFound(err) {
			return domain.Envelope{}, fmt.Errorf("%w: 발신 기기를 찾을 수 없어요", ErrNotFound)
		}

		return domain.Envelope{}, err
	}

	recipient, err := s.store.GetDevice(ctx, recipientDeviceID)
	if err != nil {
		if sqlite.IsNotFound(err) {
			return domain.Envelope{}, fmt.Errorf("%w: 수신 기기를 찾을 수 없어요", ErrNotFound)
		}

		return domain.Envelope{}, err
	}

	if sender.PeerDeviceID == "" || sender.PeerDeviceID != recipient.ID {
		return domain.Envelope{}, fmt.Errorf("%w: 수신 기기가 발신 기기와 페어링되어 있지 않아요", ErrUnauthorized)
	}

	if recipient.PeerDeviceID == "" || recipient.PeerDeviceID != sender.ID {
		return domain.Envelope{}, fmt.Errorf("%w: 발신 기기가 수신 기기와 페어링되어 있지 않아요", ErrUnauthorized)
	}

	if sender.PairingConfirmedAt == nil || recipient.PairingConfirmedAt == nil {
		return domain.Envelope{}, fmt.Errorf("%w: 페어링이 아직 활성화되지 않았어요", ErrUnauthorized)
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
		ContentType:       contentType,
		Nonce:             append([]byte(nil), input.Nonce...),
		HeaderAAD:         append([]byte(nil), input.HeaderAAD...),
		Ciphertext:        append([]byte(nil), input.Ciphertext...),
		CreatedAt:         now,
		ExpiresAt:         now.Add(s.messageTTL),
	}

	if err := s.store.CreateEnvelope(ctx, envelope); err != nil {
		return domain.Envelope{}, err
	}

	s.logger.Info(
		"암호화 envelope를 큐에 저장했어요",
		"envelope_id",
		envelope.ID,
		"sender_device_id",
		envelope.SenderDeviceID,
		"recipient_device_id",
		envelope.RecipientDeviceID,
		"channel",
		envelope.Channel,
		"content_type",
		envelope.ContentType,
		"expires_at",
		envelope.ExpiresAt.Format(time.RFC3339),
	)

	return envelope, nil
}

func (s *RelayService) PendingEnvelopes(ctx context.Context, recipientDeviceID string) ([]domain.Envelope, error) {
	return s.store.ListPendingEnvelopes(ctx, recipientDeviceID, s.now().UTC(), 256)
}

func (s *RelayService) AcknowledgeEnvelope(ctx context.Context, recipientDeviceID string, envelopeID string) error {
	if strings.TrimSpace(envelopeID) == "" {
		return fmt.Errorf("%w: envelope_id 값은 비어 있으면 안 돼요", ErrInvalidInput)
	}

	if err := s.store.MarkEnvelopeDelivered(ctx, envelopeID, recipientDeviceID, s.now().UTC()); err != nil {
		if sqlite.IsNotFound(err) {
			return fmt.Errorf("%w: envelope를 찾을 수 없어요", ErrNotFound)
		}

		return err
	}

	s.logger.Info(
		"envelope 전달 확인을 반영했어요",
		"envelope_id",
		envelopeID,
		"recipient_device_id",
		recipientDeviceID,
	)

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
				logger.Error("만료된 중계 데이터를 정리하지 못했어요", "error", err)
				continue
			}

			if result.DeletedPairingSessions == 0 && result.DeletedEnvelopes == 0 {
				continue
			}

			logger.Info(
				"만료된 중계 데이터를 정리했어요",
				"deleted_pairing_sessions",
				result.DeletedPairingSessions,
				"deleted_envelopes",
				result.DeletedEnvelopes,
				"executed_at",
				s.now().UTC().Format(time.RFC3339),
				"cleanup_interval",
				interval.String(),
			)
		}
	}
}
