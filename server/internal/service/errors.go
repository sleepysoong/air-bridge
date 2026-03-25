package service

import "errors"

var (
	ErrInvalidInput = errors.New("invalid input")
	ErrNotFound     = errors.New("not found")
	ErrUnauthorized = errors.New("unauthorized")
	ErrConflict     = errors.New("conflict")
	ErrExpired      = errors.New("expired")
)
