package config

import (
	"fmt"
	"os"
	"time"
)

type Config struct {
	HTTPAddress           string
	DatabasePath          string
	PairingTTL            time.Duration
	MessageTTL            time.Duration
	CleanupInterval       time.Duration
	WebSocketWriteTimeout time.Duration
	ShutdownTimeout       time.Duration
}

func Load() (Config, error) {
	cfg := Config{
		HTTPAddress:           getString("AIR_BRIDGE_HTTP_ADDRESS", ":8080"),
		DatabasePath:          getString("AIR_BRIDGE_DATABASE_PATH", "./data/airbridge-relay.db"),
		PairingTTL:            10 * time.Minute,
		MessageTTL:            24 * time.Hour,
		CleanupInterval:       1 * time.Minute,
		WebSocketWriteTimeout: 10 * time.Second,
		ShutdownTimeout:       10 * time.Second,
	}

	var err error

	if cfg.PairingTTL, err = getDuration("AIR_BRIDGE_PAIRING_TTL", cfg.PairingTTL); err != nil {
		return Config{}, err
	}

	if cfg.MessageTTL, err = getDuration("AIR_BRIDGE_MESSAGE_TTL", cfg.MessageTTL); err != nil {
		return Config{}, err
	}

	if cfg.CleanupInterval, err = getDuration("AIR_BRIDGE_CLEANUP_INTERVAL", cfg.CleanupInterval); err != nil {
		return Config{}, err
	}

	if cfg.WebSocketWriteTimeout, err = getDuration("AIR_BRIDGE_WEBSOCKET_WRITE_TIMEOUT", cfg.WebSocketWriteTimeout); err != nil {
		return Config{}, err
	}

	if cfg.ShutdownTimeout, err = getDuration("AIR_BRIDGE_SHUTDOWN_TIMEOUT", cfg.ShutdownTimeout); err != nil {
		return Config{}, err
	}

	return cfg, nil
}

func getString(key string, fallback string) string {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}

	return value
}

func getDuration(key string, fallback time.Duration) (time.Duration, error) {
	value := os.Getenv(key)
	if value == "" {
		return fallback, nil
	}

	duration, err := time.ParseDuration(value)
	if err != nil {
		return 0, fmt.Errorf("parse %s: %w", key, err)
	}

	return duration, nil
}
