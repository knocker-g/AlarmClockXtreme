package com.sysadmindoc.alarmclock.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.directboot.DirectBootLegacyClaimStore
import com.sysadmindoc.alarmclock.directboot.LegacyClaimRecord
import com.sysadmindoc.alarmclock.directboot.LegacyClaimSource
import com.sysadmindoc.alarmclock.directboot.LegacyClaimState
import com.sysadmindoc.alarmclock.directboot.OutputOccurrenceKey
import com.sysadmindoc.alarmclock.directboot.outputauthority.OutputAuthorizationResult
import com.sysadmindoc.alarmclock.directboot.outputauthority.OutputSinkType
import com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AlarmFullScreenFallbackOutputAuthorityTest {

    private lateinit var context: Context
    private lateinit var gate: SharedOutputAuthorityGate

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        org.robolectric.Shadows.shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        clearStorage()
        AlarmFullScreenFallback.clearForTesting()

        gate = SharedOutputAuthorityGate(
            bootCount = 10L,
            processSessionToken = "proc-test-session"
        )
    }

    @After
    fun tearDown() {
        clearStorage()
        AlarmFullScreenFallback.clearForTesting()
    }

    private fun clearStorage() {
        DirectBootLegacyClaimStore.prefs(context).edit().clear().commit()
    }

    @Test
    fun testFallback_flagOff_maintainsLegacyBehavior() {
        gate.isEnabled = false

        val posted = AlarmFullScreenFallback.post(
            context = context,
            alarmId = 101L,
            scheduledAt = 1000L,
            fireId = "fire-1",
            hideLabel = false,
            label = "Morning",
            authorityGate = gate
        )

        assertTrue(posted)
    }

    @Test
    fun testFallback_flagOn_nullClaimNonce_failsClosed() {
        gate.isEnabled = true

        val posted = AlarmFullScreenFallback.post(
            context = context,
            alarmId = 101L,
            scheduledAt = 1000L,
            fireId = "fire-1",
            hideLabel = false,
            label = "Morning",
            claimNonce = null,
            authorityGate = gate
        )

        assertFalse("Fallback MUST reject null claimNonce when gate is enabled", posted)
    }

    @Test
    fun testFallback_flagOn_nullRoomChecker_failsClosed() {
        gate.isEnabled = true
        val key = OutputOccurrenceKey(101L, 1000L, 0)
        val nonce = "nonce-handover-1"

        DirectBootLegacyClaimStore.writeInitialClaim(
            context = context,
            record = LegacyClaimRecord(
                alarmId = key.alarmId,
                scheduledAt = key.scheduledAt,
                refireCount = key.refireCount,
                source = LegacyClaimSource.ALARM_RECEIVER,
                claimNonce = nonce,
                processSessionToken = "proc-test-session",
                bootCount = 10L,
                state = LegacyClaimState.START_REQUESTED
            )
        )

        val posted = AlarmFullScreenFallback.post(
            context = context,
            alarmId = 101L,
            scheduledAt = 1000L,
            fireId = "fire-1",
            hideLabel = false,
            claimNonce = nonce,
            roomReceiptChecker = null,
            authorityGate = gate
        )

        assertFalse("Fallback MUST fail closed when roomReceiptChecker is null in CE mode", posted)
    }

    @Test
    fun testFallback_flagOn_roomCheckerException_failsClosed() {
        gate.isEnabled = true
        val key = OutputOccurrenceKey(101L, 1000L, 0)
        val nonce = "nonce-handover-2"

        DirectBootLegacyClaimStore.writeInitialClaim(
            context = context,
            record = LegacyClaimRecord(
                alarmId = key.alarmId,
                scheduledAt = key.scheduledAt,
                refireCount = key.refireCount,
                source = LegacyClaimSource.ALARM_RECEIVER,
                claimNonce = nonce,
                processSessionToken = "proc-test-session",
                bootCount = 10L,
                state = LegacyClaimState.START_REQUESTED
            )
        )

        val posted = AlarmFullScreenFallback.post(
            context = context,
            alarmId = 101L,
            scheduledAt = 1000L,
            fireId = "fire-1",
            hideLabel = false,
            claimNonce = nonce,
            roomReceiptChecker = { _, _ -> throw IllegalStateException("DB disk I/O error") },
            authorityGate = gate
        )

        assertFalse("Fallback MUST fail closed when roomReceiptChecker throws exception", posted)
    }

    @Test
    fun testFallback_flagOn_roomReceiptExists_failsClosed() {
        gate.isEnabled = true
        val key = OutputOccurrenceKey(101L, 1000L, 0)
        val nonce = "nonce-handover-3"

        DirectBootLegacyClaimStore.writeInitialClaim(
            context = context,
            record = LegacyClaimRecord(
                alarmId = key.alarmId,
                scheduledAt = key.scheduledAt,
                refireCount = key.refireCount,
                source = LegacyClaimSource.ALARM_RECEIVER,
                claimNonce = nonce,
                processSessionToken = "proc-test-session",
                bootCount = 10L,
                state = LegacyClaimState.START_REQUESTED
            )
        )

        val posted = AlarmFullScreenFallback.post(
            context = context,
            alarmId = 101L,
            scheduledAt = 1000L,
            fireId = "fire-1",
            hideLabel = false,
            claimNonce = nonce,
            roomReceiptChecker = { _, _ -> true }, // Receipt already exists in Room
            authorityGate = gate
        )

        assertFalse("Fallback MUST reject when room receipt already exists", posted)
    }

    @Test
    fun testFallback_flagOn_staleNonce_failsClosed() {
        gate.isEnabled = true
        val key = OutputOccurrenceKey(101L, 1000L, 0)
        val nonce = "nonce-handover-4"

        DirectBootLegacyClaimStore.writeInitialClaim(
            context = context,
            record = LegacyClaimRecord(
                alarmId = key.alarmId,
                scheduledAt = key.scheduledAt,
                refireCount = key.refireCount,
                source = LegacyClaimSource.ALARM_RECEIVER,
                claimNonce = nonce,
                processSessionToken = "proc-test-session",
                bootCount = 10L,
                state = LegacyClaimState.START_REQUESTED
            )
        )

        val posted = AlarmFullScreenFallback.post(
            context = context,
            alarmId = 101L,
            scheduledAt = 1000L,
            fireId = "fire-1",
            hideLabel = false,
            claimNonce = "nonce-stale-wrong",
            roomReceiptChecker = { _, _ -> false },
            authorityGate = gate
        )

        assertFalse("Fallback MUST reject when claimNonce does not match record", posted)
    }

    @Test
    fun testFallback_flagOn_doublePostWithSameNonce_failsClosed() {
        gate.isEnabled = true
        val key = OutputOccurrenceKey(101L, 1000L, 0)
        val nonce = "nonce-handover-5"

        DirectBootLegacyClaimStore.writeInitialClaim(
            context = context,
            record = LegacyClaimRecord(
                alarmId = key.alarmId,
                scheduledAt = key.scheduledAt,
                refireCount = key.refireCount,
                source = LegacyClaimSource.ALARM_RECEIVER,
                claimNonce = nonce,
                processSessionToken = "proc-test-session",
                bootCount = 10L,
                state = LegacyClaimState.START_REQUESTED
            )
        )

        val posted1 = AlarmFullScreenFallback.post(
            context = context,
            alarmId = 101L,
            scheduledAt = 1000L,
            fireId = "fire-1",
            hideLabel = false,
            claimNonce = nonce,
            roomReceiptChecker = { _, _ -> false },
            authorityGate = gate
        )
        assertTrue("First post should succeed", posted1)

        val posted2 = AlarmFullScreenFallback.post(
            context = context,
            alarmId = 101L,
            scheduledAt = 1000L,
            fireId = "fire-1",
            hideLabel = false,
            claimNonce = nonce,
            roomReceiptChecker = { _, _ -> false },
            authorityGate = gate
        )
        assertFalse("Second post with same nonce MUST be rejected as double-post", posted2)
    }

    @Test
    fun testFallback_flagOn_serviceActiveForSameOccurrence_failsClosed() {
        gate.isEnabled = true
        val key = OutputOccurrenceKey(101L, 1000L, 0)
        val nonce = "nonce-handover-6"

        DirectBootLegacyClaimStore.writeInitialClaim(
            context = context,
            record = LegacyClaimRecord(
                alarmId = key.alarmId,
                scheduledAt = key.scheduledAt,
                refireCount = key.refireCount,
                source = LegacyClaimSource.ALARM_RECEIVER,
                claimNonce = nonce,
                processSessionToken = "proc-test-session",
                bootCount = 10L,
                state = LegacyClaimState.START_REQUESTED
            )
        )

        // AlarmService claims it first
        val serviceClaim = gate.claimOutput(
            context = context,
            key = key,
            incomingNonce = nonce,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            directBootModeOnly = false,
            roomReceiptChecker = { _, _ -> false }
        )
        assertTrue(serviceClaim is OutputAuthorizationResult.Authorized)

        // Fallback attempts to claim the same occurrence
        val fallbackPosted = AlarmFullScreenFallback.post(
            context = context,
            alarmId = 101L,
            scheduledAt = 1000L,
            fireId = "fire-1",
            hideLabel = false,
            claimNonce = nonce,
            roomReceiptChecker = { _, _ -> false },
            authorityGate = gate
        )

        assertFalse("Fallback MUST be rejected when Service is already active for same occurrence", fallbackPosted)
    }

    @Test
    fun testFallback_flagOn_validClaim_succeeds() {
        gate.isEnabled = true
        val key = OutputOccurrenceKey(101L, 1000L, 0)
        val nonce = "nonce-handover-7"

        DirectBootLegacyClaimStore.writeInitialClaim(
            context = context,
            record = LegacyClaimRecord(
                alarmId = key.alarmId,
                scheduledAt = key.scheduledAt,
                refireCount = key.refireCount,
                source = LegacyClaimSource.ALARM_RECEIVER,
                claimNonce = nonce,
                processSessionToken = "proc-test-session",
                bootCount = 10L,
                state = LegacyClaimState.START_REQUESTED
            )
        )

        val posted = AlarmFullScreenFallback.post(
            context = context,
            alarmId = 101L,
            scheduledAt = 1000L,
            fireId = "fire-1",
            hideLabel = false,
            claimNonce = nonce,
            roomReceiptChecker = { _, _ -> false },
            authorityGate = gate
        )

        assertTrue("Valid fallback post MUST succeed", posted)
        assertNotNull(gate.arbiter.activeHandle)
        assertEquals(OutputSinkType.FULLSCREEN_FALLBACK_1001, gate.arbiter.activeHandle?.sink)
        assertEquals(key, gate.arbiter.activeHandle?.key)
    }

    @Test
    fun testFallback_recovery_observePhysicalOutputFailure_abortsDeClaim() {
        gate.isEnabled = true
        val key = OutputOccurrenceKey(102L, 2000L, 0)
        val nonce = "nonce-fail-obs"

        DirectBootLegacyClaimStore.writeInitialClaim(
            context = context,
            record = LegacyClaimRecord(
                alarmId = key.alarmId,
                scheduledAt = key.scheduledAt,
                refireCount = key.refireCount,
                source = LegacyClaimSource.ALARM_RECEIVER,
                claimNonce = nonce,
                processSessionToken = "proc-test-session",
                bootCount = 10L,
                state = LegacyClaimState.START_REQUESTED
            )
        )

        val posted = AlarmFullScreenFallback.post(
            context = context,
            alarmId = 102L,
            scheduledAt = 2000L,
            fireId = "fire-fail-1",
            hideLabel = false,
            claimNonce = nonce,
            roomReceiptChecker = { _, _ -> true }, // Receipt exists -> observePhysicalOutput fails
            authorityGate = gate
        )

        assertFalse("Post must fail", posted)
        // Verify DE claim is not left in claiming/observed state
        val storage = DirectBootLegacyClaimStore.inspectStorageState(context)
        val record = (storage as com.sysadmindoc.alarmclock.directboot.LegacyClaimStorageState.ValidWithRecords).records.first { it.key == key }
        assertEquals("DE claim MUST be transitioned to TERMINAL upon abort", LegacyClaimState.TERMINAL, record.state)
    }

    @Test
    fun testFallback_processDeath_deStorePreventsRePostEvenWhenInMemoryMapIsLost() {
        gate.isEnabled = true
        val key = OutputOccurrenceKey(103L, 3000L, 0)
        val nonce = "nonce-proc-death"

        DirectBootLegacyClaimStore.writeInitialClaim(
            context = context,
            record = LegacyClaimRecord(
                alarmId = key.alarmId,
                scheduledAt = key.scheduledAt,
                refireCount = key.refireCount,
                source = LegacyClaimSource.ALARM_RECEIVER,
                claimNonce = nonce,
                processSessionToken = "proc-test-session",
                bootCount = 10L,
                state = LegacyClaimState.START_REQUESTED
            )
        )

        val posted1 = AlarmFullScreenFallback.post(
            context = context,
            alarmId = 103L,
            scheduledAt = 3000L,
            fireId = "fire-death-1",
            hideLabel = false,
            claimNonce = nonce,
            roomReceiptChecker = { _, _ -> false },
            authorityGate = gate
        )
        assertTrue("Initial post succeeds", posted1)

        // Simulate process death: wipe in-memory collections
        AlarmFullScreenFallback.clearForTesting()

        // New process attempts to post the same occurrence
        val posted2 = AlarmFullScreenFallback.post(
            context = context,
            alarmId = 103L,
            scheduledAt = 3000L,
            fireId = "fire-death-1",
            hideLabel = false,
            claimNonce = nonce,
            roomReceiptChecker = { _, _ -> false },
            authorityGate = gate
        )
        assertFalse("Post MUST fail closed after process restart due to DE persistent state", posted2)
    }

    @Test
    fun testFallback_recovery_staleNonceCleanup_rejectedWithoutCorruptingActiveClaim() {
        gate.isEnabled = true
        val key = OutputOccurrenceKey(104L, 4000L, 0)
        val activeNonce = "nonce-active-104"

        DirectBootLegacyClaimStore.writeInitialClaim(
            context = context,
            record = LegacyClaimRecord(
                alarmId = key.alarmId,
                scheduledAt = key.scheduledAt,
                refireCount = key.refireCount,
                source = LegacyClaimSource.ALARM_RECEIVER,
                claimNonce = activeNonce,
                processSessionToken = "proc-test-session",
                bootCount = 10L,
                state = LegacyClaimState.START_REQUESTED
            )
        )
        DirectBootLegacyClaimStore.transition(
            context = context,
            key = key,
            expectedFrom = LegacyClaimState.START_REQUESTED,
            toState = LegacyClaimState.OUTPUT_CLAIMING,
            expectedNonce = activeNonce,
            newNonce = activeNonce,
            processSessionToken = "proc-test-session",
            bootCount = 10L
        )

        val abortResult = gate.abortClaim(
            context = context,
            key = key,
            activeNonce = "nonce-stale-wrong"
        )
        assertFalse("Stale nonce cleanup MUST be rejected", abortResult)

        val storage = DirectBootLegacyClaimStore.inspectStorageState(context)
        val record = (storage as com.sysadmindoc.alarmclock.directboot.LegacyClaimStorageState.ValidWithRecords).records.first { it.key == key }
        assertEquals("Active claim state MUST NOT be modified by stale cleanup", LegacyClaimState.OUTPUT_CLAIMING, record.state)
        assertEquals("Active claim nonce MUST NOT be altered", activeNonce, record.claimNonce)
    }

    @Test
    fun testFallback_concurrency_atomicReservation_onlyOneConcurrentPostSucceeds() {
        gate.isEnabled = true
        val key = OutputOccurrenceKey(105L, 5000L, 0)
        val nonce = "nonce-concurrent-105"

        DirectBootLegacyClaimStore.writeInitialClaim(
            context = context,
            record = LegacyClaimRecord(
                alarmId = key.alarmId,
                scheduledAt = key.scheduledAt,
                refireCount = key.refireCount,
                source = LegacyClaimSource.ALARM_RECEIVER,
                claimNonce = nonce,
                processSessionToken = "proc-test-session",
                bootCount = 10L,
                state = LegacyClaimState.START_REQUESTED
            )
        )

        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val results = java.util.Collections.synchronizedList(mutableListOf<Boolean>())

        val future1 = executor.submit {
            barrier.await()
            val r = AlarmFullScreenFallback.post(
                context = context,
                alarmId = 105L,
                scheduledAt = 5000L,
                fireId = "fire-conc-1",
                hideLabel = false,
                claimNonce = nonce,
                roomReceiptChecker = { _, _ -> false },
                authorityGate = gate
            )
            results.add(r)
        }
        val future2 = executor.submit {
            barrier.await()
            val r = AlarmFullScreenFallback.post(
                context = context,
                alarmId = 105L,
                scheduledAt = 5000L,
                fireId = "fire-conc-2",
                hideLabel = false,
                claimNonce = nonce,
                roomReceiptChecker = { _, _ -> false },
                authorityGate = gate
            )
            results.add(r)
        }

        future1.get(5, java.util.concurrent.TimeUnit.SECONDS)
        future2.get(5, java.util.concurrent.TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(2, results.size)
        val successCount = results.count { it }
        val failCount = results.count { !it }
        assertEquals("Exactly one concurrent post MUST succeed", 1, successCount)
        assertEquals("Exactly one concurrent post MUST be rejected", 1, failCount)
    }
}
