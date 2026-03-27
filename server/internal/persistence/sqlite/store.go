package sqlite

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"

	_ "modernc.org/sqlite"

	"github.com/sleepysoong/air-bridge/server/internal/domain"
)

type Store struct {
	db *sql.DB
}

type CleanupResult struct {
	DeletedPairingSessions int64
	DeletedEnvelopes       int64
}

const (
	maxDeviceNameRunes  = 128
	maxContentTypeBytes = 255
	maxNonceBytes       = 64
	maxHeaderAADBytes   = 16 * 1024
	maxCiphertextBytes  = 20*1024*1024 + 16
)

const pragmaSQL = `
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
`

const createDevicesTableSQL = `
CREATE TABLE IF NOT EXISTS devices (
	id TEXT PRIMARY KEY,
	name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 128),
	platform TEXT NOT NULL CHECK(platform IN ('macos', 'android')),
	peer_device_id TEXT REFERENCES devices(id) ON DELETE SET NULL,
	relay_token_hash BLOB NOT NULL CHECK(length(relay_token_hash) > 0),
	pairing_confirmed_at_ms INTEGER,
	created_at_ms INTEGER NOT NULL,
	last_seen_at_ms INTEGER NOT NULL
);
`

const createPairingSessionsTableSQL = `
CREATE TABLE IF NOT EXISTS pairing_sessions (
	id TEXT PRIMARY KEY,
	initiator_device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
	initiator_name TEXT NOT NULL CHECK(length(initiator_name) BETWEEN 1 AND 128),
	initiator_platform TEXT NOT NULL CHECK(initiator_platform IN ('macos', 'android')),
	initiator_public_key BLOB NOT NULL CHECK(length(initiator_public_key) = 32),
	pairing_secret_hash BLOB NOT NULL CHECK(length(pairing_secret_hash) > 0),
	joiner_device_id TEXT REFERENCES devices(id) ON DELETE SET NULL,
	joiner_name TEXT CHECK(joiner_name IS NULL OR length(joiner_name) BETWEEN 1 AND 128),
	joiner_platform TEXT CHECK(joiner_platform IS NULL OR joiner_platform IN ('macos', 'android')),
	joiner_public_key BLOB CHECK(joiner_public_key IS NULL OR length(joiner_public_key) = 32),
	state TEXT NOT NULL CHECK(state IN ('pending', 'ready', 'completed')),
	expires_at_ms INTEGER NOT NULL,
	created_at_ms INTEGER NOT NULL,
	updated_at_ms INTEGER NOT NULL,
	completed_at_ms INTEGER,
	CHECK(
		(state = 'pending' AND joiner_device_id IS NULL AND joiner_name IS NULL AND joiner_platform IS NULL AND joiner_public_key IS NULL AND completed_at_ms IS NULL) OR
		(state = 'ready' AND joiner_device_id IS NOT NULL AND joiner_name IS NOT NULL AND joiner_platform IS NOT NULL AND joiner_public_key IS NOT NULL AND completed_at_ms IS NULL) OR
		(state = 'completed' AND joiner_device_id IS NOT NULL AND joiner_name IS NOT NULL AND joiner_platform IS NOT NULL AND joiner_public_key IS NOT NULL AND completed_at_ms IS NOT NULL)
	)
);
`

const createEnvelopesTableSQL = `
CREATE TABLE IF NOT EXISTS envelopes (
	id TEXT PRIMARY KEY,
	sender_device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
	recipient_device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
	channel TEXT NOT NULL CHECK(channel IN ('clipboard', 'notification')),
	content_type TEXT NOT NULL CHECK(length(content_type) BETWEEN 1 AND 255),
	nonce BLOB NOT NULL CHECK(length(nonce) BETWEEN 1 AND 64),
	header_aad BLOB NOT NULL CHECK(length(header_aad) BETWEEN 1 AND 16384),
	ciphertext BLOB NOT NULL CHECK(length(ciphertext) BETWEEN 1 AND 20971536),
	created_at_ms INTEGER NOT NULL,
	expires_at_ms INTEGER NOT NULL,
	delivered_at_ms INTEGER,
	CHECK(delivered_at_ms IS NULL OR delivered_at_ms >= created_at_ms)
);
`

const createIndexesSQL = `
CREATE INDEX IF NOT EXISTS idx_devices_peer_device_id ON devices(peer_device_id);
CREATE INDEX IF NOT EXISTS idx_pairing_sessions_expires_at_ms ON pairing_sessions(expires_at_ms);
CREATE INDEX IF NOT EXISTS idx_envelopes_recipient_device_id_created_at_ms ON envelopes(recipient_device_id, created_at_ms);
CREATE INDEX IF NOT EXISTS idx_envelopes_expires_at_ms ON envelopes(expires_at_ms);
`

var requiredTableDefinitionFragments = map[string][]string{
	"devices": {
		"check(length(name) between 1 and 128)",
		"check(platform in ('macos', 'android'))",
		"peer_device_id text references devices(id) on delete set null",
	},
	"pairing_sessions": {
		"initiator_device_id text not null references devices(id) on delete cascade",
		"check(state in ('pending', 'ready', 'completed'))",
		"state = 'completed'",
	},
	"envelopes": {
		"sender_device_id text not null references devices(id) on delete cascade",
		"check(channel in ('clipboard', 'notification'))",
		"check(length(ciphertext) between 1 and 20971536)",
	},
}

func Open(databasePath string) (*Store, error) {
	db, err := sql.Open("sqlite", databasePath)
	if err != nil {
		return nil, err
	}

	db.SetMaxOpenConns(1)

	store := &Store{db: db}
	if err := store.migrate(context.Background()); err != nil {
		db.Close()
		return nil, err
	}

	return store, nil
}

func (s *Store) Close() error {
	return s.db.Close()
}

func (s *Store) CreatePairingSession(ctx context.Context, initiatorDevice domain.Device, session domain.PairingSession) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}

	defer tx.Rollback()

	if err := insertDevice(ctx, tx, initiatorDevice); err != nil {
		return err
	}

	_, err = tx.ExecContext(
		ctx,
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
		session.ID,
		session.InitiatorDeviceID,
		session.InitiatorName,
		string(session.InitiatorPlatform),
		session.InitiatorPublicKey,
		session.PairingSecretHash,
		string(session.State),
		toUnixMillis(session.ExpiresAt),
		toUnixMillis(session.CreatedAt),
		toUnixMillis(session.UpdatedAt),
	)
	if err != nil {
		return err
	}

	return tx.Commit()
}

func (s *Store) GetPairingSession(ctx context.Context, sessionID string) (domain.PairingSession, error) {
	row := s.db.QueryRowContext(
		ctx,
		`SELECT
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
		FROM pairing_sessions
		WHERE id = ?`,
		sessionID,
	)

	session, err := scanPairingSession(row)
	if err != nil {
		return domain.PairingSession{}, err
	}

	return session, nil
}

func (s *Store) JoinPairingSession(ctx context.Context, session domain.PairingSession, joinerDevice domain.Device, joinedAt time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}

	defer tx.Rollback()

	if err := insertDevice(ctx, tx, joinerDevice); err != nil {
		return err
	}

	result, err := tx.ExecContext(
		ctx,
		`UPDATE devices
		SET peer_device_id = ?, last_seen_at_ms = ?
		WHERE id = ?`,
		joinerDevice.ID,
		toUnixMillis(joinedAt),
		session.InitiatorDeviceID,
	)
	if err != nil {
		return err
	}

	if err := ensureRowsAffected(result); err != nil {
		return err
	}

	result, err = tx.ExecContext(
		ctx,
		`UPDATE pairing_sessions
		SET
			joiner_device_id = ?,
			joiner_name = ?,
			joiner_platform = ?,
			joiner_public_key = ?,
			state = ?,
			updated_at_ms = ?,
			completed_at_ms = ?
		WHERE id = ? AND state = ?`,
		joinerDevice.ID,
		session.JoinerName,
		string(session.JoinerPlatform),
		session.JoinerPublicKey,
		string(domain.PairingStateCompleted),
		toUnixMillis(joinedAt),
		toUnixMillis(joinedAt),
		session.ID,
		string(domain.PairingStatePending),
	)
	if err != nil {
		return err
	}

	if err := ensureRowsAffected(result); err != nil {
		return err
	}

	result, err = tx.ExecContext(
		ctx,
		`UPDATE devices
		SET pairing_confirmed_at_ms = ?, last_seen_at_ms = ?
		WHERE id IN (?, ?)`,
		toUnixMillis(joinedAt),
		toUnixMillis(joinedAt),
		session.InitiatorDeviceID,
		session.JoinerDeviceID,
	)
	if err != nil {
		return err
	}

	rowsAffected, err := result.RowsAffected()
	if err != nil {
		return err
	}

	if rowsAffected != 2 {
		return sql.ErrNoRows
	}

	return tx.Commit()
}

func (s *Store) GetDevice(ctx context.Context, deviceID string) (domain.Device, error) {
	row := s.db.QueryRowContext(
		ctx,
		`SELECT
			id,
			name,
			platform,
			peer_device_id,
			relay_token_hash,
			pairing_confirmed_at_ms,
			created_at_ms,
			last_seen_at_ms
		FROM devices
		WHERE id = ?`,
		deviceID,
	)

	device, err := scanDevice(row)
	if err != nil {
		return domain.Device{}, err
	}

	return device, nil
}

func (s *Store) GetDeviceByCredentials(ctx context.Context, deviceID string, relayTokenHash []byte) (domain.Device, error) {
	row := s.db.QueryRowContext(
		ctx,
		`SELECT
			id,
			name,
			platform,
			peer_device_id,
			relay_token_hash,
			pairing_confirmed_at_ms,
			created_at_ms,
			last_seen_at_ms
		FROM devices
		WHERE id = ? AND relay_token_hash = ?`,
		deviceID,
		relayTokenHash,
	)

	device, err := scanDevice(row)
	if err != nil {
		return domain.Device{}, err
	}

	return device, nil
}

func (s *Store) TouchDevice(ctx context.Context, deviceID string, seenAt time.Time) error {
	result, err := s.db.ExecContext(
		ctx,
		`UPDATE devices SET last_seen_at_ms = ? WHERE id = ?`,
		toUnixMillis(seenAt),
		deviceID,
	)
	if err != nil {
		return err
	}

	return ensureRowsAffected(result)
}

func (s *Store) CreateEnvelope(ctx context.Context, envelope domain.Envelope) error {
	_, err := s.db.ExecContext(
		ctx,
		`INSERT INTO envelopes (
			id,
			sender_device_id,
			recipient_device_id,
			channel,
			content_type,
			nonce,
			header_aad,
			ciphertext,
			created_at_ms,
			expires_at_ms,
			delivered_at_ms
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)`,
		envelope.ID,
		envelope.SenderDeviceID,
		envelope.RecipientDeviceID,
		string(envelope.Channel),
		envelope.ContentType,
		envelope.Nonce,
		envelope.HeaderAAD,
		envelope.Ciphertext,
		toUnixMillis(envelope.CreatedAt),
		toUnixMillis(envelope.ExpiresAt),
	)
	return err
}

func (s *Store) ListPendingEnvelopes(ctx context.Context, recipientDeviceID string, now time.Time, limit int) ([]domain.Envelope, error) {
	rows, err := s.db.QueryContext(
		ctx,
		`SELECT
			id,
			sender_device_id,
			recipient_device_id,
			channel,
			content_type,
			nonce,
			header_aad,
			ciphertext,
			created_at_ms,
			expires_at_ms,
			delivered_at_ms
		FROM envelopes
		WHERE recipient_device_id = ? AND delivered_at_ms IS NULL AND expires_at_ms > ?
		ORDER BY created_at_ms ASC
		LIMIT ?`,
		recipientDeviceID,
		toUnixMillis(now),
		limit,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	envelopes := make([]domain.Envelope, 0, limit)
	for rows.Next() {
		envelope, scanErr := scanEnvelope(rows)
		if scanErr != nil {
			return nil, scanErr
		}

		envelopes = append(envelopes, envelope)
	}

	if err := rows.Err(); err != nil {
		return nil, err
	}

	return envelopes, nil
}

func (s *Store) MarkEnvelopeDelivered(ctx context.Context, envelopeID string, recipientDeviceID string, deliveredAt time.Time) error {
	result, err := s.db.ExecContext(
		ctx,
		`UPDATE envelopes
		SET delivered_at_ms = ?
		WHERE id = ? AND recipient_device_id = ? AND delivered_at_ms IS NULL`,
		toUnixMillis(deliveredAt),
		envelopeID,
		recipientDeviceID,
	)
	if err != nil {
		return err
	}

	return ensureRowsAffected(result)
}

func (s *Store) DeleteExpired(ctx context.Context, now time.Time) (CleanupResult, error) {
	pairingResult, err := s.db.ExecContext(
		ctx,
		`DELETE FROM pairing_sessions WHERE expires_at_ms <= ?`,
		toUnixMillis(now),
	)
	if err != nil {
		return CleanupResult{}, err
	}

	envelopeResult, err := s.db.ExecContext(
		ctx,
		`DELETE FROM envelopes WHERE expires_at_ms <= ? OR delivered_at_ms IS NOT NULL`,
		toUnixMillis(now),
	)
	if err != nil {
		return CleanupResult{}, err
	}

	deletedPairingSessions, err := pairingResult.RowsAffected()
	if err != nil {
		return CleanupResult{}, err
	}

	deletedEnvelopes, err := envelopeResult.RowsAffected()
	if err != nil {
		return CleanupResult{}, err
	}

	return CleanupResult{
		DeletedPairingSessions: deletedPairingSessions,
		DeletedEnvelopes:       deletedEnvelopes,
	}, nil
}

func (s *Store) migrate(ctx context.Context) error {
	if _, err := s.db.ExecContext(ctx, pragmaSQL); err != nil {
		return fmt.Errorf("SQLite pragma 구성을 적용하지 못했어요: %w", err)
	}

	if _, err := s.db.ExecContext(ctx, createDevicesTableSQL+createPairingSessionsTableSQL+createEnvelopesTableSQL+createIndexesSQL); err != nil {
		return fmt.Errorf("SQLite 스키마 마이그레이션을 적용하지 못했어요: %w", err)
	}

	hasPairingConfirmedColumn, err := s.hasColumn(ctx, "devices", "pairing_confirmed_at_ms")
	if err != nil {
		return fmt.Errorf("devices 테이블 컬럼 정보를 확인하지 못했어요: %w", err)
	}

	if !hasPairingConfirmedColumn {
		if _, err := s.db.ExecContext(ctx, `ALTER TABLE devices ADD COLUMN pairing_confirmed_at_ms INTEGER`); err != nil {
			return fmt.Errorf("pairing_confirmed_at_ms 컬럼을 추가하지 못했어요: %w", err)
		}
	}

	needsRebuild, err := s.schemaNeedsRebuild(ctx)
	if err != nil {
		return fmt.Errorf("SQLite 스키마 무결성을 확인하지 못했어요: %w", err)
	}

	if needsRebuild {
		if err := s.rebuildTables(ctx); err != nil {
			return fmt.Errorf("SQLite 스키마를 안전한 정의로 재구성하지 못했어요: %w", err)
		}
	}

	return nil
}

func (s *Store) hasColumn(ctx context.Context, tableName string, columnName string) (bool, error) {
	row := s.db.QueryRowContext(
		ctx,
		`SELECT 1 FROM pragma_table_info(?) WHERE name = ? LIMIT 1`,
		tableName,
		columnName,
	)

	var marker int
	if err := row.Scan(&marker); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return false, nil
		}

		return false, err
	}

	return true, nil
}

func (s *Store) schemaNeedsRebuild(ctx context.Context) (bool, error) {
	for tableName, fragments := range requiredTableDefinitionFragments {
		definition, err := s.tableDefinitionSQL(ctx, tableName)
		if err != nil {
			return false, err
		}

		normalizedDefinition := strings.ToLower(definition)
		for _, fragment := range fragments {
			if !strings.Contains(normalizedDefinition, fragment) {
				return true, nil
			}
		}
	}

	return false, nil
}

func (s *Store) tableDefinitionSQL(ctx context.Context, tableName string) (string, error) {
	row := s.db.QueryRowContext(
		ctx,
		`SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?`,
		tableName,
	)

	var definition sql.NullString
	if err := row.Scan(&definition); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return "", fmt.Errorf("테이블 %q 정의를 찾을 수 없어요", tableName)
		}

		return "", err
	}

	if !definition.Valid {
		return "", fmt.Errorf("테이블 %q 정의가 비어 있어요", tableName)
	}

	return definition.String, nil
}

func (s *Store) rebuildTables(ctx context.Context) (err error) {
	if _, err = s.db.ExecContext(ctx, `PRAGMA foreign_keys = OFF`); err != nil {
		return err
	}

	defer func() {
		if _, pragmaErr := s.db.ExecContext(context.Background(), `PRAGMA foreign_keys = ON`); pragmaErr != nil && err == nil {
			err = pragmaErr
		}
	}()

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}

	defer tx.Rollback()

	statements := []string{
		`ALTER TABLE envelopes RENAME TO envelopes_legacy`,
		`ALTER TABLE pairing_sessions RENAME TO pairing_sessions_legacy`,
		`ALTER TABLE devices RENAME TO devices_legacy`,
		createDevicesTableSQL,
		createPairingSessionsTableSQL,
		createEnvelopesTableSQL,
		`INSERT INTO devices (
			id,
			name,
			platform,
			peer_device_id,
			relay_token_hash,
			pairing_confirmed_at_ms,
			created_at_ms,
			last_seen_at_ms
		)
		SELECT
			id,
			name,
			platform,
			peer_device_id,
			relay_token_hash,
			pairing_confirmed_at_ms,
			created_at_ms,
			last_seen_at_ms
		FROM devices_legacy`,
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
		)
		SELECT
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
		FROM pairing_sessions_legacy`,
		`INSERT INTO envelopes (
			id,
			sender_device_id,
			recipient_device_id,
			channel,
			content_type,
			nonce,
			header_aad,
			ciphertext,
			created_at_ms,
			expires_at_ms,
			delivered_at_ms
		)
		SELECT
			id,
			sender_device_id,
			recipient_device_id,
			channel,
			content_type,
			nonce,
			header_aad,
			ciphertext,
			created_at_ms,
			expires_at_ms,
			delivered_at_ms
		FROM envelopes_legacy`,
		`DROP TABLE envelopes_legacy`,
		`DROP TABLE pairing_sessions_legacy`,
		`DROP TABLE devices_legacy`,
		createIndexesSQL,
	}

	for _, statement := range statements {
		if _, err := tx.ExecContext(ctx, statement); err != nil {
			return err
		}
	}

	if err := tx.Commit(); err != nil {
		return err
	}

	if err := s.validateForeignKeys(ctx); err != nil {
		return err
	}

	return nil
}

func (s *Store) validateForeignKeys(ctx context.Context) error {
	rows, err := s.db.QueryContext(ctx, `PRAGMA foreign_key_check`)
	if err != nil {
		return err
	}
	defer rows.Close()

	if rows.Next() {
		var (
			tableName string
			rowID     int64
			parent    string
			foreignID int64
		)

		if err := rows.Scan(&tableName, &rowID, &parent, &foreignID); err != nil {
			return err
		}

		return fmt.Errorf("foreign key 검증에 실패했어요: table=%s rowid=%d parent=%s fk=%d", tableName, rowID, parent, foreignID)
	}

	return rows.Err()
}

func insertDevice(ctx context.Context, executor interface {
	ExecContext(context.Context, string, ...any) (sql.Result, error)
}, device domain.Device) error {
	_, err := executor.ExecContext(
		ctx,
		`INSERT INTO devices (
			id,
			name,
			platform,
			peer_device_id,
			relay_token_hash,
			pairing_confirmed_at_ms,
			created_at_ms,
			last_seen_at_ms
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		device.ID,
		device.Name,
		string(device.Platform),
		nullableString(device.PeerDeviceID),
		device.RelayTokenHash,
		nullableTimeMillis(device.PairingConfirmedAt),
		toUnixMillis(device.CreatedAt),
		toUnixMillis(device.LastSeenAt),
	)
	return err
}

type rowScanner interface {
	Scan(dest ...any) error
}

func scanPairingSession(scanner rowScanner) (domain.PairingSession, error) {
	var (
		session        domain.PairingSession
		initiatorRaw   string
		joinerRaw      sql.NullString
		stateRaw       string
		expiresAtMS    int64
		createdAtMS    int64
		updatedAtMS    int64
		completedAtMS  sql.NullInt64
		joinerDeviceID sql.NullString
		joinerName     sql.NullString
	)

	if err := scanner.Scan(
		&session.ID,
		&session.InitiatorDeviceID,
		&session.InitiatorName,
		&initiatorRaw,
		&session.InitiatorPublicKey,
		&session.PairingSecretHash,
		&joinerDeviceID,
		&joinerName,
		&joinerRaw,
		&session.JoinerPublicKey,
		&stateRaw,
		&expiresAtMS,
		&createdAtMS,
		&updatedAtMS,
		&completedAtMS,
	); err != nil {
		return domain.PairingSession{}, err
	}

	initiatorPlatform, err := domain.ParsePlatform(initiatorRaw)
	if err != nil {
		return domain.PairingSession{}, err
	}

	session.InitiatorPlatform = initiatorPlatform

	if joinerDeviceID.Valid {
		session.JoinerDeviceID = joinerDeviceID.String
	}

	if joinerName.Valid {
		session.JoinerName = joinerName.String
	}

	if joinerRaw.Valid {
		joinerPlatform, platformErr := domain.ParsePlatform(joinerRaw.String)
		if platformErr != nil {
			return domain.PairingSession{}, platformErr
		}

		session.JoinerPlatform = joinerPlatform
	}

	session.State = domain.PairingState(stateRaw)
	session.ExpiresAt = fromUnixMillis(expiresAtMS)
	session.CreatedAt = fromUnixMillis(createdAtMS)
	session.UpdatedAt = fromUnixMillis(updatedAtMS)
	session.CompletedAt = nullableTime(completedAtMS)

	return session, nil
}

func scanDevice(scanner rowScanner) (domain.Device, error) {
	var (
		device               domain.Device
		platformRaw          string
		peerDeviceID         sql.NullString
		pairingConfirmedAtMS sql.NullInt64
		createdAtMS          int64
		lastSeenAtMS         int64
	)

	if err := scanner.Scan(
		&device.ID,
		&device.Name,
		&platformRaw,
		&peerDeviceID,
		&device.RelayTokenHash,
		&pairingConfirmedAtMS,
		&createdAtMS,
		&lastSeenAtMS,
	); err != nil {
		return domain.Device{}, err
	}

	platform, err := domain.ParsePlatform(platformRaw)
	if err != nil {
		return domain.Device{}, err
	}

	device.Platform = platform
	if peerDeviceID.Valid {
		device.PeerDeviceID = peerDeviceID.String
	}

	device.PairingConfirmedAt = nullableTime(pairingConfirmedAtMS)
	device.CreatedAt = fromUnixMillis(createdAtMS)
	device.LastSeenAt = fromUnixMillis(lastSeenAtMS)

	return device, nil
}

func scanEnvelope(scanner rowScanner) (domain.Envelope, error) {
	var (
		envelope      domain.Envelope
		channelRaw    string
		createdAtMS   int64
		expiresAtMS   int64
		deliveredAtMS sql.NullInt64
	)

	if err := scanner.Scan(
		&envelope.ID,
		&envelope.SenderDeviceID,
		&envelope.RecipientDeviceID,
		&channelRaw,
		&envelope.ContentType,
		&envelope.Nonce,
		&envelope.HeaderAAD,
		&envelope.Ciphertext,
		&createdAtMS,
		&expiresAtMS,
		&deliveredAtMS,
	); err != nil {
		return domain.Envelope{}, err
	}

	channel, err := domain.ParseChannel(channelRaw)
	if err != nil {
		return domain.Envelope{}, err
	}

	envelope.Channel = channel
	envelope.CreatedAt = fromUnixMillis(createdAtMS)
	envelope.ExpiresAt = fromUnixMillis(expiresAtMS)
	envelope.DeliveredAt = nullableTime(deliveredAtMS)

	return envelope, nil
}

func ensureRowsAffected(result sql.Result) error {
	rowsAffected, err := result.RowsAffected()
	if err != nil {
		return err
	}

	if rowsAffected == 0 {
		return sql.ErrNoRows
	}

	return nil
}

func toUnixMillis(value time.Time) int64 {
	return value.UTC().UnixMilli()
}

func fromUnixMillis(value int64) time.Time {
	return time.UnixMilli(value).UTC()
}

func nullableTime(value sql.NullInt64) *time.Time {
	if !value.Valid {
		return nil
	}

	timestamp := fromUnixMillis(value.Int64)
	return &timestamp
}

func nullableString(value string) any {
	if value == "" {
		return nil
	}

	return value
}

func nullableTimeMillis(value *time.Time) any {
	if value == nil {
		return nil
	}

	return toUnixMillis(*value)
}

func IsNotFound(err error) bool {
	return errors.Is(err, sql.ErrNoRows)
}
