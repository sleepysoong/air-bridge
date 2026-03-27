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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

import com.airbridge.app.ui.theme.AirBridgeTheme

class MainActivity : ComponentActivity() {
    private val viewModel: PairingViewModel by viewModels {
        PairingViewModel.factory((application as AirBridgeApplication).container)
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
            val qrScannerLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
                result.contents?.let(viewModel::applyScannedQr)
            }

            LaunchedEffect(Unit) {
                viewModel.updateNotificationAccess(isNotificationAccessGranted())
            }

            AirBridgeTheme {
                PairingScreen(
                    uiState = uiState,
                    onDeviceNameChanged = viewModel::updateDeviceName,
                    onQrPayloadChanged = viewModel::updateQrPayload,
                    onPreparePairing = viewModel::preparePairing,
                    onCompletePairing = viewModel::completePairing,
                    onManualClipboardSend = viewModel::sendClipboardNow,
                    onOpenNotificationAccess = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION.CODES.TIRAMISU) {
                            notificationAccessLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onStartBridge = viewModel::startBridge,
                    onDismissBanner = viewModel::dismissBanner,
                    onScanQr = {
                        qrScannerLauncher.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Mac 화면의 air-bridge QR 코드를 스캔하세요.")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false),
                        )
                    },
                )
            }
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
            val qrScannerLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
                result.contents?.let(viewModel::applyScannedQr)
            }
            val darkScheme = remember {
                darkColorScheme(
                    primary = Color(0xFFF0C77B),
                    secondary = Color(0xFF76B9AF),
                    tertiary = Color(0xFFF48D6A),
                    background = Color(0xFF0E1719),
                    surface = Color(0xFF162126),
                )
            }
            val lightScheme = remember {
                lightColorScheme(
                    primary = Color(0xFF165F5A),
                    secondary = Color(0xFFB76D4F),
                    tertiary = Color(0xFF304E6B),
                    background = Color(0xFFF3ECDE),
                    surface = Color(0xFFFFFBF5),
                )
            }

            LaunchedEffect(Unit) {
                viewModel.updateNotificationAccess(isNotificationAccessGranted())
            }

            MaterialTheme(colorScheme = if (uiState.notificationAccessGranted) darkScheme else lightScheme) {
                PairingScreen(
                    uiState = uiState,
                    onDeviceNameChanged = viewModel::updateDeviceName,
                    onQrPayloadChanged = viewModel::updateQrPayload,
                    onPreparePairing = viewModel::preparePairing,
                    onCompletePairing = viewModel::completePairing,
                    onManualClipboardSend = viewModel::sendClipboardNow,
                    onOpenNotificationAccess = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationAccessLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onStartBridge = viewModel::startBridge,
                    onDismissBanner = viewModel::dismissBanner,
                    onScanQr = {
                        qrScannerLauncher.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Mac 화면의 air-bridge QR 코드를 스캔하세요.")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false),
                        )
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.startClipboardMonitoring()
    }

    override fun onStop() {
        viewModel.stopClipboardMonitoring()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        viewModel.updateNotificationAccess(isNotificationAccessGranted())
    }

    private fun isNotificationAccessGranted(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }
}
