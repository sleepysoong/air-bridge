package com.airbridge.app.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.airbridge.app.app.AppContainer
import com.airbridge.app.domain.PendingPairingSession
import com.airbridge.app.domain.StoredRelayCredentials
import com.airbridge.app.feature.common.ClipboardSyncStatus
import com.airbridge.app.feature.service.BridgeRuntimeSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    private var pendingPairingSession: PendingPairingSession? = null

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

    fun preparePairing() {
        val qrPayloadRaw = mutableUiState.value.qrPayload
        val deviceName = mutableUiState.value.deviceName
        viewModelScope.launch {
            mutableUiState.update { it.copy(isBusy = true, errorMessage = null, infoMessage = null) }
            runCatching {
                val qrPayload = parser.parse(qrPayloadRaw)
                container.pairingRepository.preparePairing(qrPayload, deviceName)
            }.onSuccess { pending ->
                pendingPairingSession = pending
                mutableUiState.update {
                    it.copy(
                        isBusy = false,
                        pendingPairing = pending,
                        infoMessage = "SAS 코드를 Mac 화면과 비교한 뒤 완료를 눌러 주세요.",
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

    fun completePairing() {
        val pending = pendingPairingSession ?: return
        viewModelScope.launch {
            mutableUiState.update { it.copy(isBusy = true, errorMessage = null) }
            runCatching {
                container.pairingRepository.completePairing(pending)
            }.onSuccess { credentials ->
                pendingPairingSession = null
                container.bridgeRuntime.startForegroundService()
                container.bridgeRuntime.ensureRunning()
                mutableUiState.update {
                    it.copy(
                        isBusy = false,
                        pendingPairing = null,
                        activeCredentials = credentials,
                        infoMessage = "페어링이 완료되었고 relay 브리지를 시작했어요.",
                    )
                }
            }.onFailure { error ->
                mutableUiState.update {
                    it.copy(
                        isBusy = false,
                        errorMessage = error.message ?: "페어링 완료에 실패했어요.",
                    )
                }
            }
        }
    }

    fun sendClipboardNow() {
        container.clipboardSyncCoordinator.sendCurrentClipboardManually()
        mutableUiState.update { it.copy(infoMessage = "현재 클립보드 전송을 요청했어요.") }
    }

    fun startClipboardMonitoring() {
        container.clipboardSyncCoordinator.startForegroundMonitoring()
    }

    fun stopClipboardMonitoring() {
        container.clipboardSyncCoordinator.stopForegroundMonitoring()
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
    val pendingPairing: PendingPairingSession? = null,
    val activeCredentials: StoredRelayCredentials? = null,
    val notificationAccessGranted: Boolean = false,
    val clipboardStatus: ClipboardSyncStatus = ClipboardSyncStatus(),
    val runtimeSnapshot: BridgeRuntimeSnapshot = BridgeRuntimeSnapshot(),
    val errorMessage: String? = null,
    val infoMessage: String? = null,
)

