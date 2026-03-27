package com.airbridge.app.feature.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning

private val AppleBackground = Color(0xFFF2F2F7)
private val AppleSurface = Color(0xFFFFFFFF)
private val AppleTextPrimary = Color(0xFF000000)
private val AppleTextSecondary = Color(0xFF8A8A8E)
private val AppleBlue = Color(0xFF007AFF)
private val AppleDivider = Color(0xFFC6C6C8)

private val IosCardShape = RoundedCornerShape(12.dp)

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
    onRequestShizukuPermission: () -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    var selectedTab by remember { mutableIntStateOf(0) }

    PopupAlert(
        message = uiState.errorMessage ?: uiState.infoMessage,
        isError = uiState.errorMessage != null,
        onDismiss = onDismissBanner,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppleBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 120.dp), // padding for bottom bar
        ) {
            if (selectedTab == 0) {
                // Home/Pairing Tab
                ScreenHeader(title = "Pairing")
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    IosSection(title = "DEVICE INFORMATION") {
                        IosTextFieldRow(
                            label = "Device Name",
                            value = uiState.deviceName,
                            onValueChange = onDeviceNameChanged,
                            isLast = false
                        )
                        IosTextFieldRow(
                            label = "QR / URL",
                            value = uiState.qrPayload,
                            onValueChange = onQrPayloadChanged,
                            isLast = true
                        )
                    }

                    IosButton(
                        text = if (uiState.isBusy) "Connecting..." else "Connect via Link",
                        onClick = onPreparePairing,
                        enabled = !uiState.isBusy,
                        isLoading = uiState.isBusy
                    )
                }
            } else {
                // Settings/Runtime Tab
                ScreenHeader(title = "Settings")
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    IosSection(title = "STATUS") {
                        IosMetricRow(label = "Bridge Status", value = uiState.runtimeSnapshot.status, isLast = false)
                        IosMetricRow(label = "Clipboard", value = if (uiState.clipboardStatus.isMonitoring) "Monitoring" else "Stopped", isLast = false)
                        IosMetricRow(
                            label = "Notification", 
                            value = if (uiState.notificationAccessGranted) "Granted" else "Required", 
                            isError = !uiState.notificationAccessGranted,
                            isLast = !uiState.shizukuAvailable
                        )
                        if (uiState.shizukuAvailable) {
                            IosMetricRow(
                                label = "Shizuku", 
                                value = if (uiState.shizukuPermissionGranted) "Granted" else "Required", 
                                isError = !uiState.shizukuPermissionGranted,
                                isLast = true
                            )
                        }
                    }

                    IosSection(title = "ACTIONS") {
                        IosActionRow(
                            label = "Start Bridge",
                            onClick = onStartBridge,
                            enabled = uiState.activeCredentials != null,
                            isLast = false
                        )
                        IosActionRow(
                            label = "Send Clipboard",
                            onClick = onManualClipboardSend,
                            enabled = uiState.activeCredentials != null,
                            isLast = false
                        )
                        IosActionRow(
                            label = "Notification Settings",
                            onClick = onOpenNotificationAccess,
                            isLast = !uiState.shizukuAvailable || uiState.shizukuPermissionGranted
                        )
                        if (uiState.shizukuAvailable && !uiState.shizukuPermissionGranted) {
                            IosActionRow(
                                label = "Request Shizuku",
                                onClick = onRequestShizukuPermission,
                                isLast = true
                            )
                        }
                    }
                }
            }
        }

        // Floating Bottom Bar with Liquid Glass
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp, start = 32.dp, end = 32.dp)
                .height(64.dp)
                .fillMaxWidth()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {
                        vibrancy()
                        blur(16.dp.toPx())
                        lens(16.dp.toPx(), 32.dp.toPx())
                    },
                    onDrawSurface = {
                        drawRect(Color.White.copy(alpha = 0.6f))
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TabItem(
                    icon = Icons.Default.Home,
                    label = "Pairing",
                    isSelected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                TabItem(
                    icon = Icons.Default.Settings,
                    label = "Settings",
                    isSelected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val color = if (isSelected) AppleBlue else AppleTextSecondary
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = color, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ScreenHeader(title: String) {
    Text(
        text = title,
        color = AppleTextPrimary,
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 48.dp, bottom = 16.dp)
    )
}

@Composable
private fun IosSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            color = AppleTextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(IosCardShape)
                .background(AppleSurface)
        ) {
            content()
        }
    }
}

@Composable
private fun IosTextFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isLast: Boolean
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = AppleTextPrimary,
                fontSize = 16.sp,
                modifier = Modifier.width(100.dp)
            )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedTextColor = AppleTextSecondary,
                    unfocusedTextColor = AppleTextSecondary,
                    cursorColor = AppleBlue,
                ),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
            )
        }
        if (!isLast) {
            HorizontalDivider(color = AppleDivider.copy(alpha = 0.5f), modifier = Modifier.padding(start = 16.dp))
        }
    }
}

@Composable
private fun IosMetricRow(label: String, value: String, isError: Boolean = false, isLast: Boolean) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, color = AppleTextPrimary, fontSize = 16.sp)
            Text(
                text = value,
                color = if (isError) Color(0xFFFF3B30) else AppleTextSecondary,
                fontSize = 16.sp
            )
        }
        if (!isLast) {
            HorizontalDivider(color = AppleDivider.copy(alpha = 0.5f), modifier = Modifier.padding(start = 16.dp))
        }
    }
}

@Composable
private fun IosActionRow(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isLast: Boolean
) {
    Column(modifier = Modifier.clickable(enabled = enabled, onClick = onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = if (enabled) AppleBlue else AppleTextSecondary.copy(alpha = 0.5f),
                fontSize = 16.sp
            )
        }
        if (!isLast) {
            HorizontalDivider(color = AppleDivider.copy(alpha = 0.5f), modifier = Modifier.padding(start = 16.dp))
        }
    }
}

@Composable
private fun IosButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    isLoading: Boolean
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = IosCardShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = AppleBlue,
            disabledContainerColor = AppleBlue.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
            Text(if (isError) "Error" else "Info", color = AppleTextPrimary, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Text(message, color = AppleTextPrimary, fontSize = 14.sp)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK", color = AppleBlue, fontWeight = FontWeight.SemiBold)
            }
        },
        containerColor = AppleSurface,
        shape = IosCardShape
    )
}
