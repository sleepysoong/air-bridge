package service

import "errors"

var (
	ErrInvalidInput = errors.New("입력이 올바르지 않아요")
	ErrNotFound     = errors.New("대상을 찾을 수 없어요")
	ErrUnauthorized = errors.New("권한이 없어요")
	ErrConflict     = errors.New("현재 상태에서는 처리할 수 없어요")
	ErrExpired      = errors.New("유효 시간이 지났어요")
)
