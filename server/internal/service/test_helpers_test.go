package service

import (
	"bytes"
	"context"
	"io"
	"log/slog"
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
		t.Fatalf("SQLite 테스트 저장소를 열지 못했어요: %v", err)
	}

	t.Cleanup(func() {
		if closeErr := store.Close(); closeErr != nil {
			t.Fatalf("SQLite 테스트 저장소를 닫지 못했어요: %v", closeErr)
		}
	})

	return store
}

func newDiscardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
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
		t.Fatalf("페어링 세션을 생성하지 못했어요: %v", err)
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
		t.Fatalf("페어링 세션에 참여하지 못했어요: %v", err)
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

	return createResult, joinResult, joinResult.Session
}

func x25519PublicKey(fill byte) []byte {
	return bytes.Repeat([]byte{fill}, domain.X25519PublicKeyLength)
}
