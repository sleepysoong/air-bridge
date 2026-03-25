package com.airbridge.app.feature.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.airbridge.app.feature.common.BridgeFeatureRegistry

class AirBridgeRelayForegroundService : Service() {
    private var hasStartedForegroundRuntime = false

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ActionStart, null -> {
                ensureForegroundStarted()
                return START_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        if (hasStartedForegroundRuntime) {
            BridgeFeatureRegistry.foregroundServiceDelegate?.onForegroundServiceStopped()
        }
        super.onDestroy()
    }

    private fun ensureForegroundStarted() {
        if (hasStartedForegroundRuntime) {
            return
        }

        BridgeForegroundNotificationFactory.ensureChannel(this)
        startForeground(
            BridgeForegroundNotificationFactory.NotificationId,
            BridgeForegroundNotificationFactory.build(this),
        )
        hasStartedForegroundRuntime = true
        BridgeFeatureRegistry.foregroundServiceDelegate?.onForegroundServiceStarted(this)
    }

    companion object {
        private const val ActionStart = "com.airbridge.app.action.START_RELAY_RUNTIME"

        fun start(context: Context) {
            val intent = Intent(context, AirBridgeRelayForegroundService::class.java).setAction(ActionStart)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AirBridgeRelayForegroundService::class.java))
        }
    }
}

