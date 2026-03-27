package com.airbridge.app.feature.clipboard

import com.airbridge.app.feature.common.ClipboardApplyOutcome
import com.airbridge.app.feature.common.ClipboardOutboundSink
import com.airbridge.app.feature.common.ClipboardReadOutcome
import com.airbridge.app.feature.common.ClipboardSnapshot
import com.airbridge.app.feature.common.ClipboardSyncStatus
import com.airbridge.app.feature.common.ClipboardTransferOrigin
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ClipboardSyncCoordinator(
    private val readGateway: ClipboardReadGateway,
    private val applyGateway: ClipboardApplyGateway,
    private val outboundSink: ClipboardOutboundSink,
    private val scope: CoroutineScope,
    private val monitorInterval: Duration = 1500.milliseconds,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val stateMutex = Mutex()
    private val mutableStatus = MutableStateFlow(ClipboardSyncStatus())
    private var monitorJob: Job? = null
    private var lastSentFingerprint: String? = null
    private var lastAppliedFingerprint: String? = null

    val status: StateFlow<ClipboardSyncStatus> = mutableStatus.asStateFlow()

    fun startForegroundMonitoring() {
        if (monitorJob?.isActive == true) {
            return
        }

        mutableStatus.value = mutableStatus.value.copy(isMonitoring = true)
        monitorJob = scope.launch {
            while (true) {
                runCatching {
                    captureAndSend(origin = ClipboardTransferOrigin.ForegroundMonitor, force = false)
                }.onFailure { error ->
                    mutableStatus.value = mutableStatus.value.copy(
                        lastError = error.message ?: "클립보드 모니터링 중 오류가 발생했어요",
                    )
                }
                delay(monitorInterval)
            }
        }
    }

    fun stopForegroundMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        mutableStatus.value = mutableStatus.value.copy(isMonitoring = false)
    }

    suspend fun resetSyncState() {
        stateMutex.withLock {
            lastSentFingerprint = null
            lastAppliedFingerprint = null
            mutableStatus.value = ClipboardSyncStatus(isMonitoring = monitorJob?.isActive == true)
        }
    }

    fun sendCurrentClipboardManually() {
        scope.launch {
            runCatching {
                captureAndSend(origin = ClipboardTransferOrigin.ManualAction, force = true)
            }.onFailure { error ->
                mutableStatus.value = mutableStatus.value.copy(
                    lastError = error.message ?: "수동 클립보드 전송에 실패했어요",
                )
            }
        }
    }

    suspend fun applyRemoteClipboard(snapshot: ClipboardSnapshot): ClipboardApplyOutcome {
        return stateMutex.withLock {
            val outcome = applyGateway.apply(snapshot)
            when (outcome) {
                ClipboardApplyOutcome.Applied -> {
                    lastAppliedFingerprint = snapshot.fingerprint
                    mutableStatus.value = mutableStatus.value.copy(
                        lastAppliedAt = Instant.now(clock),
                        lastFingerprint = snapshot.fingerprint,
                        lastError = null,
                    )
                }
                is ClipboardApplyOutcome.Failure -> {
                    mutableStatus.value = mutableStatus.value.copy(lastError = outcome.message)
                }
            }
            outcome
        }
    }

    private suspend fun captureAndSend(origin: ClipboardTransferOrigin, force: Boolean) {
        val outcome = readGateway.readCurrentClipboard(origin)
        when (outcome) {
            ClipboardReadOutcome.Empty -> {
                mutableStatus.value = mutableStatus.value.copy(lastError = null)
            }
            is ClipboardReadOutcome.Failure -> {
                mutableStatus.value = mutableStatus.value.copy(lastError = outcome.message)
            }
            is ClipboardReadOutcome.Unsupported -> {
                mutableStatus.value = mutableStatus.value.copy(lastError = outcome.reason)
            }
            is ClipboardReadOutcome.Success -> {
                publishIfNeeded(outcome.snapshot, origin, force)
            }
        }
    }

    private suspend fun publishIfNeeded(
        snapshot: ClipboardSnapshot,
        origin: ClipboardTransferOrigin,
        force: Boolean,
    ) {
        stateMutex.withLock {
            if (!force && snapshot.fingerprint == lastAppliedFingerprint) {
                mutableStatus.value = mutableStatus.value.copy(
                    lastCapturedAt = snapshot.capturedAt,
                    lastFingerprint = snapshot.fingerprint,
                    lastError = null,
                )
                return
            }

            if (!force && snapshot.fingerprint == lastSentFingerprint) {
                mutableStatus.value = mutableStatus.value.copy(
                    lastCapturedAt = snapshot.capturedAt,
                    lastFingerprint = snapshot.fingerprint,
                    lastError = null,
                )
                return
            }

            outboundSink.publishClipboard(snapshot, origin)
            lastSentFingerprint = snapshot.fingerprint
            mutableStatus.value = mutableStatus.value.copy(
                lastCapturedAt = snapshot.capturedAt,
                lastSentAt = Instant.now(clock),
                lastFingerprint = snapshot.fingerprint,
                lastError = null,
            )
        }
    }
}
