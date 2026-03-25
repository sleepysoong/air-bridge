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

func TestPairingServiceCreateAndGetSession(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	createdAt := time.UnixMilli(1_700_000_000_000).UTC()
	service := NewPairingService(newDiscardLogger(), store, 10*time.Minute)
	service.now = func() time.Time { return createdAt }

	createResult, err := service.CreateSession(ctx, CreatePairingSessionInput{
		InitiatorDeviceName: "sleepysoong-macbook-air",
		InitiatorPlatform:   domain.PlatformMacOS,
		InitiatorPublicKey:  x25519PublicKey(0x31),
	})
	if err != nil {
		t.Fatalf("세션을 생성하지 못했어요: %v", err)
	}

	if createResult.PairingSecret == "" {
		t.Fatal("페어링 비밀값이 반환되어야 해요")
	}

	if createResult.InitiatorRelayToken == "" {
		t.Fatal("시작 기기 relay token이 반환되어야 해요")
	}

	gotSession, err := service.GetSession(ctx, createResult.Session.ID, createResult.PairingSecret)
	if err != nil {
		t.Fatalf("세션을 조회하지 못했어요: %v", err)
	}

	if gotSession.State != domain.PairingStatePending {
		t.Fatalf("세션 상태는 pending 이어야 해요. 실제 값: %q", gotSession.State)
	}

	if gotSession.InitiatorDeviceID != createResult.InitiatorDevice.ID {
		t.Fatalf("시작 기기 ID는 %q 이어야 해요. 실제 값: %q", createResult.InitiatorDevice.ID, gotSession.InitiatorDeviceID)
	}

	if !bytes.Equal(gotSession.InitiatorPublicKey, x25519PublicKey(0x31)) {
		t.Fatalf("시작 기기 공개키가 예상과 달라요: %q", gotSession.InitiatorPublicKey)
	}
}

func TestPairingServiceGetSessionRejectsWrongSecret(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	service := NewPairingService(newDiscardLogger(), store, 10*time.Minute)
	service.now = func() time.Time { return time.UnixMilli(1_700_000_100_000).UTC() }

	createResult, err := service.CreateSession(ctx, CreatePairingSessionInput{
		InitiatorDeviceName: "sleepysoong-macbook-air",
		InitiatorPlatform:   domain.PlatformMacOS,
		InitiatorPublicKey:  x25519PublicKey(0x32),
	})
	if err != nil {
		t.Fatalf("세션을 생성하지 못했어요: %v", err)
	}

	_, err = service.GetSession(ctx, createResult.Session.ID, "prs_wrong")
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("권한 오류가 반환되어야 해요. 실제 값: %v", err)
	}
}

func TestPairingServiceGetSessionRejectsExpiredSession(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	baseTime := time.UnixMilli(1_700_000_200_000).UTC()
	service := NewPairingService(newDiscardLogger(), store, 1*time.Minute)
	service.now = func() time.Time { return baseTime }

	createResult, err := service.CreateSession(ctx, CreatePairingSessionInput{
		InitiatorDeviceName: "sleepysoong-macbook-air",
		InitiatorPlatform:   domain.PlatformMacOS,
		InitiatorPublicKey:  x25519PublicKey(0x33),
	})
	if err != nil {
		t.Fatalf("세션을 생성하지 못했어요: %v", err)
	}

	service.now = func() time.Time { return baseTime.Add(2 * time.Minute) }

	_, err = service.GetSession(ctx, createResult.Session.ID, createResult.PairingSecret)
	if !errors.Is(err, ErrExpired) {
		t.Fatalf("만료 오류가 반환되어야 해요. 실제 값: %v", err)
	}
}

func TestPairingServiceJoinAndCompleteSession(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	baseTime := time.UnixMilli(1_700_000_300_000).UTC()
	service := NewPairingService(newDiscardLogger(), store, 10*time.Minute)

	createResult, joinResult := createReadyPair(t, ctx, service, baseTime)

	if joinResult.Session.State != domain.PairingStateReady {
		t.Fatalf("참여 뒤 세션 상태는 ready 여야 해요. 실제 값: %q", joinResult.Session.State)
	}

	initiatorDevice, err := store.GetDevice(ctx, createResult.InitiatorDevice.ID)
	if err != nil {
		t.Fatalf("시작 기기를 조회하지 못했어요: %v", err)
	}

	if initiatorDevice.PeerDeviceID != joinResult.JoinerDevice.ID {
		t.Fatalf("시작 기기의 peer 는 %q 이어야 해요. 실제 값: %q", joinResult.JoinerDevice.ID, initiatorDevice.PeerDeviceID)
	}

	joinerDevice, err := store.GetDevice(ctx, joinResult.JoinerDevice.ID)
	if err != nil {
		t.Fatalf("참여 기기를 조회하지 못했어요: %v", err)
	}

	if joinerDevice.PeerDeviceID != createResult.InitiatorDevice.ID {
		t.Fatalf("참여 기기의 peer 는 %q 이어야 해요. 실제 값: %q", createResult.InitiatorDevice.ID, joinerDevice.PeerDeviceID)
	}

	service.now = func() time.Time { return baseTime.Add(2 * time.Minute) }

	completedSession, err := service.CompleteSession(ctx, createResult.Session.ID, createResult.PairingSecret)
	if err != nil {
		t.Fatalf("세션을 완료하지 못했어요: %v", err)
	}

	if completedSession.State != domain.PairingStateCompleted {
		t.Fatalf("세션 상태는 completed 여야 해요. 실제 값: %q", completedSession.State)
	}

	if completedSession.CompletedAt == nil {
		t.Fatal("completed_at 값이 설정되어야 해요")
	}
}

func TestPairingServiceCompleteRejectsPendingSession(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	service := NewPairingService(newDiscardLogger(), store, 10*time.Minute)
	service.now = func() time.Time { return time.UnixMilli(1_700_000_400_000).UTC() }

	createResult, err := service.CreateSession(ctx, CreatePairingSessionInput{
		InitiatorDeviceName: "sleepysoong-macbook-air",
		InitiatorPlatform:   domain.PlatformMacOS,
		InitiatorPublicKey:  x25519PublicKey(0x34),
	})
	if err != nil {
		t.Fatalf("세션을 생성하지 못했어요: %v", err)
	}

	_, err = service.CompleteSession(ctx, createResult.Session.ID, createResult.PairingSecret)
	if !errors.Is(err, ErrConflict) {
		t.Fatalf("충돌 오류가 반환되어야 해요. 실제 값: %v", err)
	}
}

func TestPairingServiceCreateSessionRejectsTooLongDeviceName(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	service := NewPairingService(newDiscardLogger(), store, 10*time.Minute)

	_, err := service.CreateSession(ctx, CreatePairingSessionInput{
		InitiatorDeviceName: strings.Repeat("가", MaxDeviceNameRunes+1),
		InitiatorPlatform:   domain.PlatformMacOS,
		InitiatorPublicKey:  x25519PublicKey(0x35),
	})
	if !errors.Is(err, ErrInvalidInput) {
		t.Fatalf("입력 오류가 반환되어야 해요. 실제 값: %v", err)
	}
}

func TestPairingServiceGetSessionRejectsTooLongPairingSecret(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	store := openTestStore(t)

	service := NewPairingService(newDiscardLogger(), store, 10*time.Minute)
	service.now = func() time.Time { return time.UnixMilli(1_700_000_450_000).UTC() }

	createResult, err := service.CreateSession(ctx, CreatePairingSessionInput{
		InitiatorDeviceName: "sleepysoong-macbook-air",
		InitiatorPlatform:   domain.PlatformMacOS,
		InitiatorPublicKey:  x25519PublicKey(0x36),
	})
	if err != nil {
		t.Fatalf("세션을 생성하지 못했어요: %v", err)
	}

	_, err = service.GetSession(ctx, createResult.Session.ID, strings.Repeat("x", MaxPairingSecretRunes+1))
	if !errors.Is(err, ErrInvalidInput) {
		t.Fatalf("길이 초과 페어링 비밀값에는 입력 오류가 반환되어야 해요. 실제 값: %v", err)
	}
}
