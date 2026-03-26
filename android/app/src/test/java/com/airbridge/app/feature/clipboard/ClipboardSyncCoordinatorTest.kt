package com.airbridge.app.feature.clipboard

import com.airbridge.app.feature.common.ClipboardApplyOutcome
import com.airbridge.app.feature.common.ClipboardOutboundSink
import com.airbridge.app.feature.common.ClipboardReadOutcome
import com.airbridge.app.feature.common.ClipboardSnapshot
import com.airbridge.app.feature.common.ClipboardTransferOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ClipboardSyncCoordinatorTest {
    private lateinit var mockReadGateway: MockClipboardReadGateway
    private lateinit var mockApplyGateway: MockClipboardApplyGateway
    private lateinit var mockOutboundSink: MockClipboardOutboundSink
    private lateinit var fixedClock: Clock

    @Before
    fun setup() {
        fixedClock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneId.of("UTC"))
        mockReadGateway = MockClipboardReadGateway()
        mockApplyGateway = MockClipboardApplyGateway()
        mockOutboundSink = MockClipboardOutboundSink()
    }

    private fun createCoordinator(scope: CoroutineScope): ClipboardSyncCoordinator {
        return ClipboardSyncCoordinator(
            readGateway = mockReadGateway,
            applyGateway = mockApplyGateway,
            outboundSink = mockOutboundSink,
            scope = scope,
            monitorInterval = 100.milliseconds,
            clock = fixedClock,
        )
    }

    @Test
    fun `로컬 클립보드가 변경되면 전송`() = runTest {
        val coordinator = createCoordinator(backgroundScope)
        val snapshot = createSnapshot("fp1", "Hello")
        mockReadGateway.nextOutcome = ClipboardReadOutcome.Success(snapshot)

        coordinator.startForegroundMonitoring()
        testScheduler.advanceTimeBy(150)

        assertEquals(1, mockOutboundSink.publishedSnapshots.size)
        assertEquals("fp1", mockOutboundSink.publishedSnapshots[0].fingerprint)
    }

    @Test
    fun `동일한 fingerprint는 중복 전송하지 않음`() = runTest {
        val coordinator = createCoordinator(backgroundScope)
        val snapshot = createSnapshot("fp1", "Hello")
        mockReadGateway.nextOutcome = ClipboardReadOutcome.Success(snapshot)

        coordinator.startForegroundMonitoring()
        testScheduler.advanceTimeBy(150)
        testScheduler.advanceTimeBy(150)

        assertEquals(1, mockOutboundSink.publishedSnapshots.size)
    }

    @Test
    fun `원격에서 적용한 클립보드는 다시 전송하지 않음 (루프 방지)`() = runTest {
        val coordinator = createCoordinator(backgroundScope)
        val remoteSnapshot = createSnapshot("fp_remote", "Remote content")
        coordinator.applyRemoteClipboard(remoteSnapshot)

        mockReadGateway.nextOutcome = ClipboardReadOutcome.Success(remoteSnapshot)
        coordinator.startForegroundMonitoring()
        testScheduler.advanceTimeBy(150)

        assertEquals(0, mockOutboundSink.publishedSnapshots.size)
    }

    @Test
    fun `원격 적용 후 로컬 변경은 정상 전송`() = runTest {
        val coordinator = createCoordinator(backgroundScope)
        val remoteSnapshot = createSnapshot("fp_remote", "Remote")
        coordinator.applyRemoteClipboard(remoteSnapshot)

        val localSnapshot = createSnapshot("fp_local", "Local")
        mockReadGateway.nextOutcome = ClipboardReadOutcome.Success(localSnapshot)

        coordinator.startForegroundMonitoring()
        testScheduler.advanceTimeBy(150)

        assertEquals(1, mockOutboundSink.publishedSnapshots.size)
        assertEquals("fp_local", mockOutboundSink.publishedSnapshots[0].fingerprint)
    }

    @Test
    fun `로컬 전송 후 동일 내용 원격 수신은 적용하고 재전송은 하지 않음`() = runTest {
        val coordinator = createCoordinator(backgroundScope)
        val snapshot = createSnapshot("fp1", "Content")
        mockReadGateway.nextOutcome = ClipboardReadOutcome.Success(snapshot)

        coordinator.startForegroundMonitoring()
        testScheduler.advanceTimeBy(150)

        assertEquals(1, mockOutboundSink.publishedSnapshots.size)

        coordinator.applyRemoteClipboard(snapshot)
        testScheduler.advanceTimeBy(150)

        assertEquals(1, mockApplyGateway.appliedSnapshots.size)
        assertEquals(1, mockOutboundSink.publishedSnapshots.size)
    }

    @Test
    fun `force=true면 중복 fingerprint도 전송`() = runTest {
        val coordinator = createCoordinator(backgroundScope)
        val snapshot = createSnapshot("fp1", "Content")
        mockReadGateway.nextOutcome = ClipboardReadOutcome.Success(snapshot)

        coordinator.sendCurrentClipboardManually()
        testScheduler.runCurrent()

        coordinator.sendCurrentClipboardManually()
        testScheduler.runCurrent()

        assertEquals(2, mockOutboundSink.publishedSnapshots.size)
    }

    @Test
    fun `원격 클립보드 적용 성공`() = runTest {
        val coordinator = createCoordinator(backgroundScope)
        val snapshot = createSnapshot("fp_remote", "Remote content")

        val outcome = coordinator.applyRemoteClipboard(snapshot)

        assertEquals(ClipboardApplyOutcome.Applied, outcome)
        assertEquals(1, mockApplyGateway.appliedSnapshots.size)
        assertEquals(fixedClock.instant(), coordinator.status.value.lastAppliedAt)
    }

    @Test
    fun `빈 클립보드는 에러 없이 무시`() = runTest {
        val coordinator = createCoordinator(backgroundScope)
        mockReadGateway.nextOutcome = ClipboardReadOutcome.Empty

        coordinator.startForegroundMonitoring()
        testScheduler.advanceTimeBy(150)

        assertEquals(0, mockOutboundSink.publishedSnapshots.size)
        assertNull(coordinator.status.value.lastError)
    }

    @Test
    fun `모니터링 중지하면 전송 중단`() = runTest {
        val coordinator = createCoordinator(backgroundScope)
        val snapshot = createSnapshot("fp1", "Content")
        mockReadGateway.nextOutcome = ClipboardReadOutcome.Success(snapshot)

        coordinator.startForegroundMonitoring()
        testScheduler.advanceTimeBy(150)

        assertEquals(1, mockOutboundSink.publishedSnapshots.size)

        coordinator.stopForegroundMonitoring()
        testScheduler.advanceTimeBy(300)

        assertEquals(1, mockOutboundSink.publishedSnapshots.size)
    }

    private fun createSnapshot(fingerprint: String, text: String): ClipboardSnapshot {
        return ClipboardSnapshot(
            mimeType = "text/plain",
            label = null,
            plainText = text,
            htmlText = null,
            uriList = emptyList(),
            binaryPayload = null,
            fingerprint = fingerprint,
            capturedAt = Instant.now(fixedClock),
        )
    }

    private class MockClipboardReadGateway : ClipboardReadGateway {
        var nextOutcome: ClipboardReadOutcome = ClipboardReadOutcome.Empty

        override suspend fun readCurrentClipboard(origin: ClipboardTransferOrigin): ClipboardReadOutcome {
            return nextOutcome
        }
    }

    private class MockClipboardApplyGateway : ClipboardApplyGateway {
        val appliedSnapshots = mutableListOf<ClipboardSnapshot>()

        override suspend fun apply(snapshot: ClipboardSnapshot): ClipboardApplyOutcome {
            appliedSnapshots.add(snapshot)
            return ClipboardApplyOutcome.Applied
        }
    }

    private class MockClipboardOutboundSink : ClipboardOutboundSink {
        val publishedSnapshots = mutableListOf<ClipboardSnapshot>()

        override suspend fun publishClipboard(snapshot: ClipboardSnapshot, origin: ClipboardTransferOrigin) {
            publishedSnapshots.add(snapshot)
        }
    }
}
