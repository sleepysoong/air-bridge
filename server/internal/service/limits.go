package service

import (
	"fmt"
	"strings"
	"unicode/utf8"
)

const (
	MaxDeviceNameRunes        = 128
	MaxPairingSecretRunes     = 128
	MaxContentTypeBytes       = 255
	MaxNonceBytes             = 64
	MaxHeaderAADBytes         = 16 * 1024
	MaxNormalizedPayloadBytes = 20 * 1024 * 1024
	MaxCiphertextBytes        = MaxNormalizedPayloadBytes + 16
)

func validateDeviceName(raw string, subject string) (string, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return "", fmt.Errorf("%w: %s 이름은 비어 있으면 안 돼요", ErrInvalidInput, subject)
	}

	if utf8.RuneCountInString(trimmed) > MaxDeviceNameRunes {
		return "", fmt.Errorf("%w: %s 이름은 %d자를 넘기면 안 돼요", ErrInvalidInput, subject, MaxDeviceNameRunes)
	}

	return trimmed, nil
}

func validatePairingSecret(raw string) (string, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return "", fmt.Errorf("%w: 페어링 비밀값은 비어 있으면 안 돼요", ErrInvalidInput)
	}

	if utf8.RuneCountInString(trimmed) > MaxPairingSecretRunes {
		return "", fmt.Errorf("%w: 페어링 비밀값 길이가 서버 제한을 초과했어요", ErrInvalidInput)
	}

	return trimmed, nil
}

func validateContentType(raw string) (string, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return "", fmt.Errorf("%w: 콘텐츠 타입 값은 비어 있으면 안 돼요", ErrInvalidInput)
	}

	if len(trimmed) > MaxContentTypeBytes {
		return "", fmt.Errorf("%w: 콘텐츠 타입 길이가 서버 제한을 초과했어요", ErrInvalidInput)
	}

	return trimmed, nil
}

func validateOpaqueBytes(value []byte, fieldLabel string, maxBytes int) error {
	if len(value) == 0 {
		return fmt.Errorf("%w: %s 값은 비어 있으면 안 돼요", ErrInvalidInput, fieldLabel)
	}

	if len(value) > maxBytes {
		return fmt.Errorf("%w: %s 크기가 서버 제한을 초과했어요", ErrInvalidInput, fieldLabel)
	}

	return nil
}
