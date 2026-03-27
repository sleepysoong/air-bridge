package com.airbridge.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airbridge.app.app.AirBridgeApplication
import com.airbridge.app.feature.pairing.PairingScreen
import com.airbridge.app.feature.pairing.PairingViewModel
import com.airbridge.app.ui.theme.AirBridgeTheme
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val viewModel: PairingViewModel by viewModels {
        PairingViewModel.factory((application as AirBridgeApplication).container)
    }
    private var lastHandledPairingLink: String? = null

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1001) {
            viewModel.updateShizukuStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val notificationAccessLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                viewModel.updateNotificationAccess(granted || isNotificationAccessGranted())
            }

            LaunchedEffect(Unit) {
                viewModel.updateNotificationAccess(isNotificationAccessGranted())
                viewModel.updateShizukuStatus()
            }

            AirBridgeTheme {
                PairingScreen(
                    uiState = uiState,
                    onDeviceNameChanged = viewModel::updateDeviceName,
                    onManualClipboardSend = viewModel::sendClipboardNow,
                    onRequestShizukuPermission = viewModel::requestShizukuPermission,
                    onOpenNotificationAccess = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationAccessLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onStartBridge = viewModel::startBridge,
                    onDismissBanner = viewModel::dismissBanner,
                )
            }
        }

        handlePairingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
    }

    override fun onStop() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        viewModel.updateNotificationAccess(isNotificationAccessGranted())
        viewModel.updateShizukuStatus()
    }

    private fun isNotificationAccessGranted(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    private fun handlePairingIntent(intent: Intent?) {
        val rawLink = intent?.dataString?.trim().orEmpty()
        if (rawLink.isEmpty() || rawLink == lastHandledPairingLink) {
            return
        }

        lastHandledPairingLink = rawLink
        viewModel.applyScannedQr(rawLink)
    }
}
