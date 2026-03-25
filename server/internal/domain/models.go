package domain

import (
	"fmt"
	"time"
)

const X25519PublicKeyLength = 32

type Platform string

const (
	PlatformMacOS   Platform = "macos"
	PlatformAndroid Platform = "android"
)

func ParsePlatform(raw string) (Platform, error) {
	switch Platform(raw) {
	case PlatformMacOS, PlatformAndroid:
		return Platform(raw), nil
	default:
		return "", fmt.Errorf("지원하지 않는 플랫폼 값이에요: %q", raw)
	}
}

type PairingState string

const (
	PairingStatePending   PairingState = "pending"
	PairingStateReady     PairingState = "ready"
	PairingStateCompleted PairingState = "completed"
)

type Channel string

const (
	ChannelClipboard    Channel = "clipboard"
	ChannelNotification Channel = "notification"
)

func ParseChannel(raw string) (Channel, error) {
	switch Channel(raw) {
	case ChannelClipboard, ChannelNotification:
		return Channel(raw), nil
	default:
		return "", fmt.Errorf("지원하지 않는 채널 값이에요: %q", raw)
	}
}

type Device struct {
	ID                 string
	Name               string
	Platform           Platform
	PeerDeviceID       string
	RelayTokenHash     []byte
	PairingConfirmedAt *time.Time
	CreatedAt          time.Time
	LastSeenAt         time.Time
}

type PairingSession struct {
	ID                 string
	InitiatorDeviceID  string
	InitiatorName      string
	InitiatorPlatform  Platform
	InitiatorPublicKey []byte
	PairingSecretHash  []byte
	JoinerDeviceID     string
	JoinerName         string
	JoinerPlatform     Platform
	JoinerPublicKey    []byte
	State              PairingState
	ExpiresAt          time.Time
	CreatedAt          time.Time
	UpdatedAt          time.Time
	CompletedAt        *time.Time
}

type Envelope struct {
	ID                string
	SenderDeviceID    string
	RecipientDeviceID string
	Channel           Channel
	ContentType       string
	Nonce             []byte
	HeaderAAD         []byte
	Ciphertext        []byte
	CreatedAt         time.Time
	ExpiresAt         time.Time
	DeliveredAt       *time.Time
}
