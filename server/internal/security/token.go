package security

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
)

func NewOpaqueToken(prefix string, randomBytes int) (string, error) {
	buffer := make([]byte, randomBytes)
	if _, err := rand.Read(buffer); err != nil {
		return "", err
	}

	return prefix + "_" + base64.RawURLEncoding.EncodeToString(buffer), nil
}

func NewID(prefix string) (string, error) {
	return NewOpaqueToken(prefix, 12)
}

func HashOpaqueToken(token string) []byte {
	sum := sha256.Sum256([]byte(token))
	return append([]byte(nil), sum[:]...)
}
