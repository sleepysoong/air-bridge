package service

import (
	"bytes"
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/sleepysoong/air-bridge/server/internal/domain"
)

func TestRelayServiceAuthenticateDevice(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	baseTime := time.UnixMilli(1_700_000_500_000).UTC()
	pairingService := NewPairingService(newDiscardLogger(), store, 10*time.Minute)
	createResult, _, _ := createCompletedPair(t, ctx, pairingService, baseTime)

	relayService := NewRelayService(newDiscardLogger(), store, 24*time.Hour)
	relayService.now = func() time.Time { return baseTime.Add(5 * time.Minute) }

	device, err := relayService.AuthenticateDevice(ctx, createResult.InitiatorDevice.ID, createResult.InitiatorRelayToken)
	if err != nil {
		t.Fatalf("기기를 인증하지 못했어요: %v", err)
	}

	if device.ID != createResult.InitiatorDevice.ID {
		t.Fatalf("기기 ID는 %q 이어야 해요. 실제 값: %q", createResult.InitiatorDevice.ID, device.ID)
	}

	storedDevice, err := store.GetDevice(ctx, createResult.InitiatorDevice.ID)
	if err != nil {
		t.Fatalf("기기를 조회하지 못했어요: %v", err)
	}

	if !storedDevice.LastSeenAt.Equal(baseTime.Add(5 * time.Minute)) {
		t.Fatalf("last_seen_at 값은 %v 이어야 해요. 실제 값: %v", baseTime.Add(5*time.Minute), storedDevice.LastSeenAt)
	}
}

func TestRelayServiceAuthenticateDeviceRejectsUnconfirmedPairing(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	baseTime := time.UnixMilli(1_700_000_550_000).UTC()
	pairingService := NewPairingService(newDiscardLogger(), store, 10*time.Minute)
	createResult, _ := createReadyPair(t, ctx, pairingService, baseTime)

	relayService := NewRelayService(newDiscardLogger(), store, 24*time.Hour)

	_, err := relayService.AuthenticateDevice(ctx, createResult.InitiatorDevice.ID, createResult.InitiatorRelayToken)
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("미확정 페어링에는 권한 오류가 반환되어야 해요. 실제 값: %v", err)
	}
}

func TestRelayServiceAuthenticateDeviceRejectsWrongToken(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	baseTime := time.UnixMilli(1_700_000_600_000).UTC()
	pairingService := NewPairingService(newDiscardLogger(), store, 10*time.Minute)
	createResult, _, _ := createCompletedPair(t, ctx, pairingService, baseTime)

	relayService := NewRelayService(newDiscardLogger(), store, 24*time.Hour)

	_, err := relayService.AuthenticateDevice(ctx, createResult.InitiatorDevice.ID, "rt_wrong")
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("권한 오류가 반환되어야 해요. 실제 값: %v", err)
	}
}

func TestRelayServiceQueueAndAcknowledgeEnvelope(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	baseTime := time.UnixMilli(1_700_000_700_000).UTC()
	pairingService := NewPairingService(newDiscardLogger(), store, 10*time.Minute)
	createResult, joinResult, _ := createCompletedPair(t, ctx, pairingService, baseTime)

	relayService := NewRelayService(newDiscardLogger(), store, 24*time.Hour)
	relayService.now = func() time.Time { return baseTime.Add(2 * time.Minute) }

	envelope, err := relayService.QueueEnvelope(ctx, QueueEnvelopeInput{
		SenderDeviceID:    createResult.InitiatorDevice.ID,
		RecipientDeviceID: joinResult.JoinerDevice.ID,
		Channel:           domain.ChannelClipboard,
		ContentType:       "application/json",
		Nonce:             []byte("nonce"),
		HeaderAAD:         []byte("aad"),
		Ciphertext:        []byte("ciphertext"),
	})
	if err != nil {
		t.Fatalf("envelope를 큐에 저장하지 못했어요: %v", err)
	}

	if envelope.Channel != domain.ChannelClipboard {
		t.Fatalf("채널은 clipboard 여야 해요. 실제 값: %q", envelope.Channel)
	}

	pendingEnvelopes, err := relayService.PendingEnvelopes(ctx, joinResult.JoinerDevice.ID)
	if err != nil {
		t.Fatalf("대기 중인 envelope를 조회하지 못했어요: %v", err)
	}

	if len(pendingEnvelopes) != 1 {
		t.Fatalf("대기 중인 envelope 개수는 1이어야 해요. 실제 값: %d", len(pendingEnvelopes))
	}

	if err := relayService.AcknowledgeEnvelope(ctx, joinResult.JoinerDevice.ID, envelope.ID); err != nil {
		t.Fatalf("envelope 전달 확인을 반영하지 못했어요: %v", err)
	}

	pendingEnvelopes, err = relayService.PendingEnvelopes(ctx, joinResult.JoinerDevice.ID)
	if err != nil {
		t.Fatalf("전달 확인 뒤 대기 중인 envelope를 조회하지 못했어요: %v", err)
	}

	if len(pendingEnvelopes) != 0 {
		t.Fatalf("전달 확인 뒤 대기 중인 envelope 개수는 0이어야 해요. 실제 값: %d", len(pendingEnvelopes))
	}
}

func TestRelayServiceQueueEnvelopeRejectsUnpairedRecipient(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	baseTime := time.UnixMilli(1_700_000_800_000).UTC()
	pairingService := NewPairingService(newDiscardLogger(), store, 10*time.Minute)
	createResult, _, _ := createCompletedPair(t, ctx, pairingService, baseTime)

	unpairedCreateResult, err := pairingService.CreateSession(ctx, CreatePairingSessionInput{
		InitiatorDeviceName: "other-mac",
		InitiatorPlatform:   domain.PlatformMacOS,
		InitiatorPublicKey:  x25519PublicKey(0x44),
	})
	if err != nil {
		t.Fatalf("비연결 세션을 생성하지 못했어요: %v", err)
	}

	relayService := NewRelayService(newDiscardLogger(), store, 24*time.Hour)

	_, err = relayService.QueueEnvelope(ctx, QueueEnvelopeInput{
		SenderDeviceID:    createResult.InitiatorDevice.ID,
		RecipientDeviceID: unpairedCreateResult.InitiatorDevice.ID,
		Channel:           domain.ChannelNotification,
		ContentType:       "application/json",
		Nonce:             []byte("nonce"),
		HeaderAAD:         []byte("aad"),
		Ciphertext:        []byte("ciphertext"),
	})
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("권한 오류가 반환되어야 해요. 실제 값: %v", err)
	}
}

func TestRelayServiceAcknowledgeEnvelopeRejectsWrongRecipient(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	baseTime := time.UnixMilli(1_700_000_900_000).UTC()
	pairingService := NewPairingService(newDiscardLogger(), store, 10*time.Minute)
	createResult, joinResult, _ := createCompletedPair(t, ctx, pairingService, baseTime)

	relayService := NewRelayService(newDiscardLogger(), store, 24*time.Hour)
	relayService.now = func() time.Time { return baseTime.Add(1 * time.Minute) }

	envelope, err := relayService.QueueEnvelope(ctx, QueueEnvelopeInput{
		SenderDeviceID:    createResult.InitiatorDevice.ID,
		RecipientDeviceID: joinResult.JoinerDevice.ID,
		Channel:           domain.ChannelNotification,
		ContentType:       "application/json",
		Nonce:             []byte("nonce"),
		HeaderAAD:         []byte("aad"),
		Ciphertext:        []byte("ciphertext"),
	})
	if err != nil {
		t.Fatalf("envelope를 큐에 저장하지 못했어요: %v", err)
	}

	err = relayService.AcknowledgeEnvelope(ctx, createResult.InitiatorDevice.ID, envelope.ID)
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("찾을 수 없음 오류가 반환되어야 해요. 실제 값: %v", err)
	}
}

func TestRelayServiceQueueEnvelopeRejectsOversizedCiphertext(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	baseTime := time.UnixMilli(1_700_001_000_000).UTC()
	pairingService := NewPairingService(newDiscardLogger(), store, 10*time.Minute)
	createResult, joinResult, _ := createCompletedPair(t, ctx, pairingService, baseTime)

	relayService := NewRelayService(newDiscardLogger(), store, 24*time.Hour)

	_, err := relayService.QueueEnvelope(ctx, QueueEnvelopeInput{
		SenderDeviceID:    createResult.InitiatorDevice.ID,
		RecipientDeviceID: joinResult.JoinerDevice.ID,
		Channel:           domain.ChannelClipboard,
		ContentType:       "application/json",
		Nonce:             []byte("123456789012"),
		HeaderAAD:         []byte("aad"),
		Ciphertext:        bytes.Repeat([]byte("a"), MaxCiphertextBytes+1),
	})
	if !errors.Is(err, ErrInvalidInput) {
		t.Fatalf("입력 오류가 반환되어야 해요. 실제 값: %v", err)
	}
}

func TestRelayServiceQueueEnvelopeRejectsTooLongContentType(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	baseTime := time.UnixMilli(1_700_001_100_000).UTC()
	pairingService := NewPairingService(newDiscardLogger(), store, 10*time.Minute)
	createResult, joinResult, _ := createCompletedPair(t, ctx, pairingService, baseTime)

	relayService := NewRelayService(newDiscardLogger(), store, 24*time.Hour)

	_, err := relayService.QueueEnvelope(ctx, QueueEnvelopeInput{
		SenderDeviceID:    createResult.InitiatorDevice.ID,
		RecipientDeviceID: joinResult.JoinerDevice.ID,
		Channel:           domain.ChannelClipboard,
		ContentType:       strings.Repeat("a", MaxContentTypeBytes+1),
		Nonce:             []byte("123456789012"),
		HeaderAAD:         []byte("aad"),
		Ciphertext:        []byte("ciphertext"),
	})
	if !errors.Is(err, ErrInvalidInput) {
		t.Fatalf("입력 오류가 반환되어야 해요. 실제 값: %v", err)
	}
}
