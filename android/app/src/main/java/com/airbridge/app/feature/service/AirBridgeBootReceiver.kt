package com.airbridge.app.feature.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.airbridge.app.app.AirBridgeApplication

class AirBridgeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val application = context.applicationContext as? AirBridgeApplication ?: return
        val container = application.container
        val shouldRecoverRuntime = container.runtimePreferencesStore.isForegroundRuntimeEnabled() &&
            container.relayCredentialStore.read() != null

        if (shouldRecoverRuntime) {
            AirBridgeRelayForegroundService.start(context.applicationContext)
        }
    }
}
