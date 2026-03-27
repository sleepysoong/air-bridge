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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

private val ScreenBackground = Color(0xFFF4F4F8)
private val AccentBlue = Color(0xFF007AFF)
private val AccentMint = Color(0xFF34C759)
private val AccentOrange = Color(0xFFFF9F0A)
private val AccentRed = Color(0xFFFF453A)
private val TextPrimary = Color(0xFF111114)
private val TextSecondary = Color(0xFF6D6D72)
private val DividerColor = Color.White.copy(alpha = 0.44f)
private val CardShape = RoundedCornerShape(28.dp)
private val FieldShape = RoundedCornerShape(20.dp)
private val TabShape = RoundedCornerShape(32.dp)
private val ButtonShape = RoundedCornerShape(24.dp)

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
    var selectedTab by remember { mutableIntStateOf(0) }

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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ScreenBackground),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .padding(bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            HeaderSection()
            if (selectedTab == 0) {
                PairingTab(
                    backdrop = backdrop,
                    uiState = uiState,
                    onDeviceNameChanged = onDeviceNameChanged,
                )
            } else {
                RuntimeTab(
                    backdrop = backdrop,
                    uiState = uiState,
                    onManualClipboardSend = onManualClipboardSend,
                    onOpenNotificationAccess = onOpenNotificationAccess,
                    onStartBridge = onStartBridge,
                    onRequestShizukuPermission = onRequestShizukuPermission,
                )
            }
        }

        FloatingTabBar(
            backdrop = backdrop,
            selectedTab = selectedTab,
            onSelectTab = { selectedTab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp, vertical = 24.dp),
        )
    }
}

@Composable
private fun HeaderSection() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Air Bridge",
            color = TextPrimary,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Mac에서 QR 코드를 띄우고 Android 카메라로 스캔하면 이 앱이 열리면서 바로 연결해요.",
            color = TextSecondary,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
    }
}

@Composable
private fun PairingTab(
    backdrop: Backdrop,
    uiState: PairingUiState,
    onDeviceNameChanged: (String) -> Unit,
) {
    GlassPanel(backdrop = backdrop, accent = AccentBlue) {
        SectionHeader(
            eyebrow = "연결 준비",
            title = if (uiState.activeCredentials != null) "이미 연결했어요" else "QR 스캔만으로 연결해요",
            description = if (uiState.activeCredentials != null) {
                "현재 기기 정보는 저장해 두었고, 다시 스캔하면 새 페어링으로 이어져요."
            } else {
                "링크를 직접 입력하지 않아도 돼요. Mac 화면의 QR 코드를 스캔해야 해요."
            },
        )

        DeviceNameField(
            value = uiState.deviceName,
            onValueChange = onDeviceNameChanged,
        )

        if (uiState.isBusy) {
            BusyGlassChip(text = "QR 링크를 확인하고 연결하는 중이에요")
        } else {
            PairingInstructionList()
        }
    }

    GlassPanel(backdrop = backdrop, accent = AccentMint) {
        SectionHeader(
            eyebrow = "현재 상태",
            title = "바로 확인할 수 있어요",
            description = "QR을 스캔하기 전에도 필요한 상태를 먼저 확인해 두면 좋아요.",
        )

        StatusRow(
            label = "브리지 상태",
            value = uiState.runtimeSnapshot.status,
            accent = if (uiState.runtimeSnapshot.isConnected) AccentMint else AccentOrange,
            isLast = false,
        )
        StatusRow(
            label = "알림 접근",
            value = if (uiState.notificationAccessGranted) "허용했어요" else "허용해야 해요",
            accent = if (uiState.notificationAccessGranted) AccentMint else AccentOrange,
            isLast = !uiState.shizukuAvailable,
        )
        if (uiState.shizukuAvailable) {
            StatusRow(
                label = "Shizuku",
                value = if (uiState.shizukuPermissionGranted) "준비됐어요" else "권한이 필요해요",
                accent = if (uiState.shizukuPermissionGranted) AccentMint else AccentOrange,
                isLast = true,
            )
        }
    }
}

@Composable
private fun RuntimeTab(
    backdrop: Backdrop,
    uiState: PairingUiState,
    onManualClipboardSend: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onStartBridge: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
) {
    GlassPanel(backdrop = backdrop, accent = AccentBlue) {
        SectionHeader(
            eyebrow = "브리지 제어",
            title = "필요할 때 바로 실행해요",
            description = "연결과 권한 상태를 보고 필요한 작업만 빠르게 실행할 수 있어요.",
        )

        StatusRow(
            label = "브리지 상태",
            value = uiState.runtimeSnapshot.status,
            accent = if (uiState.runtimeSnapshot.isConnected) AccentMint else AccentOrange,
            isLast = false,
        )
        StatusRow(
            label = "클립보드 감시",
            value = if (uiState.clipboardStatus.isMonitoring) "감시하고 있어요" else "아직 멈춰 있어요",
            accent = if (uiState.clipboardStatus.isMonitoring) AccentMint else AccentOrange,
            isLast = false,
        )
        StatusRow(
            label = "알림 접근",
            value = if (uiState.notificationAccessGranted) "허용했어요" else "허용해야 해요",
            accent = if (uiState.notificationAccessGranted) AccentMint else AccentOrange,
            isLast = !uiState.shizukuAvailable,
        )
        if (uiState.shizukuAvailable) {
            StatusRow(
                label = "Shizuku",
                value = if (uiState.shizukuPermissionGranted) "백그라운드 동기화를 준비했어요" else "권한을 허용해야 해요",
                accent = if (uiState.shizukuPermissionGranted) AccentMint else AccentOrange,
                isLast = true,
            )
        }
    }

    GlassPanel(backdrop = backdrop, accent = AccentOrange) {
        SectionHeader(
            eyebrow = "빠른 동작",
            title = "필요한 작업만 눌러서 진행해요",
            description = "아직 연결하지 않았다면 먼저 QR을 스캔해야 해요.",
        )

        GlassActionButton(
            backdrop = backdrop,
            text = "브리지를 시작해요",
            accent = AccentBlue,
            enabled = uiState.activeCredentials != null,
            onClick = onStartBridge,
        )
        GlassActionButton(
            backdrop = backdrop,
            text = "지금 클립보드를 보내요",
            accent = AccentMint,
            enabled = uiState.activeCredentials != null,
            onClick = onManualClipboardSend,
        )
        GlassActionButton(
            backdrop = backdrop,
            text = "알림 접근을 열어요",
            accent = AccentOrange,
            onClick = onOpenNotificationAccess,
        )
        if (uiState.shizukuAvailable && !uiState.shizukuPermissionGranted) {
            GlassActionButton(
                backdrop = backdrop,
                text = "Shizuku 권한을 허용해요",
                accent = AccentBlue,
                onClick = onRequestShizukuPermission,
            )
        }
    }
}

@Composable
private fun PairingInstructionList() {
    InstructionRow(number = "1", title = "Mac에서 QR 코드를 띄워요", description = "Mac 앱의 페어링 화면을 열면 Android용 QR 코드가 보여야 해요.")
    InstructionRow(number = "2", title = "Android 카메라로 스캔해요", description = "QR 코드를 인식하면 이 앱이 deeplink로 열리면서 연결을 시작해요.")
    InstructionRow(number = "3", title = "연결이 끝날 때까지 잠깐 기다려요", description = "정상적으로 끝나면 브리지가 바로 시작되고 상태가 바뀌어요.")
}

@Composable
private fun InstructionRow(number: String, title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(AccentBlue.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = number, color = AccentBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(text = description, color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
        }
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
            Text(text = "기기 이름", color = TextSecondary)
        },
        supportingText = {
            Text(text = "Mac에서 이 이름으로 보여야 해요.", color = TextSecondary)
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        shape = FieldShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.White.copy(alpha = 0.86f),
            unfocusedBorderColor = Color.White.copy(alpha = 0.48f),
            focusedContainerColor = Color.White.copy(alpha = 0.24f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.18f),
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
private fun BusyGlassChip(text: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.42f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentBlue)
        Text(text = text, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun GlassPanel(
    backdrop: Backdrop,
    accent: Color,
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
                    blur(10.dp.toPx())
                    lens(20.dp.toPx(), 28.dp.toPx())
                },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 0.34f))
                    drawRect(accent.copy(alpha = 0.10f), blendMode = BlendMode.Hue)
                    drawRect(accent.copy(alpha = 0.04f))
                },
            )
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun SectionHeader(
    eyebrow: String,
    title: String,
    description: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = eyebrow, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(text = title, color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(text = description, color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    accent: Color,
    isLast: Boolean,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, color = TextPrimary, fontSize = 15.sp)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
                Text(text = value, color = TextSecondary, fontSize = 14.sp)
            }
        }
        if (!isLast) {
            HorizontalDivider(color = DividerColor)
        }
    }
}

@Composable
private fun GlassActionButton(
    backdrop: Backdrop,
    text: String,
    accent: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { ButtonShape },
                effects = {
                    blur(6.dp.toPx())
                    lens(14.dp.toPx(), 18.dp.toPx())
                },
                onDrawSurface = {
                    val base = if (enabled) accent else TextSecondary
                    drawRect(base.copy(alpha = if (enabled) 0.88f else 0.35f))
                    drawRect(Color.White.copy(alpha = if (enabled) 0.12f else 0.06f))
                },
            )
            .clip(ButtonShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FloatingTabBar(
    backdrop: Backdrop,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { TabShape },
                effects = {
                    vibrancy()
                    blur(8.dp.toPx())
                    lens(24.dp.toPx(), 24.dp.toPx())
                },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 0.46f))
                },
            )
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FloatingTabButton(
            icon = Icons.Default.Home,
            label = "연결",
            selected = selectedTab == 0,
            accent = AccentBlue,
            backdrop = backdrop,
            modifier = Modifier.weight(1f),
            onClick = { onSelectTab(0) },
        )
        FloatingTabButton(
            icon = Icons.Default.Settings,
            label = "설정",
            selected = selectedTab == 1,
            accent = AccentMint,
            backdrop = backdrop,
            modifier = Modifier.weight(1f),
            onClick = { onSelectTab(1) },
        )
    }
}

@Composable
private fun FloatingTabButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    accent: Color,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val contentColor = if (selected) Color.White else TextSecondary
    val rowModifier = if (selected) {
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { TabShape },
            effects = {
                blur(4.dp.toPx())
                lens(14.dp.toPx(), 18.dp.toPx())
            },
            onDrawSurface = {
                drawRect(accent.copy(alpha = 0.9f))
            },
        )
    } else {
        modifier.background(Color.Transparent)
    }

    Row(
        modifier = rowModifier
            .clip(TabShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = contentColor, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, color = contentColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
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
                text = if (isError) "문제가 있어요" else "안내해요",
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
                Text(text = "확인해요", fontWeight = FontWeight.SemiBold)
            }
        },
        containerColor = Color.White.copy(alpha = 0.98f),
        shape = RoundedCornerShape(24.dp),
    )
}
