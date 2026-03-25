package sqlite

import (
	"bytes"
	"context"
	"database/sql"
	"path/filepath"
	"testing"
	"time"

	"github.com/sleepysoong/air-bridge/server/internal/domain"
	"github.com/sleepysoong/air-bridge/server/internal/security"

	_ "modernc.org/sqlite"
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
		InitiatorPublicKey: bytes.Repeat([]byte{0x11}, 32),
		PairingSecretHash:  security.HashOpaqueToken("prs_expired"),
		State:              domain.PairingStatePending,
		ExpiresAt:          baseTime.Add(-1 * time.Minute),
		CreatedAt:          baseTime,
		UpdatedAt:          baseTime,
	}

	if err := store.CreatePairingSession(ctx, expiredDevice, expiredSession); err != nil {
		t.Fatalf("만료된 페어링 세션 데이터를 만들지 못했어요: %v", err)
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
		InitiatorPublicKey: bytes.Repeat([]byte{0x22}, 32),
		PairingSecretHash:  security.HashOpaqueToken("prs_active"),
		State:              domain.PairingStatePending,
		ExpiresAt:          baseTime.Add(10 * time.Minute),
		CreatedAt:          baseTime,
		UpdatedAt:          baseTime,
	}

	if err := store.CreatePairingSession(ctx, activeDevice, activeSession); err != nil {
		t.Fatalf("활성 페어링 세션 데이터를 만들지 못했어요: %v", err)
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
		t.Fatalf("만료된 envelope 데이터를 만들지 못했어요: %v", err)
	}

	if err := store.CreateEnvelope(ctx, deliveredEnvelope); err != nil {
		t.Fatalf("전달 완료 envelope 데이터를 만들지 못했어요: %v", err)
	}

	if err := store.CreateEnvelope(ctx, activeEnvelope); err != nil {
		t.Fatalf("활성 envelope 데이터를 만들지 못했어요: %v", err)
	}

	if err := store.MarkEnvelopeDelivered(ctx, deliveredEnvelope.ID, deliveredEnvelope.RecipientDeviceID, baseTime.Add(2*time.Minute)); err != nil {
		t.Fatalf("envelope 전달 완료를 표시하지 못했어요: %v", err)
	}

	cleanupResult, err := store.DeleteExpired(ctx, baseTime)
	if err != nil {
		t.Fatalf("만료 데이터를 정리하지 못했어요: %v", err)
	}

	if cleanupResult.DeletedPairingSessions != 1 {
		t.Fatalf("삭제된 페어링 세션 개수는 1이어야 해요. 실제 값: %d", cleanupResult.DeletedPairingSessions)
	}

	if cleanupResult.DeletedEnvelopes != 2 {
		t.Fatalf("삭제된 envelope 개수는 2여야 해요. 실제 값: %d", cleanupResult.DeletedEnvelopes)
	}

	if _, err := store.GetPairingSession(ctx, expiredSession.ID); err == nil {
		t.Fatal("만료된 페어링 세션은 삭제되어야 해요")
	}

	if _, err := store.GetPairingSession(ctx, activeSession.ID); err != nil {
		t.Fatalf("활성 페어링 세션은 남아 있어야 해요: %v", err)
	}

	pending, err := store.ListPendingEnvelopes(ctx, activeDevice.ID, baseTime, 10)
	if err != nil {
		t.Fatalf("대기 중인 envelope를 조회하지 못했어요: %v", err)
	}

	if len(pending) != 1 {
		t.Fatalf("활성 대기 envelope 개수는 1이어야 해요. 실제 값: %d", len(pending))
	}

	if pending[0].ID != activeEnvelope.ID {
		t.Fatalf("남아 있는 envelope는 %q 이어야 해요. 실제 값: %q", activeEnvelope.ID, pending[0].ID)
	}
}

func TestStoreCreateEnvelopeRejectsUnknownDevices(t *testing.T) {
	t.Parallel()

	store := openSQLiteTestStore(t)

	err := store.CreateEnvelope(context.Background(), domain.Envelope{
		ID:                "env_missing_devices",
		SenderDeviceID:    "dev_missing_sender",
		RecipientDeviceID: "dev_missing_recipient",
		Channel:           domain.ChannelClipboard,
		ContentType:       "application/json",
		Nonce:             []byte("123456789012"),
		HeaderAAD:         []byte("aad"),
		Ciphertext:        []byte("ciphertext"),
		CreatedAt:         time.UnixMilli(1_700_001_100_000).UTC(),
		ExpiresAt:         time.UnixMilli(1_700_001_200_000).UTC(),
	})
	if err == nil {
		t.Fatal("존재하지 않는 기기 ID로는 envelope를 저장하면 안 돼요")
	}
}

func TestStoreCreatePairingSessionRejectsInvalidPlatformValue(t *testing.T) {
	t.Parallel()

	store := openSQLiteTestStore(t)
	createdAt := time.UnixMilli(1_700_001_300_000).UTC()

	err := store.CreatePairingSession(context.Background(), domain.Device{
		ID:             "dev_invalid_platform",
		Name:           "invalid-platform-device",
		Platform:       domain.Platform("windows"),
		RelayTokenHash: security.HashOpaqueToken("rt_invalid_platform"),
		CreatedAt:      createdAt,
		LastSeenAt:     createdAt,
	}, domain.PairingSession{
		ID:                 "ps_invalid_platform",
		InitiatorDeviceID:  "dev_invalid_platform",
		InitiatorName:      "invalid-platform-device",
		InitiatorPlatform:  domain.Platform("windows"),
		InitiatorPublicKey: bytes.Repeat([]byte{0x33}, 32),
		PairingSecretHash:  security.HashOpaqueToken("prs_invalid_platform"),
		State:              domain.PairingStatePending,
		ExpiresAt:          createdAt.Add(10 * time.Minute),
		CreatedAt:          createdAt,
		UpdatedAt:          createdAt,
	})
	if err == nil {
		t.Fatal("지원하지 않는 플랫폼 값은 저장소 제약에서 거부되어야 해요")
	}
}

func TestOpenMigratesLegacySchemaToConstrainedSchema(t *testing.T) {
	t.Parallel()

	databasePath := filepath.Join(t.TempDir(), "legacy-relay.db")
	seedLegacySchema(t, databasePath)

	store, err := Open(databasePath)
	if err != nil {
		t.Fatalf("레거시 SQLite 저장소를 마이그레이션하며 열지 못했어요: %v", err)
	}

	t.Cleanup(func() {
		if closeErr := store.Close(); closeErr != nil {
			t.Fatalf("마이그레이션된 SQLite 저장소를 닫지 못했어요: %v", closeErr)
		}
	})

	hasPairingConfirmedColumn, err := store.hasColumn(context.Background(), "devices", "pairing_confirmed_at_ms")
	if err != nil {
		t.Fatalf("devices 테이블 컬럼 정보를 확인하지 못했어요: %v", err)
	}

	if !hasPairingConfirmedColumn {
		t.Fatal("레거시 스키마를 열면 pairing_confirmed_at_ms 컬럼이 추가되어야 해요")
	}

	needsRebuild, err := store.schemaNeedsRebuild(context.Background())
	if err != nil {
		t.Fatalf("마이그레이션 뒤 스키마 정의를 다시 확인하지 못했어요: %v", err)
	}

	if needsRebuild {
		t.Fatal("레거시 스키마는 보강된 제약 정의로 재구성되어야 해요")
	}

	session, err := store.GetPairingSession(context.Background(), "ps_legacy")
	if err != nil {
		t.Fatalf("마이그레이션 뒤에도 기존 페어링 세션을 조회할 수 있어야 해요: %v", err)
	}

	if session.InitiatorDeviceID != "dev_legacy" {
		t.Fatalf("기존 페어링 세션 데이터가 보존되어야 해요. initiator_device_id=%q", session.InitiatorDeviceID)
	}
}

func openSQLiteTestStore(t *testing.T) *Store {
	t.Helper()

	store, err := Open(filepath.Join(t.TempDir(), "relay.db"))
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

func seedLegacySchema(t *testing.T, databasePath string) {
	t.Helper()

	db, err := sql.Open("sqlite", databasePath)
	if err != nil {
		t.Fatalf("레거시 SQLite 저장소를 만들지 못했어요: %v", err)
	}
	defer db.Close()

	const legacySchema = `
PRAGMA foreign_keys = ON;

CREATE TABLE devices (
	id TEXT PRIMARY KEY,
	name TEXT NOT NULL,
	platform TEXT NOT NULL,
	peer_device_id TEXT,
	relay_token_hash BLOB NOT NULL,
	created_at_ms INTEGER NOT NULL,
	last_seen_at_ms INTEGER NOT NULL
);

CREATE TABLE pairing_sessions (
	id TEXT PRIMARY KEY,
	initiator_device_id TEXT NOT NULL,
	initiator_name TEXT NOT NULL,
	initiator_platform TEXT NOT NULL,
	initiator_public_key BLOB NOT NULL,
	pairing_secret_hash BLOB NOT NULL,
	joiner_device_id TEXT,
	joiner_name TEXT,
	joiner_platform TEXT,
	joiner_public_key BLOB,
	state TEXT NOT NULL,
	expires_at_ms INTEGER NOT NULL,
	created_at_ms INTEGER NOT NULL,
	updated_at_ms INTEGER NOT NULL,
	completed_at_ms INTEGER
);

CREATE TABLE envelopes (
	id TEXT PRIMARY KEY,
	sender_device_id TEXT NOT NULL,
	recipient_device_id TEXT NOT NULL,
	channel TEXT NOT NULL,
	content_type TEXT NOT NULL,
	nonce BLOB NOT NULL,
	header_aad BLOB NOT NULL,
	ciphertext BLOB NOT NULL,
	created_at_ms INTEGER NOT NULL,
	expires_at_ms INTEGER NOT NULL,
	delivered_at_ms INTEGER
);
`

	if _, err := db.Exec(legacySchema); err != nil {
		t.Fatalf("레거시 스키마를 만들지 못했어요: %v", err)
	}

	createdAt := time.UnixMilli(1_700_002_000_000).UTC()

	if _, err := db.Exec(
		`INSERT INTO devices (id, name, platform, peer_device_id, relay_token_hash, created_at_ms, last_seen_at_ms)
		VALUES (?, ?, ?, NULL, ?, ?, ?)`,
		"dev_legacy",
		"legacy-mac",
		"macos",
		security.HashOpaqueToken("rt_legacy"),
		createdAt.UnixMilli(),
		createdAt.UnixMilli(),
	); err != nil {
		t.Fatalf("레거시 device 데이터를 넣지 못했어요: %v", err)
	}

	if _, err := db.Exec(
		`INSERT INTO pairing_sessions (
			id,
			initiator_device_id,
			initiator_name,
			initiator_platform,
			initiator_public_key,
			pairing_secret_hash,
			joiner_device_id,
			joiner_name,
			joiner_platform,
			joiner_public_key,
			state,
			expires_at_ms,
			created_at_ms,
			updated_at_ms,
			completed_at_ms
		) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, ?, ?, ?, ?, NULL)`,
		"ps_legacy",
		"dev_legacy",
		"legacy-mac",
		"macos",
		bytes.Repeat([]byte{0x44}, 32),
		security.HashOpaqueToken("prs_legacy"),
		string(domain.PairingStatePending),
		createdAt.Add(10*time.Minute).UnixMilli(),
		createdAt.UnixMilli(),
		createdAt.UnixMilli(),
	); err != nil {
		t.Fatalf("레거시 pairing_session 데이터를 넣지 못했어요: %v", err)
	}
}
