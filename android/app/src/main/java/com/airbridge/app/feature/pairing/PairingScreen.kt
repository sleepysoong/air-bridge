package com.airbridge.app.feature.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF102028),
            Color(0xFF163A38),
            Color(0xFFEEE7D9),
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeaderCard(uiState)
            BannerCard(uiState.errorMessage ?: uiState.infoMessage, onDismissBanner)
            PairingInputCard(
                uiState = uiState,
                onDeviceNameChanged = onDeviceNameChanged,
                onQrPayloadChanged = onQrPayloadChanged,
                onPreparePairing = onPreparePairing,
                onScanQr = onScanQr,
            )
            PendingPairingCard(uiState, onCompletePairing)
            RuntimeCard(
                uiState = uiState,
                onOpenNotificationAccess = onOpenNotificationAccess,
                onManualClipboardSend = onManualClipboardSend,
                onStartBridge = onStartBridge,
            )
            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun HeaderCard(uiState: PairingUiState) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121C21).copy(alpha = 0.92f)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Air Bridge",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                color = Color(0xFFF7F1E4),
            )
            Text(
                text = "Mac 과 Android 사이에서 암호화된 클립보드와 알림 브리지를 유지합니다.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFD3D7CF),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text(if (uiState.runtimeSnapshot.isConnected) "relay connected" else "relay idle") },
                )
                AssistChip(
                    onClick = {},
                    label = { Text(if (uiState.notificationAccessGranted) "notification access on" else "notification access off") },
                )
            }
        }
    }
}

@Composable
private fun BannerCard(message: String?, onDismissBanner: () -> Unit) {
    if (message == null) {
        return
    }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFFEEE7D9),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                color = Color(0xFF1E2622),
            )
            TextButton(onClick = onDismissBanner) {
                Text("닫기")
            }
        }
    }
}

@Composable
private fun PairingInputCard(
    uiState: PairingUiState,
    onDeviceNameChanged: (String) -> Unit,
    onQrPayloadChanged: (String) -> Unit,
    onPreparePairing: () -> Unit,
    onScanQr: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF4EFE3)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Pairing",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = uiState.deviceName,
                onValueChange = onDeviceNameChanged,
                label = { Text("Android 기기 이름") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.qrPayload,
                onValueChange = onQrPayloadChanged,
                label = { Text("QR payload 또는 airbridge URL") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPreparePairing, enabled = !uiState.isBusy) {
                    if (uiState.isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("세션 참여")
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
private fun PendingPairingCard(
    uiState: PairingUiState,
    onCompletePairing: () -> Unit,
) {
    val pending = uiState.pendingPairing ?: return
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF103A36)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "SAS 확인",
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFFF8F2E2),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = pending.sessionSnapshot.initiatorName.ifBlank { "Mac" },
                color = Color(0xFFD7E7DE),
            )
            Text(
                text = pending.sasCode.chunked(3).joinToString(" "),
                style = MaterialTheme.typography.displaySmall,
                color = Color(0xFFF7D28B),
                fontWeight = FontWeight.Black,
            )
            Text(
                text = "Mac 에 표시된 6자리 코드와 같으면 완료를 눌러 relay 연결을 활성화하세요.",
                color = Color(0xFFD7E7DE),
            )
            Button(onClick = onCompletePairing, enabled = !uiState.isBusy) {
                Text("코드 일치, 페어링 완료")
            }
        }
    }
}

@Composable
private fun RuntimeCard(
    uiState: PairingUiState,
    onOpenNotificationAccess: () -> Unit,
    onManualClipboardSend: () -> Unit,
    onStartBridge: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF6D9A8)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Bridge Runtime",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text("상태: ${uiState.runtimeSnapshot.status}")
            Text("클립보드 감시: ${if (uiState.clipboardStatus.isMonitoring) "foreground on" else "foreground off"}")
            Text("Notification access: ${if (uiState.notificationAccessGranted) "granted" else "required"}")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onManualClipboardSend, enabled = uiState.activeCredentials != null) {
                    Text("지금 클립보드 보내기")
                }
                TextButton(onClick = onStartBridge, enabled = uiState.activeCredentials != null) {
                    Text("브리지 시작")
                }
            }
            TextButton(onClick = onOpenNotificationAccess) {
                Text("알림 접근 설정 열기")
            }
        }
    }
}

