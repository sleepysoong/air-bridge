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
			updated_at_ms = ?
		WHERE id = ? AND state = ?`,
		joinerDevice.ID,
		session.JoinerName,
		string(session.JoinerPlatform),
		session.JoinerPublicKey,
		string(domain.PairingStateReady),
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

	return tx.Commit()
}

func (s *Store) CompletePairingSession(ctx context.Context, session domain.PairingSession, completedAt time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}

	defer tx.Rollback()

	result, err := tx.ExecContext(
		ctx,
		`UPDATE pairing_sessions
		SET state = ?, updated_at_ms = ?, completed_at_ms = ?
		WHERE id = ? AND state = ?`,
		string(domain.PairingStateCompleted),
		toUnixMillis(completedAt),
		toUnixMillis(completedAt),
		session.ID,
		string(domain.PairingStateReady),
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
		toUnixMillis(completedAt),
		toUnixMillis(completedAt),
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
	const schema = `
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;

CREATE TABLE IF NOT EXISTS devices (
	id TEXT PRIMARY KEY,
	name TEXT NOT NULL,
	platform TEXT NOT NULL,
	peer_device_id TEXT,
	relay_token_hash BLOB NOT NULL,
	pairing_confirmed_at_ms INTEGER,
	created_at_ms INTEGER NOT NULL,
	last_seen_at_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS pairing_sessions (
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

CREATE TABLE IF NOT EXISTS envelopes (
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

CREATE INDEX IF NOT EXISTS idx_devices_peer_device_id ON devices(peer_device_id);
CREATE INDEX IF NOT EXISTS idx_pairing_sessions_expires_at_ms ON pairing_sessions(expires_at_ms);
CREATE INDEX IF NOT EXISTS idx_envelopes_recipient_device_id_created_at_ms ON envelopes(recipient_device_id, created_at_ms);
CREATE INDEX IF NOT EXISTS idx_envelopes_expires_at_ms ON envelopes(expires_at_ms);
`

	if _, err := s.db.ExecContext(ctx, schema); err != nil {
		return fmt.Errorf("run sqlite schema migration: %w", err)
	}

	if _, err := s.db.ExecContext(ctx, `ALTER TABLE devices ADD COLUMN pairing_confirmed_at_ms INTEGER`); err != nil {
		if !strings.Contains(err.Error(), "duplicate column name") {
			return fmt.Errorf("add pairing_confirmed_at_ms column: %w", err)
		}
	}

	return nil
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
