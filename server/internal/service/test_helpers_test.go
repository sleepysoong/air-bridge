package service

import (
	"context"
	"bytes"
	"path/filepath"
	"testing"
	"time"

	"github.com/sleepysoong/air-bridge/server/internal/domain"
	"github.com/sleepysoong/air-bridge/server/internal/persistence/sqlite"
)

func openTestStore(t *testing.T) *sqlite.Store {
	t.Helper()

	store, err := sqlite.Open(filepath.Join(t.TempDir(), "relay.db"))
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

func createReadyPair(
	t *testing.T,
	ctx context.Context,
	pairingService *PairingService,
	createdAt time.Time,
) (CreatePairingSessionResult, JoinPairingSessionResult) {
	t.Helper()

	pairingService.now = func() time.Time { return createdAt }

	createResult, err := pairingService.CreateSession(ctx, CreatePairingSessionInput{
		InitiatorDeviceName: "macbook-air",
		InitiatorPlatform:   domain.PlatformMacOS,
		InitiatorPublicKey:  x25519PublicKey(0x11),
	})
	if err != nil {
		t.Fatalf("create pairing session: %v", err)
	}

	pairingService.now = func() time.Time { return createdAt.Add(1 * time.Minute) }

	joinResult, err := pairingService.JoinSession(ctx, JoinPairingSessionInput{
		SessionID:        createResult.Session.ID,
		PairingSecret:    createResult.PairingSecret,
		JoinerDeviceName: "pixel-android",
		JoinerPlatform:   domain.PlatformAndroid,
		JoinerPublicKey:  x25519PublicKey(0x22),
	})
	if err != nil {
		t.Fatalf("join pairing session: %v", err)
	}

	return createResult, joinResult
}

func createCompletedPair(
	t *testing.T,
	ctx context.Context,
	pairingService *PairingService,
	createdAt time.Time,
) (CreatePairingSessionResult, JoinPairingSessionResult, domain.PairingSession) {
	t.Helper()

	createResult, joinResult := createReadyPair(t, ctx, pairingService, createdAt)

	pairingService.now = func() time.Time { return createdAt.Add(2 * time.Minute) }

	completedSession, err := pairingService.CompleteSession(ctx, createResult.Session.ID, createResult.PairingSecret)
	if err != nil {
		t.Fatalf("complete pairing session: %v", err)
	}

	return createResult, joinResult, completedSession
}

func x25519PublicKey(fill byte) []byte {
	return bytes.Repeat([]byte{fill}, domain.X25519PublicKeyLength)
}
