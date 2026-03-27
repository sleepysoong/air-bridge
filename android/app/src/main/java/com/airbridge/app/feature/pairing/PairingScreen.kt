package com.airbridge.app.feature.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

@Composable
fun PairingScreen(
    uiState: PairingUiState,
    onDeviceNameChanged: (String) -> Unit,
    onQrPayloadChanged: (String) -> Unit,
    onPreparePairing: () -> Unit,
    onCompletePairing: () -> Unit,
    onManualClipboardSend: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onStartBridge: () -> Unit,
    onDismissBanner: () -> Unit,
    onScanQr: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeaderSection(uiState)
        BannerSection(uiState.errorMessage ?: uiState.infoMessage, onDismissBanner)
        PairingInputSection(
            uiState = uiState,
            onDeviceNameChanged = onDeviceNameChanged,
            onQrPayloadChanged = onQrPayloadChanged,
            onPreparePairing = onPreparePairing,
            onScanQr = onScanQr,
        )
        PendingPairingSection(uiState, onCompletePairing)
        RuntimeSection(
            uiState = uiState,
            onOpenNotificationAccess = onOpenNotificationAccess,
            onManualClipboardSend = onManualClipboardSend,
            onStartBridge = onStartBridge,
        )
    }
}

@Composable
private fun HeaderSection(uiState: PairingUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Air Bridge",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Mac과 Android 간 암호화된 클립보드/알림 동기화",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "연결 상태: ${if (uiState.runtimeSnapshot.isConnected) "연결됨" else "대기 중"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "알림 접근: ${if (uiState.notificationAccessGranted) "허용됨" else "필요"}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun BannerSection(message: String?, onDismissBanner: () -> Unit) {
    if (message == null) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismissBanner) {
                Text("닫기")
            }
        }
    }
}

@Composable
private fun PairingInputSection(
    uiState: PairingUiState,
    onDeviceNameChanged: (String) -> Unit,
    onQrPayloadChanged: (String) -> Unit,
    onPreparePairing: () -> Unit,
    onScanQr: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "페어링",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = uiState.deviceName,
                onValueChange = onDeviceNameChanged,
                label = { Text("기기 이름") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.qrPayload,
                onValueChange = onQrPayloadChanged,
                label = { Text("QR 내용 또는 URL") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPreparePairing, enabled = !uiState.isBusy) {
                    if (uiState.isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("참여")
                    }
                }
                TextButton(onClick = onScanQr, enabled = !uiState.isBusy) {
                    Text("QR 스캔")
                }
            }
        }
    }
}

@Composable
private fun PendingPairingSection(
    uiState: PairingUiState,
    onCompletePairing: () -> Unit,
) {
    val pending = uiState.pendingPairing ?: return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "SAS 확인",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "기기: ${pending.sessionSnapshot.initiatorName.ifBlank { "Mac" }}",
            )
            Text(
                text = pending.sasCode.chunked(3).joinToString(" "),
                style = MaterialTheme.typography.displayMedium,
            )
            Text(
                text = "Mac에 표시된 6자리 코드와 일치하는지 확인하세요.",
            )
            Button(onClick = onCompletePairing, enabled = !uiState.isBusy) {
                Text("코드 일치, 완료")
            }
        }
    }
}

@Composable
private fun RuntimeSection(
    uiState: PairingUiState,
    onOpenNotificationAccess: () -> Unit,
    onManualClipboardSend: () -> Unit,
    onStartBridge: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "브리지 실행",
                style = MaterialTheme.typography.titleMedium,
            )
            Text("상태: ${uiState.runtimeSnapshot.status}")
            Text("클립보드: ${if (uiState.clipboardStatus.isMonitoring) "감시 중" else "중지"}")
            Text("알림 접근: ${if (uiState.notificationAccessGranted) "허용됨" else "필요"}")
            
            HorizontalDivider()
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onManualClipboardSend,
                    enabled = uiState.activeCredentials != null,
                ) {
                    Text("클립보드 전송")
                }
                TextButton(
                    onClick = onStartBridge,
                    enabled = uiState.activeCredentials != null,
                ) {
                    Text("시작")
                }
            }
            TextButton(onClick = onOpenNotificationAccess) {
                Text("알림 접근 설정")
            }
        }
    }
}

