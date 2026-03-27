package com.airbridge.app.feature.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
            .background(Color.White)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Air Bridge",
            color = Color.Black,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Mac과 Android 간 암호화된 클립보드/알림 동기화",
            color = Color.Black,
            fontSize = 14.sp,
        )
        Text(
            text = "연결 상태: ${if (uiState.runtimeSnapshot.isConnected) "연결됨" else "대기 중"}",
            color = Color.Black,
            fontSize = 12.sp,
        )
        Text(
            text = "알림 접근: ${if (uiState.notificationAccessGranted) "허용됨" else "필요"}",
            color = Color.Black,
            fontSize = 12.sp,
        )
        HorizontalDivider(color = Color.Black, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun BannerSection(message: String?, onDismissBanner: () -> Unit) {
    if (message == null) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.Black)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = message,
            color = Color.Black,
            fontSize = 14.sp,
        )
        SolidButton(text = "닫기", onClick = onDismissBanner)
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "페어링",
            color = Color.Black,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        SimpleTextField(
            value = uiState.deviceName,
            onValueChange = onDeviceNameChanged,
            label = "기기 이름",
            singleLine = true,
        )
        SimpleTextField(
            value = uiState.qrPayload,
            onValueChange = onQrPayloadChanged,
            label = "QR 내용 또는 URL",
            minLines = 3,
            singleLine = false,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onPreparePairing,
                enabled = !uiState.isBusy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black,
                    contentColor = Color.White,
                    disabledContainerColor = Color.LightGray,
                    disabledContentColor = Color.White,
                ),
                shape = RectangleShape,
            ) {
                if (uiState.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Text("참여")
                }
            }
            SolidButton(text = "QR 스캔", onClick = onScanQr, enabled = !uiState.isBusy)
        }
        HorizontalDivider(color = Color.Black, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun PendingPairingSection(
    uiState: PairingUiState,
    onCompletePairing: () -> Unit,
) {
    val pending = uiState.pendingPairing ?: return

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "확인 코드 (선택)",
            color = Color.Black,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "기기: ${pending.sessionSnapshot.initiatorName.ifBlank { "Mac" }}",
            color = Color.Black,
        )
        Text(
            text = pending.sasCode.chunked(3).joinToString(" "),
            color = Color.Black,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "QR로 이미 연결 정보는 전달됐어요. 필요하면 Mac의 6자리 코드와 비교한 뒤 완료하세요.",
            color = Color.Black,
        )
        SolidButton(text = "페어링 완료", onClick = onCompletePairing, enabled = !uiState.isBusy)
        HorizontalDivider(color = Color.Black, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun RuntimeSection(
    uiState: PairingUiState,
    onOpenNotificationAccess: () -> Unit,
    onManualClipboardSend: () -> Unit,
    onStartBridge: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "브리지 실행",
            color = Color.Black,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Text("상태: ${uiState.runtimeSnapshot.status}", color = Color.Black)
        Text("클립보드: ${if (uiState.clipboardStatus.isMonitoring) "감시 중" else "중지"}", color = Color.Black)
        Text("알림 접근: ${if (uiState.notificationAccessGranted) "허용됨" else "필요"}", color = Color.Black)
        
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SolidButton(
                text = "클립보드 전송",
                onClick = onManualClipboardSend,
                enabled = uiState.activeCredentials != null,
            )
            SolidButton(
                text = "시작",
                onClick = onStartBridge,
                enabled = uiState.activeCredentials != null,
            )
        }
        SolidButton(text = "알림 접근 설정", onClick = onOpenNotificationAccess)
    }
}

@Composable
private fun SimpleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    singleLine: Boolean,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = Color.Black) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        singleLine = singleLine,
        minLines = minLines,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Black,
            unfocusedBorderColor = Color.Black,
            focusedTextColor = Color.Black,
            unfocusedTextColor = Color.Black,
            cursorColor = Color.Black,
        ),
        shape = RectangleShape,
    )
}

@Composable
private fun SolidButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Black,
            contentColor = Color.White,
            disabledContainerColor = Color.LightGray,
            disabledContentColor = Color.White,
        ),
        shape = RectangleShape,
    ) {
        Text(text)
    }
}
