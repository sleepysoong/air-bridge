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
		slog.Error("load config", "error", err)
		os.Exit(1)
	}

	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{}))

	if err := os.MkdirAll(filepath.Dir(cfg.DatabasePath), 0o755); err != nil {
		logger.Error("create database directory", "path", filepath.Dir(cfg.DatabasePath), "error", err)
		os.Exit(1)
	}

	store, err := sqlite.Open(cfg.DatabasePath)
	if err != nil {
		logger.Error("open sqlite store", "path", cfg.DatabasePath, "error", err)
		os.Exit(1)
	}
	defer store.Close()

	pairingService := service.NewPairingService(store, cfg.PairingTTL)
	relayService := service.NewRelayService(store, cfg.MessageTTL)
	server := httpapi.NewServer(logger, cfg, pairingService, relayService)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

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
			logger.Error("shutdown relay server", "error", err)
		}
	}()

	logger.Info("relay server starting", "address", cfg.HTTPAddress, "database_path", cfg.DatabasePath)

	if err := httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		logger.Error("listen and serve", "error", err)
		os.Exit(1)
	}
}
