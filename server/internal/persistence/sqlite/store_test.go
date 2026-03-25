package sqlite

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"github.com/sleepysoong/air-bridge/server/internal/domain"
	"github.com/sleepysoong/air-bridge/server/internal/security"
)

func TestStoreDeleteExpiredRemovesExpiredSessionsAndDeliveredOrExpiredEnvelopes(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openSQLiteTestStore(t)

	baseTime := time.UnixMilli(1_700_001_000_000).UTC()
	expiredDevice := domain.Device{
		ID:             "dev_expired",
		Name:           "expired-device",
		Platform:       domain.PlatformMacOS,
		RelayTokenHash: security.HashOpaqueToken("rt_expired"),
		CreatedAt:      baseTime,
		LastSeenAt:     baseTime,
	}
	expiredSession := domain.PairingSession{
		ID:                 "ps_expired",
		InitiatorDeviceID:  expiredDevice.ID,
		InitiatorName:      expiredDevice.Name,
		InitiatorPlatform:  expiredDevice.Platform,
		InitiatorPublicKey: []byte("initiator-key"),
		PairingSecretHash:  security.HashOpaqueToken("prs_expired"),
		State:              domain.PairingStatePending,
		ExpiresAt:          baseTime.Add(-1 * time.Minute),
		CreatedAt:          baseTime,
		UpdatedAt:          baseTime,
	}

	if err := store.CreatePairingSession(ctx, expiredDevice, expiredSession); err != nil {
		t.Fatalf("create expired pairing session: %v", err)
	}

	activeDevice := domain.Device{
		ID:             "dev_active",
		Name:           "active-device",
		Platform:       domain.PlatformAndroid,
		RelayTokenHash: security.HashOpaqueToken("rt_active"),
		CreatedAt:      baseTime,
		LastSeenAt:     baseTime,
	}
	activeSession := domain.PairingSession{
		ID:                 "ps_active",
		InitiatorDeviceID:  activeDevice.ID,
		InitiatorName:      activeDevice.Name,
		InitiatorPlatform:  activeDevice.Platform,
		InitiatorPublicKey: []byte("active-key"),
		PairingSecretHash:  security.HashOpaqueToken("prs_active"),
		State:              domain.PairingStatePending,
		ExpiresAt:          baseTime.Add(10 * time.Minute),
		CreatedAt:          baseTime,
		UpdatedAt:          baseTime,
	}

	if err := store.CreatePairingSession(ctx, activeDevice, activeSession); err != nil {
		t.Fatalf("create active pairing session: %v", err)
	}

	expiredEnvelope := domain.Envelope{
		ID:                "env_expired",
		SenderDeviceID:    activeDevice.ID,
		RecipientDeviceID: activeDevice.ID,
		Channel:           domain.ChannelClipboard,
		ContentType:       "application/json",
		Nonce:             []byte("nonce-expired"),
		HeaderAAD:         []byte("aad-expired"),
		Ciphertext:        []byte("ciphertext-expired"),
		CreatedAt:         baseTime,
		ExpiresAt:         baseTime.Add(-1 * time.Minute),
	}

	deliveredEnvelope := domain.Envelope{
		ID:                "env_delivered",
		SenderDeviceID:    activeDevice.ID,
		RecipientDeviceID: activeDevice.ID,
		Channel:           domain.ChannelNotification,
		ContentType:       "application/json",
		Nonce:             []byte("nonce-delivered"),
		HeaderAAD:         []byte("aad-delivered"),
		Ciphertext:        []byte("ciphertext-delivered"),
		CreatedAt:         baseTime,
		ExpiresAt:         baseTime.Add(10 * time.Minute),
	}

	activeEnvelope := domain.Envelope{
		ID:                "env_active",
		SenderDeviceID:    activeDevice.ID,
		RecipientDeviceID: activeDevice.ID,
		Channel:           domain.ChannelClipboard,
		ContentType:       "application/json",
		Nonce:             []byte("nonce-active"),
		HeaderAAD:         []byte("aad-active"),
		Ciphertext:        []byte("ciphertext-active"),
		CreatedAt:         baseTime,
		ExpiresAt:         baseTime.Add(10 * time.Minute),
	}

	if err := store.CreateEnvelope(ctx, expiredEnvelope); err != nil {
		t.Fatalf("create expired envelope: %v", err)
	}

	if err := store.CreateEnvelope(ctx, deliveredEnvelope); err != nil {
		t.Fatalf("create delivered envelope: %v", err)
	}

	if err := store.CreateEnvelope(ctx, activeEnvelope); err != nil {
		t.Fatalf("create active envelope: %v", err)
	}

	if err := store.MarkEnvelopeDelivered(ctx, deliveredEnvelope.ID, deliveredEnvelope.RecipientDeviceID, baseTime.Add(2*time.Minute)); err != nil {
		t.Fatalf("mark envelope delivered: %v", err)
	}

	cleanupResult, err := store.DeleteExpired(ctx, baseTime)
	if err != nil {
		t.Fatalf("delete expired: %v", err)
	}

	if cleanupResult.DeletedPairingSessions != 1 {
		t.Fatalf("expected 1 deleted pairing session, got %d", cleanupResult.DeletedPairingSessions)
	}

	if cleanupResult.DeletedEnvelopes != 2 {
		t.Fatalf("expected 2 deleted envelopes, got %d", cleanupResult.DeletedEnvelopes)
	}

	if _, err := store.GetPairingSession(ctx, expiredSession.ID); err == nil {
		t.Fatal("expected expired pairing session to be deleted")
	}

	if _, err := store.GetPairingSession(ctx, activeSession.ID); err != nil {
		t.Fatalf("expected active pairing session to remain, got %v", err)
	}

	pending, err := store.ListPendingEnvelopes(ctx, activeDevice.ID, baseTime, 10)
	if err != nil {
		t.Fatalf("list pending envelopes: %v", err)
	}

	if len(pending) != 1 {
		t.Fatalf("expected 1 active pending envelope, got %d", len(pending))
	}

	if pending[0].ID != activeEnvelope.ID {
		t.Fatalf("expected remaining envelope %q, got %q", activeEnvelope.ID, pending[0].ID)
	}
}

func openSQLiteTestStore(t *testing.T) *Store {
	t.Helper()

	store, err := Open(filepath.Join(t.TempDir(), "relay.db"))
	if err != nil {
		t.Fatalf("open sqlite store: %v", err)
	}

	t.Cleanup(func() {
		if closeErr := store.Close(); closeErr != nil {
			t.Fatalf("close sqlite store: %v", closeErr)
		}
	})

	return store
}
