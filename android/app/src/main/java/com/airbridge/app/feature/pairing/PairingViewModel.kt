package com.airbridge.app.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.airbridge.app.app.AppContainer
import com.airbridge.app.domain.StoredRelayCredentials
import com.airbridge.app.feature.common.ClipboardSyncStatus
import com.airbridge.app.feature.service.BridgeRuntimeSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class PairingViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val parser = PairingQrParser()
    private val mutableUiState = MutableStateFlow(
        PairingUiState(
            deviceName = container.pairingRepository.defaultDeviceName(),
            activeCredentials = container.pairingRepository.currentCredentials(),
        ),
    )

    val uiState: StateFlow<PairingUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            container.bridgeRuntime.snapshot.collect { runtimeSnapshot ->
                mutableUiState.update { current ->
                    current.copy(runtimeSnapshot = runtimeSnapshot)
                }
            }
        }

        viewModelScope.launch {
            container.clipboardSyncCoordinator.status.collect { clipboardStatus ->
                mutableUiState.update { current ->
                    current.copy(clipboardStatus = clipboardStatus)
                }
            }
        }

        if (mutableUiState.value.activeCredentials != null) {
            container.bridgeRuntime.startForegroundService()
            container.bridgeRuntime.ensureRunning()
        }
    }

    fun updateDeviceName(value: String) {
        mutableUiState.update { it.copy(deviceName = value) }
    }

    fun updateQrPayload(value: String) {
        mutableUiState.update { it.copy(qrPayload = value) }
    }

    fun updateNotificationAccess(isGranted: Boolean) {
        mutableUiState.update { it.copy(notificationAccessGranted = isGranted) }
    }

    fun updateShizukuStatus() {
        val available = Shizuku.pingBinder()
        val granted = available && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        mutableUiState.update { 
            it.copy(
                shizukuAvailable = available,
                shizukuPermissionGranted = granted
            )
        }
    }

    fun requestShizukuPermission() {
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(1001)
        }
    }

    fun preparePairing() {
        val qrPayloadRaw = mutableUiState.value.qrPayload
        val deviceName = mutableUiState.value.deviceName
        viewModelScope.launch {
            mutableUiState.update { it.copy(isBusy = true, errorMessage = null, infoMessage = null) }
            runCatching {
                val qrPayload = parser.parse(qrPayloadRaw)
                container.pairingRepository.preparePairing(qrPayload, deviceName)
            }.onSuccess { credentials ->
                container.bridgeRuntime.startForegroundService()
                container.bridgeRuntime.reloadStoredPairing()
                mutableUiState.update {
                    it.copy(
                        isBusy = false,
                        activeCredentials = credentials,
                        infoMessage = "QR 스캔만으로 페어링을 완료했고 relay 브리지를 시작했어요.",
                    )
                }
            }.onFailure { error ->
                mutableUiState.update {
                    it.copy(
                        isBusy = false,
                        errorMessage = error.message ?: "페어링 준비에 실패했어요.",
                    )
                }
            }
        }
    }

    fun applyScannedQr(rawValue: String) {
        updateQrPayload(rawValue)
        preparePairing()
    }

    fun sendClipboardNow() {
        container.clipboardSyncCoordinator.sendCurrentClipboardManually()
        mutableUiState.update { it.copy(infoMessage = "현재 클립보드 전송을 요청했어요.") }
    }

    fun startBridge() {
        container.bridgeRuntime.startForegroundService()
        container.bridgeRuntime.ensureRunning()
    }

    fun dismissBanner() {
        mutableUiState.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PairingViewModel(container) as T
            }
        }
    }
}

data class PairingUiState(
    val deviceName: String = "",
    val qrPayload: String = "",
    val isBusy: Boolean = false,
    val activeCredentials: StoredRelayCredentials? = null,
    val notificationAccessGranted: Boolean = false,
    val shizukuAvailable: Boolean = false,
    val shizukuPermissionGranted: Boolean = false,
    val clipboardStatus: ClipboardSyncStatus = ClipboardSyncStatus(),
    val runtimeSnapshot: BridgeRuntimeSnapshot = BridgeRuntimeSnapshot(),
    val errorMessage: String? = null,
    val infoMessage: String? = null,
)
