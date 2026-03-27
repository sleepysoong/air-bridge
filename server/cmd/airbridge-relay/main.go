package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

	"github.com/sleepysoong/air-bridge/server/internal/config"
	"github.com/sleepysoong/air-bridge/server/internal/persistence/sqlite"
	"github.com/sleepysoong/air-bridge/server/internal/service"
	"github.com/sleepysoong/air-bridge/server/internal/transport/httpapi"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		slog.Error("설정을 불러오지 못했어요", "error", err)
		os.Exit(1)
	}

	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{}))

	if err := os.MkdirAll(filepath.Dir(cfg.DatabasePath), 0o755); err != nil {
		logger.Error("데이터베이스 디렉터리를 만들지 못했어요", "path", filepath.Dir(cfg.DatabasePath), "error", err)
		os.Exit(1)
	}

	store, err := sqlite.Open(cfg.DatabasePath)
	if err != nil {
		logger.Error("SQLite 저장소를 열지 못했어요", "path", cfg.DatabasePath, "error", err)
		os.Exit(1)
	}
	defer store.Close()

	pairingService := service.NewPairingService(logger, store, cfg.PairingTTL)
	relayService := service.NewRelayService(logger, store, cfg.MessageTTL)
	server := httpapi.NewServer(logger, cfg, pairingService, relayService)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	signalChannel := make(chan os.Signal, 1)
	signal.Notify(signalChannel, os.Interrupt, syscall.SIGTERM)
	defer signal.Stop(signalChannel)

	go func() {
		receivedSignal := <-signalChannel
		logger.Info(
			"종료 신호를 받았어요",
			"signal",
			receivedSignal.String(),
			"address",
			cfg.HTTPAddress,
			"database_path",
			cfg.DatabasePath,
		)
		cancel()
	}()

	go relayService.RunCleanupLoop(ctx, logger, cfg.CleanupInterval)

	httpServer := &http.Server{
		Addr:    cfg.HTTPAddress,
		Handler: server.Handler(),
	}

	go func() {
		<-ctx.Done()

		shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
		defer cancel()

		if err := httpServer.Shutdown(shutdownCtx); err != nil {
			logger.Error("중계 서버를 종료하지 못했어요", "error", err)
			return
		}

		logger.Info("중계 서버를 정상 종료했어요", "address", cfg.HTTPAddress, "database_path", cfg.DatabasePath)
	}()

	logger.Info(
		"중계 서버를 시작해요",
		"address",
		cfg.HTTPAddress,
		"database_path",
		cfg.DatabasePath,
		"pairing_ttl",
		cfg.PairingTTL.String(),
		"message_ttl",
		cfg.MessageTTL.String(),
		"cleanup_interval",
		cfg.CleanupInterval.String(),
	)

	go func() {
		addresses := server.CollectServerAddresses()
		for _, addr := range addresses {
			logger.Info("Mac 앱의 Relay URL로 아래 주소 중 하나를 입력해 주세요", "url", addr)
		}
	}()

	if err := httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		logger.Error("중계 서버 요청을 처리하지 못했어요", "error", err)
		os.Exit(1)
	}
}
