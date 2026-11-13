package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Integrated Fault Injection Test Suite for KNO-75 Phase D3-C2-P3D-P3-E4-INTEGRATED (F01 - F10).
 *
 * Exercises explicit fault injection points across DE storage, CE Takeover Store,
 * Qualified DE ACK, and Room 38 Receipt Consumer Engine transactions.
 */
@RunWith(RobolectricTestRunner::class)
class DirectBootIntegratedFaultInjectionTest {

    private lateinit var deContext: Context
    private lateinit var ceContext: Context
    private lateinit var database: AlarmDatabase
    private val baseTime = 1_700_000_000_000L

    private fun clearDeStorage() {
        val dePrefs = deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
        dePrefs.edit()
            .remove(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD)
            .remove(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD)
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

    // F01: Exception before CE Commit
    @Test
    fun f01_exceptionBeforeCeCommit_preservesDeRecordAndCeEmpty() {
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 1L,
            triggerTime = baseTime + 10_000L,
            deliveredAt = baseTime + 10_050L,
            generation = 1,
            instanceToken = "tok_f01",
            restoreEpoch = 1L
        )
        val appRes = DirectBootDeliveryLedger.appendRecord(deContext, delRecord)
        assertTrue("Precondition: appendRecord must succeed but got $appRes", appRes is DeliveryAppendResult.Appended)

        val result = DirectBootCeHandoffCoordinator.handoffDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = delRecord,
            recordedAtEpochMs = baseTime + 10_100L,
            unlockGate = { true },
            preCommitHook = { throw RuntimeException("Injected pre-commit failure F01") }
        )

        assertEquals(CeHandoffResult.Rejected.PreCommitFailure::class.java, result.javaClass)

        // Verify DE record remains preserved
        val deState = DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext)
        println("F01 DE STATE: $deState")
        assertTrue("F01 DE State expected ValidWithRecords but got $deState", deState is LedgerStorageState.ValidWithRecords)
        assertEquals(1, (deState as LedgerStorageState.ValidWithRecords).records.size)

        // Verify CE storage remains empty
        val ceState = DirectBootCeTakeoverStore.inspectStorageState(ceContext)
        assertTrue(ceState is CeTakeoverStorageState.Absent || ceState is CeTakeoverStorageState.ValidEmpty)
    }

    // F02: Stop after CE Commit, before DE ACK
    @Test
    fun f02_stopAfterCeCommitBeforeDeAck_retryCompletesDeAckCleanly() {
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 2L,
            triggerTime = baseTime + 20_000L,
            deliveredAt = baseTime + 20_050L,
            generation = 1,
            instanceToken = "tok_f02",
            restoreEpoch = 1L
        )
        DirectBootDeliveryLedger.appendRecord(deContext, delRecord)

        // Stage 1: CE Intake succeeds (CE record present)
        val intakeRes = DirectBootCeTakeoverStore.recordDelivered(ceContext, delRecord, baseTime + 20_100L)
        assertTrue(intakeRes is CeTakeoverIntakeResult.RecordedConfirmed)
        val confirmedReceipt = CeHandoffResult.Confirmed(
            ceRecord = (intakeRes as CeTakeoverIntakeResult.RecordedConfirmed).record,
            totalCeRecords = 1,
            alreadyRecordedInCe = false,
            writePerformed = true
        )

        // Verify state prior to DE ACK (C2 Boundary)
        val deStateBefore = DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext)
        assertTrue(deStateBefore is LedgerStorageState.ValidWithRecords)
        assertEquals(1, (deStateBefore as LedgerStorageState.ValidWithRecords).records.size)

        // Stage 2: Retry/ACK execution retires DE record
        val ackRes = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = delRecord,
            confirmedReceipt = confirmedReceipt,
            unlockGate = { true }
        )
        assertTrue("F02: Qualified DE ACK retires target DE record", ackRes is CeQualifiedAckResult.AcknowledgedConfirmed)

        // Verify DE record retired
        val deStateAfter = DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext)
        assertTrue(deStateAfter is LedgerStorageState.ValidEmpty)
    }

    // F03: Stop after DE ACK, before Room Transaction
    @Test
    fun f03_stopAfterDeAckBeforeRoomTx_recoversAndExecutesRoomTx() = runBlocking {
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 3L,
            triggerTime = baseTime + 30_000L,
            deliveredAt = baseTime + 30_050L,
            generation = 1,
            instanceToken = "tok_f03",
            restoreEpoch = 1L
        )

        DirectBootDeliveryLedger.appendRecord(deContext, delRecord)

        // Handoff & ACK complete: DE retired, CE active record present, Room receipt empty
        val handoffRes = DirectBootCeHandoffCoordinator.handoffAndAcknowledgeDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = delRecord,
            recordedAtEpochMs = baseTime + 30_100L,
            unlockGate = { true }
        )
        assertTrue("Handoff & ACK must succeed but got $handoffRes", handoffRes is CeQualifiedAckResult.AcknowledgedConfirmed)

        val deState = DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext)
        assertTrue(deState is LedgerStorageState.ValidEmpty)

        // Room DB empty prior to consumer execution
        val dao = database.directBootOccurrenceReceiptDao()
        assertEquals(0L, dao.count())

        // Post-unlock Consumer execution recovers and commits Room Tx
        val result = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)
        assertTrue("F03: Expected Success but got $result", result is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)

        // Verify Room Receipt committed & CE slot released
        assertEquals(1L, dao.count())
        val ceState = DirectBootCeTakeoverStore.inspectStorageState(ceContext)
        assertTrue(ceState is CeTakeoverStorageState.ValidEmpty)
    }

    // F04: Exception during Room Transaction
    @Test
    fun f04_exceptionDuringRoomTx_rollsBackTransactionAtomically() = runBlocking {
        val missedRecord = DirectBootMissedRecord.createNormal(
            alarmId = 4L,
            triggerTime = baseTime + 40_000L,
            missedAt = baseTime + 40_500L,
            generation = 1,
            instanceToken = "tok_f04",
            classification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE,
            restoreEpoch = 1L
        )

        DirectBootCeTakeoverStore.recordMissed(ceContext, missedRecord, baseTime + 40_600L)

        // Simulated crash during Room Tx (invalid/closed database call or exception)
        val ceRecord = (DirectBootCeTakeoverStore.inspectStorageState(ceContext) as CeTakeoverStorageState.ValidWithRecords).records.first()

        // Execute inside bad transaction to test rollback
        try {
            database.runInTransaction {
                database.directBootOccurrenceReceiptDao().insert(
                    DirectBootOccurrenceReceipt(
                        occurrenceKey = ceRecord.occurrenceKey,
                        sourceRecordId = ceRecord.sourceRecordId,
                        recordType = ceRecord.recordType,
                        alarmId = ceRecord.alarmId,
                        triggerTime = ceRecord.triggerTime,
                        generation = ceRecord.generation,
                        instanceToken = ceRecord.instanceToken,
                        restoreEpoch = 1L,
                        sourcePayloadHash = "hash_f04",
                        consumerOutcome = "CONSUMED_MISSED",
                        completedAt = baseTime + 40_500L
                    )
                )
                throw RuntimeException("Injected transaction failure F04")
            }
        } catch (_: Exception) {
            // Expected rollback
        }

        // Verify 0 receipt rows in Room DB
        val dao = database.directBootOccurrenceReceiptDao()
        assertEquals(0L, dao.count())

        // Verify CE slot remains preserved in CE Active
        val ceState = DirectBootCeTakeoverStore.inspectStorageState(ceContext)
        assertTrue(ceState is CeTakeoverStorageState.ValidWithRecords)
    }

    // F05 & F06: Stop after Room Commit, before CE Release / Release Retry
    @Test
    fun f05_f06_stopAfterRoomCommitBeforeCeRelease_retryReleasesCeSlotIdempotently() = runBlocking {
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 5L,
            triggerTime = baseTime + 50_000L,
            deliveredAt = baseTime + 50_050L,
            generation = 1,
            instanceToken = "tok_f05",
            provenance = "",
            restoreEpoch = 1L
        )

        DirectBootCeTakeoverStore.recordDelivered(ceContext, delRecord, baseTime + 50_100L)

        // Room Tx commits receipt directly
        val occKey = DirectBootCeTakeoverStore.computeOccurrenceKey(5L, baseTime + 50_000L, 1, "tok_f05")
        val hash = DirectBootEventConsumerEngine.calculateCanonicalPayloadHashV2(
            sourceRecordId = delRecord.recordId,
            recordType = "DELIVERED",
            alarmId = 5L,
            triggerTime = baseTime + 50_000L,
            eventTimestamp = baseTime + 50_050L,
            generation = 1,
            instanceToken = "tok_f05",
            restoreEpoch = 1L,
            metadataString = ""
        )

        val receipt = DirectBootOccurrenceReceipt(
            occurrenceKey = occKey,
            sourceRecordId = delRecord.recordId,
            recordType = "DELIVERED",
            alarmId = 5L,
            triggerTime = baseTime + 50_000L,
            generation = 1,
            instanceToken = "tok_f05",
            restoreEpoch = 1L,
            sourcePayloadHash = hash,
            consumerOutcome = "CONSUMED_DELIVERED",
            completedAt = baseTime + 50_050L
        )
        database.directBootOccurrenceReceiptDao().insert(receipt)

        // Simulate crash before release: Room has receipt, CE still has slot
        val ceStateBefore = DirectBootCeTakeoverStore.inspectStorageState(ceContext)
        assertTrue(ceStateBefore is CeTakeoverStorageState.ValidWithRecords)

        // Consumer re-run detects AlreadyConsumedReceipt and releases CE slot
        val outcome = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)
        assertTrue("F05: Expected Success but got $outcome", outcome is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)

        // Verify CE slot is now released & single receipt remains in DB
        val ceStateAfter = DirectBootCeTakeoverStore.inspectStorageState(ceContext)
        assertTrue(ceStateAfter is CeTakeoverStorageState.ValidEmpty)
        assertEquals(1L, database.directBootOccurrenceReceiptDao().count())
    }

    // F07: 2 Concurrent Consumers
    @Test
    fun f07_concurrentConsumers_serializesCleanlyWithoutDuplicateReceipts() = runBlocking {
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 7L,
            triggerTime = baseTime + 70_000L,
            deliveredAt = baseTime + 70_050L,
            generation = 1,
            instanceToken = "tok_f07",
            restoreEpoch = 1L
        )

        DirectBootCeTakeoverStore.recordDelivered(ceContext, delRecord, baseTime + 70_100L)

        val executor = Executors.newFixedThreadPool(2)
        val task1 = executor.submit<DirectBootEventConsumerEngine.ConsumerExecutionResult> {
            runBlocking { DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database) }
        }
        val task2 = executor.submit<DirectBootEventConsumerEngine.ConsumerExecutionResult> {
            runBlocking { DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database) }
        }

        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        val res1 = task1.get()
        val res2 = task2.get()

        assertTrue(res1 is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success || res1 is DirectBootEventConsumerEngine.ConsumerExecutionResult.NoPendingRecords)
        assertTrue(res2 is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success || res2 is DirectBootEventConsumerEngine.ConsumerExecutionResult.NoPendingRecords)

        // Verify exactly 1 receipt in DB
        assertEquals(1L, database.directBootOccurrenceReceiptDao().count())
    }

    // F08: Duplicate Source Re-injection
    @Test
    fun f08_duplicateSourceReinjection_returnsAlreadyConsumedWithoutDuplicateWrites() = runBlocking {
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 8L,
            triggerTime = baseTime + 80_000L,
            deliveredAt = baseTime + 80_050L,
            generation = 1,
            instanceToken = "tok_f08",
            restoreEpoch = 1L
        )

        DirectBootCeTakeoverStore.recordDelivered(ceContext, delRecord, baseTime + 80_100L)
        DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)

        // Re-inject duplicate record to CE
        DirectBootCeTakeoverStore.recordDelivered(ceContext, delRecord, baseTime + 80_200L)

        val ceRecord = (DirectBootCeTakeoverStore.inspectStorageState(ceContext) as CeTakeoverStorageState.ValidWithRecords).records.first()
        val outcome = DirectBootEventConsumerEngine.processSingleRecord(ceContext, database, ceRecord)

        assertTrue("Duplicate source re-injection returns AlreadyConsumed", outcome is DirectBootEventConsumerEngine.RecordConsumptionOutcome.AlreadyConsumed)
        assertEquals(1L, database.directBootOccurrenceReceiptDao().count())
    }

    // F09: Same OccurrenceKey, Different Payload / Epoch
    @Test
    fun f09_sameOccurrenceKeyDifferentPayload_returnsPayloadConflict() = runBlocking {
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 9L,
            triggerTime = baseTime + 90_000L,
            deliveredAt = baseTime + 90_050L,
            generation = 1,
            instanceToken = "tok_f09",
            restoreEpoch = 1L
        )

        DirectBootCeTakeoverStore.recordDelivered(ceContext, delRecord, baseTime + 90_100L)
        DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)

        // Re-inject receipt into DB with modified hash to simulate payload conflict
        val occKey = DirectBootCeTakeoverStore.computeOccurrenceKey(9L, baseTime + 90_000L, 1, "tok_f09")
        val conflictingCeRecord = CeTakeoverRecord.Delivered(
            occurrenceKey = occKey,
            sourceRecordId = delRecord.recordId,
            kind = "NORMAL",
            alarmId = 9L,
            triggerTime = baseTime + 90_000L,
            deliveredAt = baseTime + 90_999L, // Different timestamp
            generation = 1,
            instanceToken = "tok_f09",
            provenance = "CONFLICT",
            restoreEpoch = 2L, // Different restoreEpoch
            recordedAtEpochMs = baseTime + 91_000L
        )

        val outcome = DirectBootEventConsumerEngine.processSingleRecord(ceContext, database, conflictingCeRecord)
        assertTrue("Conflicting payload returns PayloadConflict", outcome is DirectBootEventConsumerEngine.RecordConsumptionOutcome.PayloadConflict)
    }

    // F10: Old Source Re-injection after DB Restore
    @Test
    fun f10_oldSourceReinjectionAfterRestore_returnsRestoreEpochConflictAndPreservesSlot() = runBlocking {
        // Legacy v1 record or pre-restore record with restoreEpoch = 0L
        val oldRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 10L,
            triggerTime = baseTime + 100_000L,
            deliveredAt = baseTime + 100_050L,
            generation = 1,
            instanceToken = "tok_pre_restore",
            restoreEpoch = 0L,
            schemaVersion = 1
        )

        DirectBootCeTakeoverStore.recordDelivered(ceContext, oldRecord, baseTime + 100_100L)

        val outcome = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)
        assertTrue(outcome is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)

        // Verify receipt recorded with RESTORE_EPOCH_CONFLICT
        val dao = database.directBootOccurrenceReceiptDao()
        val occKey = DirectBootCeTakeoverStore.computeOccurrenceKey(10L, baseTime + 100_000L, 1, "tok_pre_restore")
        val receipt = dao.findByOccurrenceKey(occKey)
        assertNotNull(receipt)
        assertEquals("RESTORE_EPOCH_CONFLICT", receipt?.consumerOutcome)

        // Verify CE slot preserved (quarantined)
        val ceState = DirectBootCeTakeoverStore.inspectStorageState(ceContext)
        assertTrue(ceState is CeTakeoverStorageState.ValidWithRecords)
    }
}
