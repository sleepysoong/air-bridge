package com.airbridge.app.feature.clipboard

import com.airbridge.app.feature.common.ClipboardReadOutcome
import com.airbridge.app.feature.common.ClipboardTransferOrigin
import rikka.shizuku.Shizuku

class DualClipboardReadGateway(
    private val standardGateway: ClipboardReadGateway,
    private val shizukuGateway: ClipboardReadGateway
) : ClipboardReadGateway {
    override suspend fun readCurrentClipboard(origin: ClipboardTransferOrigin): ClipboardReadOutcome {
        return if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            shizukuGateway.readCurrentClipboard(origin)
        } else {
            standardGateway.readCurrentClipboard(origin)
        }
    }
}
