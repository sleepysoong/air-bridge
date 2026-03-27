package com.airbridge.app.feature.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

private val ScreenBackgroundTop = Color(0xFFEFF6FF)
private val ScreenBackgroundBottom = Color(0xFFF9FBFF)
private val AccentBlue = Color(0xFF5A8CFF)
private val AccentMint = Color(0xFF7DE7D4)
private val AccentLavender = Color(0xFFA99BFF)
private val SurfaceTextPrimary = Color(0xFF132033)
private val SurfaceTextSecondary = Color(0xFF5D6A80)
private val GlassBorder = Color.White.copy(alpha = 0.62f)
private val GlassSurface = Color.White.copy(alpha = 0.28f)
private val GlassSurfaceStrong = Color.White.copy(alpha = 0.42f)
private val CardShape = RoundedCornerShape(28.dp)
private val FieldShape = RoundedCornerShape(22.dp)
private val ButtonShape = RoundedCornerShape(999.dp)

@Composable
fun PairingScreen(
    uiState: PairingUiState,
    onDeviceNameChanged: (String) -> Unit,
    onQrPayloadChanged: (String) -> Unit,
    onPreparePairing: () -> Unit,
    onManualClipboardSend: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onStartBridge: () -> Unit,
    onDismissBanner: () -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    PopupAlert(
        message = uiState.errorMessage ?: uiState.infoMessage,
        isError = uiState.errorMessage != null,
        onDismiss = onDismissBanner,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBackgroundBottom),
    ) {
        LiquidGlassBackground(backdrop = backdrop)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            PairingInputSection(
                backdrop = backdrop,
                uiState = uiState,
                onDeviceNameChanged = onDeviceNameChanged,
                onQrPayloadChanged = onQrPayloadChanged,
                onPreparePairing = onPreparePairing,
            )
            RuntimeSection(
                backdrop = backdrop,
                uiState = uiState,
                onOpenNotificationAccess = onOpenNotificationAccess,
                onManualClipboardSend = onManualClipboardSend,
                onStartBridge = onStartBridge,
            )
        }
    }
}

@Composable
private fun LiquidGlassBackground(backdrop: LayerBackdrop) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .layerBackdrop(backdrop)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(ScreenBackgroundTop, ScreenBackgroundBottom),
                ),
            ),
    ) {
        GlassOrb(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 8.dp, top = 48.dp),
            size = 220.dp,
            colors = listOf(AccentMint.copy(alpha = 0.55f), Color.Transparent),
        )
        GlassOrb(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 12.dp, top = 12.dp),
            size = 240.dp,
            colors = listOf(AccentBlue.copy(alpha = 0.42f), Color.Transparent),
        )
        GlassOrb(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 32.dp, bottom = 96.dp),
            size = 180.dp,
            colors = listOf(AccentLavender.copy(alpha = 0.34f), Color.Transparent),
        )
    }
}

@Composable
private fun GlassOrb(
    modifier: Modifier,
    size: androidx.compose.ui.unit.Dp,
    colors: List<Color>,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.radialGradient(colors = colors)),
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
            Text(if (isError) "문제가 있어요" else "안내", color = SurfaceTextPrimary, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Text(message, color = SurfaceTextSecondary, lineHeight = 20.sp)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("확인", color = AccentBlue, fontWeight = FontWeight.SemiBold)
            }
        },
        containerColor = Color(0xFFF8FBFF),
    )
}

@Composable
private fun PairingInputSection(
    backdrop: Backdrop,
    uiState: PairingUiState,
    onDeviceNameChanged: (String) -> Unit,
    onQrPayloadChanged: (String) -> Unit,
    onPreparePairing: () -> Unit,
) {
    GlassPanel(backdrop = backdrop) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionTitle(
                title = "페어링",
                subtitle = "카메라 앱으로 QR을 찍으면 자동으로 들어와요. 필요하면 링크를 직접 붙여 넣어도 돼요.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusChip(
                    label = if (uiState.runtimeSnapshot.isConnected) "연결됨" else "대기 중",
                    accent = if (uiState.runtimeSnapshot.isConnected) AccentMint else AccentBlue,
                )
                StatusChip(
                    label = if (uiState.notificationAccessGranted) "알림 허용됨" else "알림 필요",
                    accent = if (uiState.notificationAccessGranted) AccentBlue else AccentLavender,
                )
            }
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
                minLines = 4,
                singleLine = false,
            )
            GlassButton(
                backdrop = backdrop,
                text = if (uiState.isBusy) "연결 중" else "링크로 연결",
                onClick = onPreparePairing,
                enabled = !uiState.isBusy,
                accent = AccentBlue,
                leading = {
                    if (uiState.isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun RuntimeSection(
    backdrop: Backdrop,
    uiState: PairingUiState,
    onOpenNotificationAccess: () -> Unit,
    onManualClipboardSend: () -> Unit,
    onStartBridge: () -> Unit,
) {
    GlassPanel(backdrop = backdrop, accent = AccentMint) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionTitle(
                title = "브리지 실행",
                subtitle = "클립보드 감시와 알림 브리지를 원하는 순간에 깨끗하게 시작할 수 있어요.",
            )

            MetricRow(label = "상태", value = uiState.runtimeSnapshot.status)
            MetricRow(label = "클립보드", value = if (uiState.clipboardStatus.isMonitoring) "감시 중" else "중지")
            MetricRow(label = "알림 접근", value = if (uiState.notificationAccessGranted) "허용됨" else "필요")

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(
                    backdrop = backdrop,
                    text = "클립보드 전송",
                    onClick = onManualClipboardSend,
                    enabled = uiState.activeCredentials != null,
                    accent = AccentLavender,
                    modifier = Modifier.weight(1f),
                )
                GlassButton(
                    backdrop = backdrop,
                    text = "시작",
                    onClick = onStartBridge,
                    enabled = uiState.activeCredentials != null,
                    accent = AccentBlue,
                    modifier = Modifier.weight(1f),
                )
            }

            GlassButton(
                backdrop = backdrop,
                text = "알림 접근 설정",
                onClick = onOpenNotificationAccess,
                accent = AccentMint,
            )
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    subtitle: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            color = SurfaceTextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            color = SurfaceTextSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FieldShape)
            .background(Color.White.copy(alpha = 0.2f))
            .border(1.dp, Color.White.copy(alpha = 0.38f), FieldShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = SurfaceTextSecondary, fontSize = 13.sp)
        Text(text = value, color = SurfaceTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatusChip(
    label: String,
    accent: Color,
) {
    Box(
        modifier = Modifier
            .clip(ButtonShape)
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, Color.White.copy(alpha = 0.55f), ButtonShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = SurfaceTextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun GlassPanel(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    accent: Color = AccentBlue,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { CardShape },
                effects = {
                    vibrancy()
                    blur(10.dp.toPx())
                    lens(20.dp.toPx(), 28.dp.toPx())
                },
                onDrawSurface = {
                    drawRect(GlassSurfaceStrong)
                    drawRect(accent.copy(alpha = 0.08f), blendMode = BlendMode.Hue)
                    drawRect(accent.copy(alpha = 0.08f))
                },
            )
            .border(1.dp, GlassBorder, CardShape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
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
        label = { Text(label, color = SurfaceTextSecondary) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        singleLine = singleLine,
        minLines = minLines,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.White.copy(alpha = 0.8f),
            unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
            focusedTextColor = SurfaceTextPrimary,
            unfocusedTextColor = SurfaceTextPrimary,
            cursorColor = AccentBlue,
            focusedContainerColor = GlassSurface,
            unfocusedContainerColor = GlassSurface,
            focusedLabelColor = SurfaceTextSecondary,
            unfocusedLabelColor = SurfaceTextSecondary,
        ),
        shape = FieldShape,
    )
}

@Composable
private fun GlassButton(
    backdrop: Backdrop,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = AccentBlue,
    fillWidth: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val buttonModifier = if (fillWidth) modifier.fillMaxWidth() else modifier

    Row(
        modifier = buttonModifier
            .height(52.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { ButtonShape },
                effects = {
                    blur(6.dp.toPx())
                    lens(14.dp.toPx(), 18.dp.toPx())
                },
                onDrawSurface = {
                    val base = if (enabled) accent else Color.LightGray
                    drawRect(base.copy(alpha = if (enabled) 0.78f else 0.42f))
                    drawRect(Color.White.copy(alpha = if (enabled) 0.18f else 0.08f))
                },
            )
            .border(1.dp, Color.White.copy(alpha = if (enabled) 0.55f else 0.24f), ButtonShape)
            .clip(ButtonShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        if (leading != null) {
            Box(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
