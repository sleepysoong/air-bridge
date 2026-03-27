package com.airbridge.app.feature.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbridge.app.domain.StoredRelayCredentials
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

private val ScreenBackground = Color(0xFFF4F6F9)
private val SurfaceWhite = Color(0xFFFFFFFF)
private val TextPrimary = Color(0xFF15171A)
private val TextSecondary = Color(0xFF6E737B)
private val AccentBlue = Color(0xFF3D7CFF)
private val AccentMint = Color(0xFF2FB694)
private val AccentOrange = Color(0xFFE28A2F)
private val AccentRed = Color(0xFFD95656)
private val DividerColor = Color(0xFFE7EBF0)
private val CardShape = RoundedCornerShape(28.dp)
private val FieldShape = RoundedCornerShape(22.dp)
private val ActionShape = RoundedCornerShape(24.dp)

@Composable
fun PairingScreen(
    uiState: PairingUiState,
    onDeviceNameChanged: (String) -> Unit,
    onManualClipboardSend: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onStartBridge: () -> Unit,
    onDismissBanner: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
) {
    val backdrop = rememberLayerBackdrop {
        drawRect(ScreenBackground)
        drawContent()
    }
    val hasPairing = uiState.activeCredentials != null

    PopupAlert(
        message = uiState.errorMessage ?: uiState.infoMessage,
        isError = uiState.errorMessage != null,
        onDismiss = onDismissBanner,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (hasPairing) {
                PairedSummaryCard(
                    backdrop = backdrop,
                    uiState = uiState,
                )
            } else {
                PairingReadyCard(
                    backdrop = backdrop,
                    uiState = uiState,
                    onDeviceNameChanged = onDeviceNameChanged,
                )
            }

            RuntimeSummaryCard(
                backdrop = backdrop,
                uiState = uiState,
            )

            NeededActionsCard(
                backdrop = backdrop,
                uiState = uiState,
                onManualClipboardSend = onManualClipboardSend,
                onOpenNotificationAccess = onOpenNotificationAccess,
                onRequestShizukuPermission = onRequestShizukuPermission,
                onStartBridge = onStartBridge,
            )
        }
    }
}

@Composable
private fun PairingReadyCard(
    backdrop: Backdrop,
    uiState: PairingUiState,
    onDeviceNameChanged: (String) -> Unit,
) {
    GlassCard(backdrop = backdrop) {
        SectionEyebrow(text = "페어링")
        Text(
            text = if (uiState.isBusy) "연결하고 있어요" else "QR만 스캔하면 바로 연결해요",
            color = TextPrimary,
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 34.sp,
        )

        DeviceNameField(
            value = uiState.deviceName,
            onValueChange = onDeviceNameChanged,
        )

        if (uiState.isBusy) {
            StatusPill(
                text = "Mac에서 연 QR 링크를 확인하는 중이에요",
                accent = AccentBlue,
                isLoading = true,
            )
        } else {
            CompactInstructionBlock()
        }
    }
}

@Composable
private fun PairedSummaryCard(
    backdrop: Backdrop,
    uiState: PairingUiState,
) {
    val credentials = uiState.activeCredentials ?: return
    val pairedAt = remember(credentials.completedAt) {
        credentials.completedAt?.let { completedAt ->
            runCatching { Instant.parse(completedAt) }.getOrNull()
        }
    }
    val now by produceState(initialValue = Instant.now(), key1 = pairedAt) {
        if (pairedAt == null) {
            value = Instant.now()
            return@produceState
        }
        while (true) {
            value = Instant.now()
            delay(1_000)
        }
    }

    GlassCard(backdrop = backdrop) {
        SectionEyebrow(text = "연결 상태")
        Text(
            text = "이미 연결했어요",
            color = TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )

        SummaryRow(
            label = "연결된 Mac",
            value = credentials.initiatorName.ifBlank { "이름을 아직 확인하지 못했어요" },
        )
        SummaryRow(
            label = "연결한 지",
            value = if (pairedAt != null) {
                formatElapsedDuration(pairedAt, now)
            } else {
                "기록을 아직 읽지 못했어요"
            },
        )
        SummaryRow(
            label = "내 기기 이름",
            value = credentials.localDeviceName,
        )

        StatusPill(
            text = when {
                uiState.runtimeSnapshot.isConnected -> "브리지를 안정적으로 유지하고 있어요"
                uiState.runtimeSnapshot.isServiceRunning -> "브리지는 켜져 있고 다시 연결을 시도하고 있어요"
                else -> "필요하면 아래에서 브리지를 다시 시작할게요"
            },
            accent = if (uiState.runtimeSnapshot.isConnected) AccentMint else AccentOrange,
        )
    }
}

@Composable
private fun RuntimeSummaryCard(
    backdrop: Backdrop,
    uiState: PairingUiState,
) {
    GlassCard(backdrop = backdrop) {
        SectionEyebrow(text = "상태")
        SummaryRow(
            label = "브리지",
            value = uiState.runtimeSnapshot.status,
            trailingAccent = if (uiState.runtimeSnapshot.isConnected) AccentMint else AccentOrange,
        )
        SummaryRow(
            label = "알림 접근",
            value = if (uiState.notificationAccessGranted) "허용했어요" else "허용해야 해요",
            trailingAccent = if (uiState.notificationAccessGranted) AccentMint else AccentOrange,
        )
        if (uiState.shizukuAvailable) {
            SummaryRow(
                label = "Shizuku",
                value = if (uiState.shizukuPermissionGranted) "준비됐어요" else "권한을 허용해야 해요",
                trailingAccent = if (uiState.shizukuPermissionGranted) AccentMint else AccentOrange,
            )
        }
        SummaryRow(
            label = "클립보드 감시",
            value = if (uiState.clipboardStatus.isMonitoring) "동작하고 있어요" else "지금은 멈춰 있어요",
            trailingAccent = if (uiState.clipboardStatus.isMonitoring) AccentMint else AccentOrange,
        )
    }
}

@Composable
private fun NeededActionsCard(
    backdrop: Backdrop,
    uiState: PairingUiState,
    onManualClipboardSend: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onStartBridge: () -> Unit,
) {
    val actions = remember(
        uiState.notificationAccessGranted,
        uiState.shizukuAvailable,
        uiState.shizukuPermissionGranted,
        uiState.activeCredentials,
        uiState.runtimeSnapshot.isServiceRunning,
        uiState.runtimeSnapshot.isConnected,
        uiState.clipboardStatus.isMonitoring,
    ) {
        buildList<NeededAction> {
            if (!uiState.notificationAccessGranted) {
                add(
                    NeededAction(
                        title = "알림 접근을 열어야 해요",
                        button = "알림 접근을 열게요",
                        accent = AccentOrange,
                        onClick = onOpenNotificationAccess,
                    ),
                )
            }
            if (uiState.shizukuAvailable && !uiState.shizukuPermissionGranted) {
                add(
                    NeededAction(
                        title = "Shizuku 권한을 허용해야 해요",
                        button = "Shizuku 권한을 열게요",
                        accent = AccentBlue,
                        onClick = onRequestShizukuPermission,
                    ),
                )
            }
            if (uiState.activeCredentials != null && !uiState.runtimeSnapshot.isServiceRunning) {
                add(
                    NeededAction(
                        title = "브리지를 다시 켜야 해요",
                        button = "브리지를 시작할게요",
                        accent = AccentBlue,
                        onClick = onStartBridge,
                    ),
                )
            }
            if (uiState.activeCredentials != null && !uiState.clipboardStatus.isMonitoring) {
                add(
                    NeededAction(
                        title = "지금 클립보드를 한 번 보낼 수 있어요",
                        button = "클립보드를 보낼게요",
                        accent = AccentMint,
                        onClick = onManualClipboardSend,
                    ),
                )
            }
        }
    }

    if (actions.isEmpty()) {
        return
    }

    GlassCard(backdrop = backdrop) {
        SectionEyebrow(text = "필요한 조치")
        actions.forEachIndexed { index, action ->
            if (index > 0) {
                HorizontalDivider(color = DividerColor)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = action.title,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
                SmallActionButton(
                    text = action.button,
                    accent = action.accent,
                    onClick = action.onClick,
                )
            }
        }
    }
}

@Composable
private fun CompactInstructionBlock() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InstructionRow(number = "1", text = "Mac에서 QR 코드를 열어야 해요")
        InstructionRow(number = "2", text = "Android 카메라로 스캔하면 바로 이 앱으로 들어와요")
        InstructionRow(number = "3", text = "연결이 끝나면 여기서 상태를 바로 확인할게요")
    }
}

@Composable
private fun InstructionRow(number: String, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(AccentBlue.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number,
                color = AccentBlue,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = text,
            color = TextSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun DeviceNameField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = {
            Text(text = "Android 이름")
        },
        supportingText = {
            Text(text = "Mac에서 이 이름으로 보여줄게요")
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        shape = FieldShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AccentBlue.copy(alpha = 0.42f),
            unfocusedBorderColor = DividerColor,
            focusedContainerColor = SurfaceWhite.copy(alpha = 0.76f),
            unfocusedContainerColor = SurfaceWhite.copy(alpha = 0.62f),
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = AccentBlue,
            focusedLabelColor = TextSecondary,
            unfocusedLabelColor = TextSecondary,
            focusedSupportingTextColor = TextSecondary,
            unfocusedSupportingTextColor = TextSecondary,
        ),
    )
}

@Composable
private fun StatusPill(
    text: String,
    accent: Color,
    isLoading: Boolean = false,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(accent.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = accent,
                strokeWidth = 2.dp,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
        }
        Text(
            text = text,
            color = TextPrimary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
    trailingAccent: Color? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            if (trailingAccent != null) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(trailingAccent),
                )
            }
        }
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 24.sp,
        )
    }
}

@Composable
private fun SmallActionButton(
    text: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(ActionShape)
            .background(accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun GlassCard(
    backdrop: Backdrop,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { CardShape },
                effects = {
                    vibrancy()
                    blur(8.dp.toPx())
                    lens(18.dp.toPx(), 24.dp.toPx())
                },
                onDrawSurface = {
                    drawRect(SurfaceWhite.copy(alpha = 0.72f))
                },
            )
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun SectionEyebrow(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun PopupAlert(
    message: String?,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    if (message == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isError) "문제가 있어요" else "안내할게요",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Text(
                text = message,
                color = TextPrimary,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = AccentBlue)) {
                Text(text = "확인할게요", fontWeight = FontWeight.SemiBold)
            }
        },
        containerColor = SurfaceWhite,
        shape = RoundedCornerShape(24.dp),
    )
}

private fun formatElapsedDuration(start: Instant, end: Instant): String {
    val safeDuration = Duration.between(start, end).coerceAtLeast(Duration.ZERO)
    val days = safeDuration.toDays()
    val hours = safeDuration.toHours() % 24
    val minutes = safeDuration.toMinutes() % 60
    val seconds = safeDuration.seconds % 60
    return String.format("%d일 %02d시간 %02d분 %02d초 지났어요", days, hours, minutes, seconds)
}

private data class NeededAction(
    val title: String,
    val button: String,
    val accent: Color,
    val onClick: () -> Unit,
)
