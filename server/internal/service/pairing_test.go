package service

import (
	"bytes"
	"context"
	"errors"
	"testing"
	"time"

	"github.com/sleepysoong/air-bridge/server/internal/domain"
)

func TestPairingServiceCreateAndGetSession(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	createdAt := time.UnixMilli(1_700_000_000_000).UTC()
	service := NewPairingService(store, 10*time.Minute)
	service.now = func() time.Time { return createdAt }

	createResult, err := service.CreateSession(ctx, CreatePairingSessionInput{
		InitiatorDeviceName: "sleepysoong-macbook-air",
		InitiatorPlatform:   domain.PlatformMacOS,
		InitiatorPublicKey:  x25519PublicKey(0x31),
	})
	if err != nil {
		t.Fatalf("create session: %v", err)
	}

	if createResult.PairingSecret == "" {
		t.Fatal("expected pairing secret to be returned")
	}

	if createResult.InitiatorRelayToken == "" {
		t.Fatal("expected initiator relay token to be returned")
	}

	gotSession, err := service.GetSession(ctx, createResult.Session.ID, createResult.PairingSecret)
	if err != nil {
		t.Fatalf("get session: %v", err)
	}

	if gotSession.State != domain.PairingStatePending {
		t.Fatalf("expected pending state, got %q", gotSession.State)
	}

	if gotSession.InitiatorDeviceID != createResult.InitiatorDevice.ID {
		t.Fatalf("expected initiator device ID %q, got %q", createResult.InitiatorDevice.ID, gotSession.InitiatorDeviceID)
	}

	if !bytes.Equal(gotSession.InitiatorPublicKey, x25519PublicKey(0x31)) {
		t.Fatalf("unexpected initiator public key: %q", gotSession.InitiatorPublicKey)
	}
}

func TestPairingServiceGetSessionRejectsWrongSecret(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	service := NewPairingService(store, 10*time.Minute)
	service.now = func() time.Time { return time.UnixMilli(1_700_000_100_000).UTC() }

	createResult, err := service.CreateSession(ctx, CreatePairingSessionInput{
		InitiatorDeviceName: "sleepysoong-macbook-air",
		InitiatorPlatform:   domain.PlatformMacOS,
		InitiatorPublicKey:  x25519PublicKey(0x32),
	})
	if err != nil {
		t.Fatalf("create session: %v", err)
	}

	_, err = service.GetSession(ctx, createResult.Session.ID, "prs_wrong")
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("expected unauthorized error, got %v", err)
	}
}

func TestPairingServiceGetSessionRejectsExpiredSession(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	baseTime := time.UnixMilli(1_700_000_200_000).UTC()
	service := NewPairingService(store, 1*time.Minute)
	service.now = func() time.Time { return baseTime }

	createResult, err := service.CreateSession(ctx, CreatePairingSessionInput{
		InitiatorDeviceName: "sleepysoong-macbook-air",
		InitiatorPlatform:   domain.PlatformMacOS,
		InitiatorPublicKey:  x25519PublicKey(0x33),
	})
	if err != nil {
		t.Fatalf("create session: %v", err)
	}

	service.now = func() time.Time { return baseTime.Add(2 * time.Minute) }

	_, err = service.GetSession(ctx, createResult.Session.ID, createResult.PairingSecret)
	if !errors.Is(err, ErrExpired) {
		t.Fatalf("expected expired error, got %v", err)
	}
}

func TestPairingServiceJoinAndCompleteSession(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	baseTime := time.UnixMilli(1_700_000_300_000).UTC()
	service := NewPairingService(store, 10*time.Minute)

	createResult, joinResult := createReadyPair(t, ctx, service, baseTime)

	if joinResult.Session.State != domain.PairingStateReady {
		t.Fatalf("expected ready state after join, got %q", joinResult.Session.State)
	}

	initiatorDevice, err := store.GetDevice(ctx, createResult.InitiatorDevice.ID)
	if err != nil {
		t.Fatalf("get initiator device: %v", err)
	}

	if initiatorDevice.PeerDeviceID != joinResult.JoinerDevice.ID {
		t.Fatalf("expected initiator peer %q, got %q", joinResult.JoinerDevice.ID, initiatorDevice.PeerDeviceID)
	}

	joinerDevice, err := store.GetDevice(ctx, joinResult.JoinerDevice.ID)
	if err != nil {
		t.Fatalf("get joiner device: %v", err)
	}

	if joinerDevice.PeerDeviceID != createResult.InitiatorDevice.ID {
		t.Fatalf("expected joiner peer %q, got %q", createResult.InitiatorDevice.ID, joinerDevice.PeerDeviceID)
	}

	service.now = func() time.Time { return baseTime.Add(2 * time.Minute) }

	completedSession, err := service.CompleteSession(ctx, createResult.Session.ID, createResult.PairingSecret)
	if err != nil {
		t.Fatalf("complete session: %v", err)
	}

	if completedSession.State != domain.PairingStateCompleted {
		t.Fatalf("expected completed state, got %q", completedSession.State)
	}

	if completedSession.CompletedAt == nil {
		t.Fatal("expected completed_at to be set")
	}
}

func TestPairingServiceCompleteRejectsPendingSession(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	service := NewPairingService(store, 10*time.Minute)
	service.now = func() time.Time { return time.UnixMilli(1_700_000_400_000).UTC() }

	createResult, err := service.CreateSession(ctx, CreatePairingSessionInput{
		InitiatorDeviceName: "sleepysoong-macbook-air",
		InitiatorPlatform:   domain.PlatformMacOS,
		InitiatorPublicKey:  x25519PublicKey(0x34),
	})
	if err != nil {
		t.Fatalf("create session: %v", err)
	}

	_, err = service.CompleteSession(ctx, createResult.Session.ID, createResult.PairingSecret)
	if !errors.Is(err, ErrConflict) {
		t.Fatalf("expected conflict error, got %v", err)
	}
}
