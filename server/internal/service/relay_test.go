package service

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/sleepysoong/air-bridge/server/internal/domain"
)

func TestRelayServiceAuthenticateDevice(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	baseTime := time.UnixMilli(1_700_000_500_000).UTC()
	pairingService := NewPairingService(store, 10*time.Minute)
	createResult, _, _ := createCompletedPair(t, ctx, pairingService, baseTime)

	relayService := NewRelayService(store, 24*time.Hour)
	relayService.now = func() time.Time { return baseTime.Add(5 * time.Minute) }

	device, err := relayService.AuthenticateDevice(ctx, createResult.InitiatorDevice.ID, createResult.InitiatorRelayToken)
	if err != nil {
		t.Fatalf("authenticate device: %v", err)
	}

	if device.ID != createResult.InitiatorDevice.ID {
		t.Fatalf("expected device ID %q, got %q", createResult.InitiatorDevice.ID, device.ID)
	}

	storedDevice, err := store.GetDevice(ctx, createResult.InitiatorDevice.ID)
	if err != nil {
		t.Fatalf("get device: %v", err)
	}

	if !storedDevice.LastSeenAt.Equal(baseTime.Add(5 * time.Minute)) {
		t.Fatalf("expected last seen at %v, got %v", baseTime.Add(5*time.Minute), storedDevice.LastSeenAt)
	}
}

func TestRelayServiceAuthenticateDeviceRejectsUnconfirmedPairing(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	baseTime := time.UnixMilli(1_700_000_550_000).UTC()
	pairingService := NewPairingService(store, 10*time.Minute)
	createResult, _ := createReadyPair(t, ctx, pairingService, baseTime)

	relayService := NewRelayService(store, 24*time.Hour)

	_, err := relayService.AuthenticateDevice(ctx, createResult.InitiatorDevice.ID, createResult.InitiatorRelayToken)
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("expected unauthorized error for unconfirmed pairing, got %v", err)
	}
}

func TestRelayServiceAuthenticateDeviceRejectsWrongToken(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	baseTime := time.UnixMilli(1_700_000_600_000).UTC()
	pairingService := NewPairingService(store, 10*time.Minute)
	createResult, _, _ := createCompletedPair(t, ctx, pairingService, baseTime)

	relayService := NewRelayService(store, 24*time.Hour)

	_, err := relayService.AuthenticateDevice(ctx, createResult.InitiatorDevice.ID, "rt_wrong")
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("expected unauthorized error, got %v", err)
	}
}

func TestRelayServiceQueueAndAcknowledgeEnvelope(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	baseTime := time.UnixMilli(1_700_000_700_000).UTC()
	pairingService := NewPairingService(store, 10*time.Minute)
	createResult, joinResult, _ := createCompletedPair(t, ctx, pairingService, baseTime)

	relayService := NewRelayService(store, 24*time.Hour)
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
		t.Fatalf("queue envelope: %v", err)
	}

	if envelope.Channel != domain.ChannelClipboard {
		t.Fatalf("expected clipboard channel, got %q", envelope.Channel)
	}

	pendingEnvelopes, err := relayService.PendingEnvelopes(ctx, joinResult.JoinerDevice.ID)
	if err != nil {
		t.Fatalf("list pending envelopes: %v", err)
	}

	if len(pendingEnvelopes) != 1 {
		t.Fatalf("expected 1 pending envelope, got %d", len(pendingEnvelopes))
	}

	if err := relayService.AcknowledgeEnvelope(ctx, joinResult.JoinerDevice.ID, envelope.ID); err != nil {
		t.Fatalf("acknowledge envelope: %v", err)
	}

	pendingEnvelopes, err = relayService.PendingEnvelopes(ctx, joinResult.JoinerDevice.ID)
	if err != nil {
		t.Fatalf("list pending envelopes after ack: %v", err)
	}

	if len(pendingEnvelopes) != 0 {
		t.Fatalf("expected 0 pending envelopes after ack, got %d", len(pendingEnvelopes))
	}
}

func TestRelayServiceQueueEnvelopeRejectsUnpairedRecipient(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	baseTime := time.UnixMilli(1_700_000_800_000).UTC()
	pairingService := NewPairingService(store, 10*time.Minute)
	createResult, _, _ := createCompletedPair(t, ctx, pairingService, baseTime)

	unpairedCreateResult, err := pairingService.CreateSession(ctx, CreatePairingSessionInput{
		InitiatorDeviceName: "other-mac",
		InitiatorPlatform:   domain.PlatformMacOS,
		InitiatorPublicKey:  x25519PublicKey(0x44),
	})
	if err != nil {
		t.Fatalf("create unpaired session: %v", err)
	}

	relayService := NewRelayService(store, 24*time.Hour)

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
		t.Fatalf("expected unauthorized error, got %v", err)
	}
}

func TestRelayServiceAcknowledgeEnvelopeRejectsWrongRecipient(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	baseTime := time.UnixMilli(1_700_000_900_000).UTC()
	pairingService := NewPairingService(store, 10*time.Minute)
	createResult, joinResult, _ := createCompletedPair(t, ctx, pairingService, baseTime)

	relayService := NewRelayService(store, 24*time.Hour)
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
		t.Fatalf("queue envelope: %v", err)
	}

	err = relayService.AcknowledgeEnvelope(ctx, createResult.InitiatorDevice.ID, envelope.ID)
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("expected not found error, got %v", err)
	}
}
