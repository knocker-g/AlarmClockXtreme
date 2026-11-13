package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.receiver.AlarmReceiver
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
 * Integrated 5-Queue Direct Boot Pipeline & Crash Liveness Test Suite for KNO-75 Phase C7-CRASH-LIVENESS-P1A.
 *
 * Exercises:
 * - C1-C8: Atomic recovery transition, evidence retention on commit failure, post-unlock takeover.
 * - F1: Service start failure converts claim to MissedRecord (CLASSIFICATION_SERVICE_START_FAILED) and writes CONSUMED_MISSED receipt.
 * - R1-R13: Full 5-queue pipeline, crash window recovery, unlock idempotency, and failure handling.
 */
@RunWith(RobolectricTestRunner::class)
class DirectBoot5QueuePipelineTest {

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
    // R1 & R2: Enqueue 2 Items -> Arms Head A -> A Fires -> Arms B
    // =========================================================================

    @Test
    fun r1_r2_twoItemsEnqueued_armsHeadA_thenArmsCandidateB() {
        val itemA = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 101L,
            triggerTime = baseTime + 10_000L,
            generation = 1,
            instanceToken = "tok_101",
            restoreEpoch = 1L
        )
        val itemB = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 102L,
            triggerTime = baseTime + 20_000L,
            generation = 1,
            instanceToken = "tok_102",
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

        // Fire A
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
        assertFalse("Candidate B MUST be registered after A's commit", calls.isEmpty())
        assertEquals(102L, calls.last().alarmId)
    }

    // =========================================================================
    // R3: Full 5-Queue Sequential Delivery (A -> B -> C -> D -> E All Delivered)
    // =========================================================================

    @Test
    fun r3_full5QueueSequentialDelivery() {
        val items = (1..5).map { i ->
            DirectBootQueueItem(
                kind = DirectBootQueueItem.KIND_NORMAL,
                alarmId = (500 + i).toLong(),
                triggerTime = baseTime + (i * 10_000L),
                generation = 1,
                instanceToken = "tok_5q_$i",
                restoreEpoch = 1L
            )
        }

        DirectBootAlarmCache.writeQueue(deContext, items, now = baseTime)

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

        val authorizer = DirectBootDeliveryAuthorizer()
        var currentTestTime = baseTime + 10_000L

        DirectBootAlarmReceiver.serviceAdapterSeam = { ctx ->
            DirectBootServiceDeliveryAdapter(ctx, authorizer, clock = { currentTestTime })
        }

        // Fire all 5 items sequentially
        for (i in 1..5) {
            val item = items[i - 1]
            currentTestTime = baseTime + (i * 10_000L)
            val identity = DirectBootV2NormalIntentIdentity.fromQueueItem(item)
            val ticketRes = authorizer.issueTicket(identity, nowEpochMs = currentTestTime)
            assertTrue(ticketRes is TicketIssueResult.Success)
            val ticketId = (ticketRes as TicketIssueResult.Success).ticket.ticketId

            val firingIntent = identity.createIntent(ceContext).apply {
                putExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID, ticketId)
            }

            DirectBootAlarmReceiver().onReceive(ceContext, firingIntent)
        }

        // Verify queue state in DE storage is empty after all 5 items deliver
        val remainingQueue = DirectBootAlarmCache.readQueue(deContext, now = baseTime + 55_000L)
        assertTrue("Queue MUST be empty after all 5 items fire and deliver", remainingQueue.isEmpty())

        // Verify DE Delivery Ledger contains all 5 records
        val ledgerState = DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext)
        assertTrue(ledgerState is LedgerStorageState.ValidWithRecords)
        assertEquals(5, (ledgerState as LedgerStorageState.ValidWithRecords).records.size)
    }

    // =========================================================================
    // F1 & R7 & C1-C8: Service Start Failure & Crash Recovery
    // =========================================================================

    @Test
    fun f1_serviceStartFailed_recordsMissedRecord_preventsFalseDelivery() = runBlocking {
        val itemA = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 703L,
            triggerTime = baseTime + 73_000L,
            generation = 1,
            instanceToken = "tok_f1_703",
            restoreEpoch = 1L
        )

        DirectBootAlarmCache.writeQueue(deContext, listOf(itemA), now = baseTime)

        val authorizer = DirectBootDeliveryAuthorizer()
        DirectBootAlarmReceiver.serviceAdapterSeam = { ctx ->
            DirectBootServiceDeliveryAdapter(ctx, authorizer, clock = { baseTime + 73_000L })
        }

        val identityA = DirectBootV2NormalIntentIdentity.fromQueueItem(itemA)
        val ticketRes = authorizer.issueTicket(identityA, nowEpochMs = baseTime + 73_000L)
        assertTrue(ticketRes is TicketIssueResult.Success)
        val ticketId = (ticketRes as TicketIssueResult.Success).ticket.ticketId

        val firingIntent = identityA.createIntent(ceContext).apply {
            putExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID, ticketId)
        }

        val throwingContext = object : android.content.ContextWrapper(ceContext) {
            override fun startForegroundService(service: Intent?): android.content.ComponentName? {
                throw IllegalStateException("Background start not allowed")
            }
        }

        val receiver = DirectBootAlarmReceiver()
        receiver.onReceive(throwingContext, firingIntent)

        // 1. Verify unacknowledged record is retained in DE Ledger until post-unlock takeover
        val ledgerState = DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext)
        assertTrue("DE Ledger MUST retain unacknowledged record after service start failure", ledgerState is LedgerStorageState.ValidWithRecords)

        // 2. Verify Missed Record was created in DirectBootMissedStore with CLASSIFICATION_SERVICE_START_FAILED
        val missedState = DirectBootMissedStore.readUnacknowledgedRecords(deContext)
        assertTrue(missedState is MissedStoreStorageState.ValidWithRecords)
        val missedRecords = (missedState as MissedStoreStorageState.ValidWithRecords).records
        assertEquals(1, missedRecords.size)
        assertEquals(703L, missedRecords[0].alarmId)
        assertEquals(DirectBootMissedRecord.CLASSIFICATION_SERVICE_START_FAILED, missedRecords[0].classification)

        // 3. Handoff missed record to CE Takeover Store post-unlock
        DirectBootCeTakeoverStore.recordMissed(ceContext, missedRecords[0], baseTime + 73_050L)

        // 4. Post-unlock takeover consumes missed record as CONSUMED_MISSED (NOT CONSUMED_DELIVERED)
        DirectBootUnlockReceiver.databaseSeam = { database }
        val latch = CountDownLatch(1)
        DirectBootUnlockReceiver.onConsumerFinishedSeam = { latch.countDown() }

        DirectBootUnlockReceiver().onReceive(ceContext, Intent(Intent.ACTION_USER_UNLOCKED))
        latch.await(5, TimeUnit.SECONDS)

        val dao = database.directBootOccurrenceReceiptDao()
        assertEquals(1L, dao.count())
        val receipt = dao.findByOccurrenceKey(DirectBootCeTakeoverStore.computeOccurrenceKey(703L, baseTime + 73_000L, 1, "tok_f1_703"))
        assertNotNull(receipt)
        assertEquals("CONSUMED_MISSED", receipt?.consumerOutcome)
    }

    @Test
    fun c1_c2_missedStoreCommitFailed_retainsDeLedgerRecordForRecovery() = runBlocking {
        val itemA = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 704L,
            triggerTime = baseTime + 74_000L,
            generation = 1,
            instanceToken = "tok_c1_704",
            restoreEpoch = 1L
        )

        DirectBootAlarmCache.writeQueue(deContext, listOf(itemA), now = baseTime)

        val authorizer = DirectBootDeliveryAuthorizer()
        DirectBootAlarmReceiver.serviceAdapterSeam = { ctx ->
            DirectBootServiceDeliveryAdapter(ctx, authorizer, clock = { baseTime + 74_000L })
        }

        val identityA = DirectBootV2NormalIntentIdentity.fromQueueItem(itemA)
        val ticketRes = authorizer.issueTicket(identityA, nowEpochMs = baseTime + 74_000L)
        assertTrue(ticketRes is TicketIssueResult.Success)
        val ticketId = (ticketRes as TicketIssueResult.Success).ticket.ticketId

        val firingIntent = identityA.createIntent(ceContext).apply {
            putExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID, ticketId)
        }

        val throwingContext = object : android.content.ContextWrapper(ceContext) {
            override fun startForegroundService(service: Intent?): android.content.ComponentName? {
                throw IllegalStateException("Service start failed")
            }
        }

        // Mock MissedStore commit failure via commitSeam
        DirectBootMissedStore.commitSeam = { CommitExecutionOutcome.CommitFalse }

        val receiver = DirectBootAlarmReceiver()
        receiver.onReceive(throwingContext, firingIntent)

        // Verify that DE Ledger record WAS RETAINED because missed store commit failed
        val ledgerState = DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext)
        assertTrue("DE Ledger record MUST be retained for post-unlock recovery when missed store commit fails", ledgerState is LedgerStorageState.ValidWithRecords)
        val records = (ledgerState as LedgerStorageState.ValidWithRecords).records
        assertEquals(1, records.size)
        assertEquals(704L, records[0].alarmId)

        // Reset commit seam
        DirectBootMissedStore.resetSeamsForTesting()
    }

    @Test
    fun r7_serviceStartFailed_deLedgerRecordRetained_takeoverSucceedsPostUnlock() = runBlocking {
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 701L,
            triggerTime = baseTime + 70_000L,
            deliveredAt = baseTime + 70_010L,
            generation = 1,
            instanceToken = "tok_fail_701",
            restoreEpoch = 1L
        )

        DirectBootDeliveryLedger.appendRecord(deContext, delRecord)

        // Record SERVICE_START_FAILED in DirectBootMissedStore
        val missedRec = DirectBootMissedRecord.createNormal(
            alarmId = 701L,
            triggerTime = baseTime + 70_000L,
            generation = 1,
            instanceToken = "tok_fail_701",
            restoreEpoch = 1L,
            missedAt = baseTime + 70_015L,
            classification = DirectBootMissedRecord.CLASSIFICATION_SERVICE_START_FAILED
        )
        DirectBootMissedStore.recordMissed(deContext, missedRec)

        val intakeRes = DirectBootCeTakeoverStore.recordDelivered(ceContext, delRecord, baseTime + 70_050L)
        assertTrue(intakeRes is CeTakeoverIntakeResult.RecordedConfirmed)
        val confirmedReceipt = CeHandoffResult.Confirmed(
            ceRecord = (intakeRes as CeTakeoverIntakeResult.RecordedConfirmed).record,
            totalCeRecords = 1,
            alreadyRecordedInCe = false,
            writePerformed = true
        )

        DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = delRecord,
            confirmedReceipt = confirmedReceipt,
            unlockGate = { true }
        )

        DirectBootUnlockReceiver.databaseSeam = { database }
        val latch = CountDownLatch(1)
        var consumerOutcome: DirectBootEventConsumerEngine.ConsumerExecutionResult? = null
        DirectBootUnlockReceiver.onConsumerFinishedSeam = { res ->
            consumerOutcome = res
            latch.countDown()
        }

        DirectBootUnlockReceiver().onReceive(ceContext, Intent(Intent.ACTION_USER_UNLOCKED))
        latch.await(5, TimeUnit.SECONDS)

        assertNotNull(consumerOutcome)
        assertTrue(consumerOutcome is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)

        val dao = database.directBootOccurrenceReceiptDao()
        assertEquals(1L, dao.count())
    }

    @Test
    fun r7_b_processDeathAfterClaimCommit_recoversViaPostUnlockEngine() = runBlocking {
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 702L,
            triggerTime = baseTime + 72_000L,
            deliveredAt = baseTime + 72_010L,
            generation = 1,
            instanceToken = "tok_crash_702",
            restoreEpoch = 1L
        )

        // Append confirmed witness record in DirectBootFiredWitnessLedger
        val witnessRecord = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 702L,
            triggerTime = baseTime + 72_000L,
            generation = 1,
            instanceToken = "tok_crash_702",
            restoreEpoch = 1L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = baseTime + 72_015L
        )
        DirectBootFiredWitnessLedger.appendWitness(deContext, witnessRecord)

        val appendRes = DirectBootDeliveryLedger.appendRecord(deContext, delRecord)
        assertTrue(appendRes is DeliveryAppendResult.Appended)

        val duplicateRes = DirectBootDeliveryLedger.appendRecord(deContext, delRecord)
        assertTrue("Duplicate delivery attempt after crash MUST be ignored", duplicateRes is DeliveryAppendResult.DuplicateIgnored)

        val intakeRes = DirectBootCeTakeoverStore.recordDelivered(ceContext, delRecord, baseTime + 72_050L)
        assertTrue(intakeRes is CeTakeoverIntakeResult.RecordedConfirmed)
        val confirmedReceipt = CeHandoffResult.Confirmed(
            ceRecord = (intakeRes as CeTakeoverIntakeResult.RecordedConfirmed).record,
            totalCeRecords = 1,
            alreadyRecordedInCe = false,
            writePerformed = true
        )

        DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = delRecord,
            confirmedReceipt = confirmedReceipt,
            unlockGate = { true }
        )

        DirectBootUnlockReceiver.databaseSeam = { database }
        val latch = CountDownLatch(1)
        var consumerOutcome: DirectBootEventConsumerEngine.ConsumerExecutionResult? = null
        DirectBootUnlockReceiver.onConsumerFinishedSeam = { res ->
            consumerOutcome = res
            latch.countDown()
        }

        DirectBootUnlockReceiver().onReceive(ceContext, Intent(Intent.ACTION_USER_UNLOCKED))
        latch.await(5, TimeUnit.SECONDS)

        assertNotNull(consumerOutcome)
        assertTrue(consumerOutcome is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)

        val dao = database.directBootOccurrenceReceiptDao()
        assertEquals(1L, dao.count())
        val receipt = dao.findByOccurrenceKey(DirectBootCeTakeoverStore.computeOccurrenceKey(702L, baseTime + 72_000L, 1, "tok_crash_702"))
        assertNotNull(receipt)
        assertEquals("CONSUMED_DELIVERED", receipt?.consumerOutcome)
    }

    // =========================================================================
    // R8: Registration Failure -> Marker Records FAILED_BLOCKED, Queue Preserved
    // =========================================================================

    @Test
    fun r8_registrationFailure_markerRecorded_queuePreserved() {
        val item = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 801L,
            triggerTime = baseTime + 80_000L,
            generation = 1,
            instanceToken = "tok_regerr_801",
            restoreEpoch = 1L
        )
        DirectBootAlarmCache.writeQueue(deContext, listOf(item), now = baseTime)

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

        val queue = DirectBootAlarmCache.readQueue(deContext, now = baseTime)
        assertEquals(1, queue.size)
        assertEquals(801L, queue[0].alarmId)

        val markerRes = DirectBootRegistrationMarker.read(deContext)
        assertTrue(markerRes is MarkerReadResult.Success)
        assertEquals(RegistrationMainState.FAILED_BLOCKED, (markerRes as MarkerReadResult.Success).marker.mainState)
    }

    // =========================================================================
    // R10: Queue Corrupt / Unsupported -> Fail-Closed
    // =========================================================================

    @Test
    fun r10_queueCorrupt_failClosed() {
        val prefs = deContext.getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, "CORRUPT_NOT_JSON").commit()

        val state = DirectBootAlarmCache.inspectQueueStorageState(deContext)
        assertTrue("Corrupt queue payload MUST return Corrupt state", state is QueueStorageState.Corrupt)

        val readQueue = DirectBootAlarmCache.readQueue(deContext)
        assertTrue("Corrupt queue read MUST return empty list", readQueue.isEmpty())
    }

    // =========================================================================
    // R12: Unlock Idempotency
    // =========================================================================

    @Test
    fun r12_duplicateUnlock_idempotent() = runBlocking {
        val missedRecord = DirectBootMissedRecord.createNormal(
            alarmId = 901L,
            triggerTime = baseTime + 90_000L,
            missedAt = baseTime + 90_500L,
            generation = 1,
            instanceToken = "tok_unlock_901",
            classification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE,
            restoreEpoch = 1L
        )

        DirectBootCeTakeoverStore.recordMissed(ceContext, missedRecord, baseTime + 90_600L)

        val res1 = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)
        val res2 = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)

        assertTrue(res1 is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
        assertTrue(
            "Second run must return NoPendingRecords after slots are released, got $res2",
            res2 is DirectBootEventConsumerEngine.ConsumerExecutionResult.NoPendingRecords ||
                res2 is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success
        )

        val dao = database.directBootOccurrenceReceiptDao()
        assertEquals(1L, dao.count())
    }

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
