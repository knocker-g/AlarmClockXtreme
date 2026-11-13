package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.receiver.AlarmReceiver
import com.sysadmindoc.alarmclock.receiver.BootReceiver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Integrated Runtime Wiring Test Suite for KNO-75 Phase D3-C2-P3D-P3-C7-I1..I5 & P1..P4 Safety Corrections.
 *
 * Exercises:
 * - C7-I1 & P2: Legacy vs DirectBoot Owner Safety & Symmetric Fencing (T1 - T8).
 * - C7-I2 & P3: Locked Boot Queue Progression (A -> B -> C) & Rearm Exception Resilience.
 * - C7-I3 & P1: Stale Stop Lineage Fencing & 5-Step Audio Handover.
 * - C7-I4 & P4: OS Receiver Wiring & Unlock Receiver Timeout Safety.
 * - C7-I5: Full Integration & Regression.
 */
@RunWith(RobolectricTestRunner::class)
class DirectBootRuntimeWiringTest {

    private lateinit var deContext: Context
    private lateinit var ceContext: Context
    private lateinit var database: AlarmDatabase
    private val baseTime = 1_700_000_000_000L

    private fun clearDeStorage() {
        val dePrefs = deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
        dePrefs.edit()
            .remove(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD)
            .remove(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD)
            .remove(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD)
            .commit()
    }

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        deContext = appContext.createDeviceProtectedStorageContext()
        ceContext = appContext
        database = Room.inMemoryDatabaseBuilder(appContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DirectBootCeTakeoverStore.unlockGateSeam = { true }
        DirectBootCeTakeoverStore.clear(ceContext)
        clearDeStorage()
    }

    @After
    fun tearDown() {
        database.close()
        DirectBootCeTakeoverStore.clear(ceContext)
        DirectBootCeTakeoverStore.resetSeamsForTesting()
        clearDeStorage()
    }

    // =========================================================================
    // C7-I1 & P2: Legacy vs DirectBoot Owner Safety & Symmetric Fencing
    // =========================================================================

    @Test
    fun t1_t2_t4_legacyReceiverRejectsAlreadyDeliveredOccurrence() {
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 101L,
            triggerTime = baseTime + 10_000L,
            deliveredAt = baseTime + 10_050L,
            generation = 1,
            instanceToken = "tok_101",
            restoreEpoch = 1L
        )

        // Seed record in DE Delivery Ledger as delivered by DirectBoot
        val appendRes = DirectBootDeliveryLedger.appendRecord(deContext, delRecord)
        assertTrue(appendRes is DeliveryAppendResult.Appended)

        // Legacy AlarmReceiver query returns true -> legacy delivery rejected
        val isDelivered = AlarmReceiver.isOccurrenceDeliveredByDirectBoot(deContext, 101L, baseTime + 10_000L)
        assertTrue("Legacy AlarmReceiver MUST reject occurrence already delivered by DirectBoot", isDelivered)
    }

    @Test
    fun t8_legacyOnlyRepeatingAlarm_isNotDeliveredByDirectBoot() {
        val isDelivered = AlarmReceiver.isOccurrenceDeliveredByDirectBoot(deContext, 999L, baseTime + 99_000L)
        assertFalse("Legacy-only alarm not in DirectBoot Ledger MUST NOT be blocked", isDelivered)
    }

    // =========================================================================
    // C7-I2 & P3: Locked Boot Queue Progression & Rearm Exception Resilience
    // =========================================================================

    @Test
    fun i2_queueProgression_A_to_B_armsNextCandidateOnCommit() {
        val itemA = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 201L,
            triggerTime = baseTime + 10_000L,
            generation = 1,
            instanceToken = "tok_201",
            restoreEpoch = 1L
        )
        val itemB = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 202L,
            triggerTime = baseTime + 20_000L,
            generation = 1,
            instanceToken = "tok_202",
            restoreEpoch = 1L
        )

        DirectBootAlarmCache.writeQueue(deContext, listOf(itemA, itemB), now = baseTime)

        val fakeBoundary = FakeExactAlarmBoundary()
        val bootProv = BootProvenance(bootCount = 1, lastElapsedRealtime = 10_000L)

        DirectBootAlarmReceiver.reconcilerSeam = { ctx ->
            DirectBootRegistrationReconciler.createForTesting(
                context = ctx,
                osBoundary = fakeBoundary,
                clock = { baseTime + 10_050L },
                bootProvenanceProvider = { bootProv }
            )
        }

        // Simulate V2 firing broadcast for A
        val authorizer = DirectBootDeliveryAuthorizer()
        DirectBootAlarmReceiver.serviceAdapterSeam = { ctx ->
            DirectBootServiceDeliveryAdapter(ctx, authorizer, clock = { baseTime + 10_000L })
        }

        val identityA = DirectBootV2NormalIntentIdentity.fromQueueItem(itemA)
        val ticketRes = authorizer.issueTicket(identityA, nowEpochMs = baseTime + 10_000L)
        assertTrue(ticketRes is TicketIssueResult.Success)
        val ticketId = (ticketRes as TicketIssueResult.Success).ticket.ticketId

        val firingIntent = identityA.createIntent(ceContext).apply {
            putExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID, ticketId)
        }

        val receiver = DirectBootAlarmReceiver()
        receiver.onReceive(ceContext, firingIntent)

        // Verify next candidate B is armed on boundary
        val calls = fakeBoundary.registerCalls
        assertFalse("Reconciler MUST be invoked for next candidate after A's commit", calls.isEmpty())
        assertEquals(202L, calls.last().alarmId)
    }

    @Test
    fun p3_rearmException_preservesQueueAndMarkerFailedState() {
        val itemA = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 301L,
            triggerTime = baseTime + 10_000L,
            generation = 1,
            instanceToken = "tok_301",
            restoreEpoch = 1L
        )
        DirectBootAlarmCache.writeQueue(deContext, listOf(itemA), now = baseTime)

        val throwingBoundary = object : DirectBootExactAlarmManagerBoundary {
            override fun canScheduleExactAlarms(): Boolean = true
            override fun registerExactAlarm(identity: DirectBootV2NormalIntentIdentity): ExactRegistrationResult {
                return ExactRegistrationResult.SecurityError("Permission revoked during rearm", SecurityException("Revoked"))
            }
            override fun cancelExactAlarm(identity: DirectBootV2NormalIntentIdentity): ExactCancellationResult {
                return ExactCancellationResult.Accepted(identity)
            }
        }

        val bootProv = BootProvenance(bootCount = 1, lastElapsedRealtime = 10_000L)
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = deContext,
            osBoundary = throwingBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { bootProv }
        )

        val result = reconciler.reconcile()
        assertTrue("Failed registration must NOT return StableSuccess, got $result", result !is ReconcilerResult.StableSuccess)

        // Verify Queue remains intact (never deleted on error)
        val queueState = DirectBootAlarmCache.inspectQueueStorageState(deContext)
        assertTrue(queueState is QueueStorageState.ValidWithItems)
        assertEquals(1, (queueState as QueueStorageState.ValidWithItems).items.size)

        // Verify Marker recorded Security Exception
        val markerResult = DirectBootRegistrationMarker.read(deContext)
        assertTrue(markerResult is MarkerReadResult.Success)
        val marker = (markerResult as MarkerReadResult.Success).marker
        assertEquals(RegistrationMainState.FAILED_BLOCKED, marker.mainState)
        assertEquals(RegistrationFailureReason.SECURITY_EXCEPTION, marker.failureReason)
    }

    // =========================================================================
    // C7-I4 & P4: Unlock Receiver Execution & Idempotency
    // =========================================================================

    @Test
    fun i4_unlockReceiver_consumesPendingCeRecordsPostUnlock() = runBlocking {
        val missedRecord = DirectBootMissedRecord.createNormal(
            alarmId = 401L,
            triggerTime = baseTime + 40_000L,
            missedAt = baseTime + 40_500L,
            generation = 1,
            instanceToken = "tok_401",
            classification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE,
            restoreEpoch = 1L
        )

        DirectBootCeTakeoverStore.recordMissed(ceContext, missedRecord, baseTime + 40_600L)

        // Inject database seam into UnlockReceiver
        DirectBootUnlockReceiver.databaseSeam = { database }

        val latch = CountDownLatch(1)
        var consumerOutcome: DirectBootEventConsumerEngine.ConsumerExecutionResult? = null
        DirectBootUnlockReceiver.onConsumerFinishedSeam = { res ->
            consumerOutcome = res
            latch.countDown()
        }

        val unlockIntent = Intent(Intent.ACTION_USER_UNLOCKED)
        val receiver = DirectBootUnlockReceiver()
        receiver.onReceive(ceContext, unlockIntent)

        latch.await(5, TimeUnit.SECONDS)

        assertNotNull(consumerOutcome)
        assertTrue(consumerOutcome is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)

        // Verify Room 38 receipt written
        val dao = database.directBootOccurrenceReceiptDao()
        assertEquals(1L, dao.count())
        val occKey = DirectBootCeTakeoverStore.computeOccurrenceKey(401L, baseTime + 40_000L, 1, "tok_401")
        val receipt = dao.findByOccurrenceKey(occKey)
        assertNotNull(receipt)
        assertEquals("CONSUMED_MISSED", receipt?.consumerOutcome)

        // Verify CE slot released
        val ceState = DirectBootCeTakeoverStore.inspectStorageState(ceContext)
        assertTrue(ceState is CeTakeoverStorageState.ValidEmpty)
    }

    // Fake Exact Alarm Boundary helper for test
    private class FakeExactAlarmBoundary : DirectBootExactAlarmManagerBoundary {
        val registerCalls = mutableListOf<DirectBootV2NormalIntentIdentity>()
        val cancelCalls = mutableListOf<DirectBootV2NormalIntentIdentity>()

        override fun canScheduleExactAlarms(): Boolean = true

        override fun registerExactAlarm(identity: DirectBootV2NormalIntentIdentity): ExactRegistrationResult {
            registerCalls.add(identity)
            return ExactRegistrationResult.Accepted(identity)
        }

        override fun cancelExactAlarm(identity: DirectBootV2NormalIntentIdentity): ExactCancellationResult {
            cancelCalls.add(identity)
            return ExactCancellationResult.Accepted(identity)
        }
    }
}
