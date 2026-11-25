package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt
import com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate
import com.sysadmindoc.alarmclock.domain.ProductionSchedulingRoute
import com.sysadmindoc.alarmclock.domain.ProductionSchedulingRouteAuthority
import com.sysadmindoc.alarmclock.receiver.BootReceiver
import com.sysadmindoc.alarmclock.worker.AlarmHealthWorker
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exact Identity Collision Discriminator & P4 Unlock Handoff Integration Test Suite for W3 CE Consumer Integration
 * (KNO-75 Phase C7-V2-W3-EXACT-COLLISION-DISCRIMINATOR & G4-STEP9-P4-UNLOCK-HANDOFF-OFFLINE-IMPLEMENTATION).
 *
 * Verifies Hostile Collision Discriminator Tests H1 & H2 plus P4 Unlock Receiver DE->CE Handoff,
 * Room 38 receipt commit, Witness/DE/CE retirement, SERVICE_START_FAILED co-retirement,
 * Fail-closed protection, and Flag OFF / LEGACY_ONLY preservation.
 */
@RunWith(RobolectricTestRunner::class)
class DirectBootW3ConsumerIntegrationRedTest {

    private lateinit var context: Context
    private lateinit var deContext: Context
    private lateinit var ceContext: Context
    private lateinit var database: AlarmDatabase
    private val baseTime = 1_700_000_000_000L

    private fun clearStorage() {
        val dePrefs = deContext.getSharedPreferences(DirectBootFiredWitnessLedger.PREFS_NAME, Context.MODE_PRIVATE)
        dePrefs.edit().clear().commit()
        val delPrefs = deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
        delPrefs.edit().clear().commit()
        val missedPrefs = deContext.getSharedPreferences(DirectBootMissedStore.PREFS_NAME, Context.MODE_PRIVATE)
        missedPrefs.edit().clear().commit()
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deContext = context.createDeviceProtectedStorageContext()
        ceContext = context
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        DirectBootCeTakeoverStore.unlockGateSeam = { true }
        DirectBootCeTakeoverStore.clear(ceContext)
        DirectBootFiredWitnessLedger.resetSeamsForTesting()
        DirectBootDeliveryLedger.resetSeamsForTesting()
        DirectBootMissedStore.resetSeamsForTesting()
        DirectBootUnlockReceiver.resetSeamsForTesting()
        SharedOutputAuthorityGate.getInstance(ceContext).isEnabled = false
        clearStorage()
    }

    @After
    fun tearDown() {
        database.close()
        DirectBootCeTakeoverStore.clear(ceContext)
        DirectBootCeTakeoverStore.resetSeamsForTesting()
        DirectBootFiredWitnessLedger.resetSeamsForTesting()
        DirectBootDeliveryLedger.resetSeamsForTesting()
        DirectBootMissedStore.resetSeamsForTesting()
        DirectBootUnlockReceiver.resetSeamsForTesting()
        SharedOutputAuthorityGate.getInstance(ceContext).isEnabled = false
        clearStorage()
    }

    // =========================================================================
    // W3-RED-1: Absent Witness MUST NOT Resolve to DELIVERED or MISSED (DEFER_AND_RETAIN RED)
    // =========================================================================

    @Test
    fun w3_red1_t1_t2_absentOrUnknownWitness_mustDeferAndRetainWithoutTerminalReceipt() = runBlocking {
        val alarmId = 101L
        val triggerTime = baseTime + 10_000L
        val gen = 1
        val token = "tok_w3_101"
        val epoch = 1L

        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            deliveredAt = baseTime + 10_010L,
            generation = gen,
            instanceToken = token,
            restoreEpoch = epoch
        )
        DirectBootDeliveryLedger.appendRecord(deContext, delRecord)

        DirectBootCeTakeoverStore.recordDelivered(ceContext, delRecord, baseTime + 10_020L)

        val result = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)
        assertTrue("Takeover execution MUST succeed", result is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)

        val dao = database.directBootOccurrenceReceiptDao()
        val key = DirectBootCeTakeoverStore.computeOccurrenceKey(alarmId, triggerTime, gen, token)
        val receipt = dao.findByOccurrenceKey(key)

        assertNull(
            "W3-RED-1/T1/T2 CONFIRMED: Room 38 Receipt MUST BE NULL (DEFERRED) when Witness Record is ABSENT",
            receipt
        )
    }

    // =========================================================================
    // H1: Exact 4-Key Collision with Different Restore Epoch Test
    // =========================================================================

    @Test
    fun h1_exactFourKeyCollision_differentEpoch_falsification() = runBlocking {
        val alarmId = 1001L
        val triggerTime = baseTime + 100_000L
        val gen = 1
        val token = "tok_exact_collision"
        val epoch1 = 1L
        val epoch2 = 2L

        // 1. Record SERVICE_START_FAILED in Epoch 1 (same 4 keys: alarmId, triggerTime, gen, token, epoch1 = 1L)
        val missedEpoch1 = DirectBootMissedRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = gen,
            instanceToken = token,
            restoreEpoch = epoch1,
            missedAt = baseTime + 100_010L,
            classification = DirectBootMissedRecord.CLASSIFICATION_SERVICE_START_FAILED
        )
        DirectBootMissedStore.recordMissed(deContext, missedEpoch1)

        // 2. Candidate Delivered record in Epoch 2 (same 4 keys: alarmId, triggerTime, gen, token, epoch2 = 2L)
        val delRecordEpoch2 = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId, triggerTime = triggerTime, deliveredAt = baseTime + 100_020L,
            generation = gen, instanceToken = token, restoreEpoch = epoch2
        )
        DirectBootDeliveryLedger.appendRecord(deContext, delRecordEpoch2)

        // Witness record for Epoch 2 is ACCEPTED
        val witnessRecordEpoch2 = DirectBootFiredWitnessRecord.createNormal(
            alarmId = alarmId, triggerTime = triggerTime, generation = gen, instanceToken = token, restoreEpoch = epoch2,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = baseTime + 100_025L
        )
        DirectBootFiredWitnessLedger.appendWitness(deContext, witnessRecordEpoch2)

        // Record Delivered in CE Takeover Store for Epoch 2
        DirectBootCeTakeoverStore.recordDelivered(ceContext, delRecordEpoch2, baseTime + 100_030L)

        // 3. Process Epoch 2 candidate
        DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)

        // 4. FALSIFICATION ASSERTION:
        // Epoch 2 candidate MUST resolve to CONSUMED_DELIVERED because witness is ACCEPTED for epoch2.
        // Epoch 1's SERVICE_START_FAILED MUST NOT falsely cause Epoch 2 to resolve as CONSUMED_MISSED!
        val dao = database.directBootOccurrenceReceiptDao()
        val keyEpoch2 = DirectBootCeTakeoverStore.computeOccurrenceKey(alarmId, triggerTime, gen, token)
        val receiptEpoch2 = dao.findByOccurrenceKey(keyEpoch2)

        assertNotNull("Receipt for Epoch 2 MUST be committed", receiptEpoch2)
        assertEquals("Epoch 2 restoreEpoch MUST match receipt", epoch2, receiptEpoch2?.restoreEpoch)
        assertEquals(
            "FALSIFICATION DISCOVERY: Epoch 1 SERVICE_START_FAILED MUST NOT cause Epoch 2 delivery to resolve as CONSUMED_MISSED",
            "CONSUMED_DELIVERED",
            receiptEpoch2?.consumerOutcome
        )
    }

    // =========================================================================
    // H2: Canonical Hash Match with Different Restore Epoch Test
    // =========================================================================

    @Test
    fun h2_exactCanonicalHashCollision_differentEpoch_falsification() = runBlocking {
        val alarmId = 1002L
        val triggerTime = baseTime + 102_000L
        val gen = 1
        val token = "tok_exact_hash_h2"
        val epoch1 = 1L
        val epoch2 = 2L

        // 1. Create candidate record for epoch1 and process to commit Room 38 receipt
        val delRecordEpoch1 = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId, triggerTime = triggerTime, deliveredAt = baseTime + 102_010L,
            generation = gen, instanceToken = token, restoreEpoch = epoch1
        )
        val intakeRes1 = DirectBootCeTakeoverStore.recordDelivered(ceContext, delRecordEpoch1, baseTime + 102_020L)
        assertTrue(intakeRes1 is CeTakeoverIntakeResult.RecordedConfirmed)
        val ceRecord1 = (intakeRes1 as CeTakeoverIntakeResult.RecordedConfirmed).record

        val witnessRecordEpoch1 = DirectBootFiredWitnessRecord.createNormal(
            alarmId = alarmId, triggerTime = triggerTime, generation = gen, instanceToken = token, restoreEpoch = epoch1,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = baseTime + 102_015L
        )
        DirectBootFiredWitnessLedger.appendWitness(deContext, witnessRecordEpoch1)

        val outcome1 = DirectBootEventConsumerEngine.processSingleRecord(ceContext, database, ceRecord1)
        assertTrue(outcome1 is DirectBootEventConsumerEngine.RecordConsumptionOutcome.ConsumedDelivered)

        // Clear witness ledger & takeover store for epoch2 candidate
        DirectBootCeTakeoverStore.clear(ceContext)
        DirectBootFiredWitnessLedger.resetSeamsForTesting()
        clearStorage()

        // 2. Candidate record for Epoch 2 (same 4 keys, restoreEpoch = 2L)
        val delRecordEpoch2 = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId, triggerTime = triggerTime, deliveredAt = baseTime + 102_010L,
            generation = gen, instanceToken = token, restoreEpoch = epoch2
        )
        DirectBootDeliveryLedger.appendRecord(deContext, delRecordEpoch2)

        val intakeRes2 = DirectBootCeTakeoverStore.recordDelivered(ceContext, delRecordEpoch2, baseTime + 102_030L)
        assertTrue(intakeRes2 is CeTakeoverIntakeResult.RecordedConfirmed)
        val ceRecord2 = (intakeRes2 as CeTakeoverIntakeResult.RecordedConfirmed).record

        // 3. Process Epoch 2 candidate against existing Room 38 receipt (which has epoch1 = 1L)
        val outcome2 = DirectBootEventConsumerEngine.processSingleRecord(ceContext, database, ceRecord2)

        // 4. FALSIFICATION ASSERTION:
        // Candidate Epoch 2 (restoreEpoch = 2L) MUST NOT be treated as AlreadyConsumed against existing Receipt Epoch 1 (1L).
        // It MUST return PayloadConflict because restoreEpoch (2L vs 1L) differs!
        assertTrue(
            "FALSIFICATION DISCOVERY: Candidate Epoch 2 MUST return PayloadConflict against existing Receipt Epoch 1",
            outcome2 is DirectBootEventConsumerEngine.RecordConsumptionOutcome.PayloadConflict
        )

        // 5. DE ACK MUST be skipped
        val deState = DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext)
        assertTrue(deState is LedgerStorageState.ValidWithRecords)
        assertEquals("DE Delivery Record for Epoch 2 MUST NOT be ACKed on PayloadConflict", 1, (deState as LedgerStorageState.ValidWithRecords).records.size)
    }

    // =========================================================================
    // W3F-01: SERVICE_START_FAILED RestoreEpoch Fencing Test (GREEN)
    // =========================================================================

    @Test
    fun w3f_01_serviceStartFailed_restoreEpochFence() = runBlocking {
        val alarmId = 501L
        val triggerTime = baseTime + 50_000L

        val ceRecord = CeTakeoverRecord.Delivered(
            occurrenceKey = "NORMAL_501_gen0__trig$triggerTime",
            sourceRecordId = "delivery_NORMAL_501_gen0__trig$triggerTime",
            kind = "NORMAL",
            alarmId = alarmId,
            triggerTime = triggerTime,
            deliveredAt = baseTime + 50_010L,
            generation = 0,
            instanceToken = "",
            provenance = "V2_INTENT_DELIVERY",
            restoreEpoch = 0L,
            recordedAtEpochMs = baseTime + 50_020L
        )

        val outcome = DirectBootEventConsumerEngine.processSingleRecord(ceContext, database, ceRecord)
        assertTrue("W3F-01: Record with restoreEpoch=0L MUST return RestoreEpochConflict", outcome is DirectBootEventConsumerEngine.RecordConsumptionOutcome.RestoreEpochConflict)
    }

    // =========================================================================
    // P4 Helper
    // =========================================================================

    private fun dispatchUnlockAndAwait(): DirectBootEventConsumerEngine.ConsumerExecutionResult {
        DirectBootUnlockReceiver.databaseSeam = { database }
        val latch = CountDownLatch(1)
        var capturedResult: DirectBootEventConsumerEngine.ConsumerExecutionResult? = null
        DirectBootUnlockReceiver.onConsumerFinishedSeam = { res ->
            capturedResult = res
            latch.countDown()
        }
        DirectBootUnlockReceiver().onReceive(ceContext, Intent(Intent.ACTION_USER_UNLOCKED))
        assertTrue("DirectBootUnlockReceiver did not finish within 5s", latch.await(5, TimeUnit.SECONDS))
        return requireNotNull(capturedResult)
    }

    // =========================================================================
    // P4-1: DE DeliveryLedger + FiredWitness -> CE Handoff -> Room 38 -> Full Retirement
    // =========================================================================

    @Test
    fun p4_unlockReceiver_deliveredAndWitness_autoHandoffsCommitsRoom38AndRetiresAllStores() = runBlocking {
        val rec1 = DirectBootDeliveryRecord.createNormal(
            alarmId = 2001L,
            triggerTime = baseTime + 10_000L,
            deliveredAt = baseTime + 10_050L,
            generation = 1,
            instanceToken = "tok_p4_2001",
            restoreEpoch = 2L
        )
        val rec2 = DirectBootDeliveryRecord.createNormal(
            alarmId = 2002L,
            triggerTime = baseTime + 20_000L,
            deliveredAt = baseTime + 20_050L,
            generation = 1,
            instanceToken = "tok_p4_2002",
            restoreEpoch = 2L
        )
        assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, rec1) is DeliveryAppendResult.Appended)
        assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, rec2) is DeliveryAppendResult.Appended)

        val w1 = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 2001L,
            triggerTime = baseTime + 10_000L,
            generation = 1,
            instanceToken = "tok_p4_2001",
            restoreEpoch = 2L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = baseTime + 10_060L
        )
        val w2 = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 2002L,
            triggerTime = baseTime + 20_000L,
            generation = 1,
            instanceToken = "tok_p4_2002",
            restoreEpoch = 2L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_ONLY,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_PARTIAL_OUTPUT_ACCEPTED,
            observedAtEpochMs = baseTime + 20_060L
        )
        assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, w1) is WitnessAppendResult.Appended)
        assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, w2) is WitnessAppendResult.Appended)

        // Precondition: CE TakeoverStore is empty (no manual bridge)
        assertTrue(DirectBootCeTakeoverStore.inspectStorageState(ceContext) is CeTakeoverStorageState.Absent)

        val res = dispatchUnlockAndAwait()
        assertTrue("Expected ConsumerExecutionResult.Success, got $res", res is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
        val success = res as DirectBootEventConsumerEngine.ConsumerExecutionResult.Success
        assertEquals(2, success.processedCount)
        assertEquals(2, success.receiptsCommitted)

        val dao = database.directBootOccurrenceReceiptDao()
        assertEquals(2L, dao.count())
        val r1 = dao.findByOccurrenceKey(DirectBootCeTakeoverStore.computeOccurrenceKey(2001L, baseTime + 10_000L, 1, "tok_p4_2001"))
        val r2 = dao.findByOccurrenceKey(DirectBootCeTakeoverStore.computeOccurrenceKey(2002L, baseTime + 20_000L, 1, "tok_p4_2002"))
        assertEquals("CONSUMED_DELIVERED", r1?.consumerOutcome)
        assertEquals("CONSUMED_DELIVERED", r2?.consumerOutcome)
        assertEquals(2L, r1?.restoreEpoch)
        assertEquals(2L, r2?.restoreEpoch)

        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.ValidEmpty)
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)
        assertTrue(DirectBootCeTakeoverStore.inspectStorageState(ceContext) is CeTakeoverStorageState.ValidEmpty)
    }

    // =========================================================================
    // P4-2: DE MissedStore (OVERDUE) -> CE Handoff -> CONSUMED_MISSED -> Retirement
    // =========================================================================

    @Test
    fun p4_unlockReceiver_missedStoreOverdue_autoHandoffsCommitsConsumedMissedAndRetires() = runBlocking {
        val missed = DirectBootMissedRecord.createNormal(
            alarmId = 2003L,
            triggerTime = baseTime + 30_000L,
            missedAt = baseTime + 90_000L,
            generation = 2,
            instanceToken = "tok_p4_2003",
            classification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE,
            restoreEpoch = 3L
        )
        assertTrue(DirectBootMissedStore.recordMissed(deContext, missed) is MissedRecordResult.Recorded)

        val res = dispatchUnlockAndAwait()
        assertTrue("Expected Success, got $res", res is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
        assertEquals(1, (res as DirectBootEventConsumerEngine.ConsumerExecutionResult.Success).receiptsCommitted)

        val dao = database.directBootOccurrenceReceiptDao()
        assertEquals(1L, dao.count())
        val receipt = dao.findByOccurrenceKey(DirectBootCeTakeoverStore.computeOccurrenceKey(2003L, baseTime + 30_000L, 2, "tok_p4_2003"))
        assertEquals("CONSUMED_MISSED", receipt?.consumerOutcome)
        assertEquals(3L, receipt?.restoreEpoch)

        assertTrue(DirectBootMissedStore.readUnacknowledgedRecords(deContext) is MissedStoreStorageState.ValidEmpty)
        assertTrue(DirectBootCeTakeoverStore.inspectStorageState(ceContext) is CeTakeoverStorageState.ValidEmpty)
    }

    // =========================================================================
    // P4-3: SERVICE_START_FAILED Coexistence -> Handoff -> CONSUMED_MISSED -> Dual Co-Retirement
    // =========================================================================

    @Test
    fun p4_unlockReceiver_serviceStartFailedCoexistence_autoHandoffsAndCoRetiresBothDeStores() = runBlocking {
        val alarmId = 2004L
        val triggerTime = baseTime + 40_000L
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            deliveredAt = baseTime + 40_010L,
            generation = 1,
            instanceToken = "tok_p4_2004",
            restoreEpoch = 2L
        )
        val missedFail = DirectBootMissedRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            missedAt = baseTime + 40_020L,
            generation = 1,
            instanceToken = "tok_p4_2004",
            classification = DirectBootMissedRecord.CLASSIFICATION_SERVICE_START_FAILED,
            restoreEpoch = 2L
        )
        val witnessSlot = DirectBootFiredWitnessRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = 1,
            instanceToken = "tok_p4_2004",
            restoreEpoch = 2L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_STATUS_UNKNOWN,
            observedAtEpochMs = baseTime + 40_015L
        )
        assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, delRecord) is DeliveryAppendResult.Appended)
        assertTrue(DirectBootMissedStore.recordMissed(deContext, missedFail) is MissedRecordResult.Recorded)
        assertTrue(DirectBootFiredWitnessLedger.reserveWitnessSlot(deContext, witnessSlot) is WitnessAppendResult.Appended)

        val res = dispatchUnlockAndAwait()
        assertTrue("Expected Success on SERVICE_START_FAILED co-retirement, got $res", res is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
        assertEquals(1, (res as DirectBootEventConsumerEngine.ConsumerExecutionResult.Success).receiptsCommitted)

        val dao = database.directBootOccurrenceReceiptDao()
        assertEquals(1L, dao.count())
        val receipt = dao.findByOccurrenceKey(DirectBootCeTakeoverStore.computeOccurrenceKey(alarmId, triggerTime, 1, "tok_p4_2004"))
        assertEquals("CONSUMED_MISSED", receipt?.consumerOutcome)

        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.ValidEmpty)
        assertTrue(DirectBootMissedStore.readUnacknowledgedRecords(deContext) is MissedStoreStorageState.ValidEmpty)
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)
        assertTrue(DirectBootCeTakeoverStore.inspectStorageState(ceContext) is CeTakeoverStorageState.ValidEmpty)
    }

    // =========================================================================
    // P4-4: UNKNOWN Witness Defer & Retain -> Converges After Witness Confirmation
    // =========================================================================

    @Test
    fun p4_unlockReceiver_unknownWitness_defersRetainsWithoutReceipt_thenConvergesWhenConfirmed() = runBlocking {
        val alarmId = 2005L
        val triggerTime = baseTime + 50_000L
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            deliveredAt = baseTime + 50_010L,
            generation = 1,
            instanceToken = "tok_p4_2005",
            restoreEpoch = 2L
        )
        val unknownWitness = DirectBootFiredWitnessRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = 1,
            instanceToken = "tok_p4_2005",
            restoreEpoch = 2L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_STATUS_UNKNOWN,
            observedAtEpochMs = baseTime + 50_015L
        )
        assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, delRecord) is DeliveryAppendResult.Appended)
        assertTrue(DirectBootFiredWitnessLedger.reserveWitnessSlot(deContext, unknownWitness) is WitnessAppendResult.Appended)

        // First unlock with UNKNOWN witness: fail-closed defer & retain
        dispatchUnlockAndAwait()
        val dao = database.directBootOccurrenceReceiptDao()
        assertEquals("UNKNOWN witness MUST NOT emit Room 38 receipt", 0L, dao.count())
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.ValidWithRecords)
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidWithRecords)

        // Confirm witness outcome and replay unlock -> converges cleanly
        assertTrue(
            DirectBootFiredWitnessLedger.updateWitnessOutcome(
                context = deContext,
                recordId = unknownWitness.recordId,
                deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
                deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED
            ) is WitnessUpdateResult.Updated
        )
        val res2 = dispatchUnlockAndAwait()
        assertTrue("Expected Success after witness confirmation, got $res2", res2 is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
        assertEquals(1L, dao.count())
        assertEquals(
            "CONSUMED_DELIVERED",
            dao.findByOccurrenceKey(DirectBootCeTakeoverStore.computeOccurrenceKey(alarmId, triggerTime, 1, "tok_p4_2005"))?.consumerOutcome
        )
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.ValidEmpty)
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)
        assertTrue(DirectBootCeTakeoverStore.inspectStorageState(ceContext) is CeTakeoverStorageState.ValidEmpty)
    }

    // =========================================================================
    // P4-5: Duplicate Unlock & Mid-Retirement Crash Recovery -> Exactly-Once Receipt
    // =========================================================================

    @Test
    fun p4_unlockReceiver_duplicateUnlockAndMidCrashRetry_exactlyOneReceiptAndCompletesRetirement() = runBlocking {
        val alarmId = 2006L
        val triggerTime = baseTime + 60_000L
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            deliveredAt = baseTime + 60_010L,
            generation = 1,
            instanceToken = "tok_p4_2006",
            restoreEpoch = 2L
        )
        val witness = DirectBootFiredWitnessRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = 1,
            instanceToken = "tok_p4_2006",
            restoreEpoch = 2L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = baseTime + 60_015L
        )
        assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, delRecord) is DeliveryAppendResult.Appended)
        assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, witness) is WitnessAppendResult.Appended)

        // Crash simulation: DE ACK commit fails after Room 38 receipt is committed
        DirectBootDeliveryLedger.commitSeam = { _, _ -> false }
        val crashRes = dispatchUnlockAndAwait()
        assertTrue("Expected IncompleteRetirement on DE ACK commit failure, got $crashRes", crashRes is DirectBootEventConsumerEngine.ConsumerExecutionResult.IncompleteRetirement)

        val dao = database.directBootOccurrenceReceiptDao()
        assertEquals(1L, dao.count())
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.ValidWithRecords)
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidWithRecords)
        assertTrue(DirectBootCeTakeoverStore.inspectStorageState(ceContext) is CeTakeoverStorageState.ValidWithRecords)

        // Recover & replay unlock twice
        DirectBootDeliveryLedger.resetSeamsForTesting()
        val retryRes = dispatchUnlockAndAwait()
        assertTrue("Expected Success on recovery unlock, got $retryRes", retryRes is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)

        val duplicateUnlockRes = dispatchUnlockAndAwait()
        assertTrue("Expected NoPendingRecords on duplicate unlock, got $duplicateUnlockRes", duplicateUnlockRes is DirectBootEventConsumerEngine.ConsumerExecutionResult.NoPendingRecords)

        assertEquals("Duplicate unlock & retry MUST NOT create duplicate Room 38 receipts", 1L, dao.count())
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.ValidEmpty)
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)
        assertTrue(DirectBootCeTakeoverStore.inspectStorageState(ceContext) is CeTakeoverStorageState.ValidEmpty)
    }

    // =========================================================================
    // P4-6: Fail-Closed on CE Locked, Corrupt/Unsupported DE Stores, and Active Owner
    // =========================================================================

    @Test
    fun p4_unlockReceiver_ceLocked_corrupt_unsupported_andActiveOwner_failClosed() = runBlocking {
        val alarmId = 2007L
        val triggerTime = baseTime + 70_000L
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            deliveredAt = baseTime + 70_010L,
            generation = 1,
            instanceToken = "tok_p4_2007",
            restoreEpoch = 2L
        )
        val witness = DirectBootFiredWitnessRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = 1,
            instanceToken = "tok_p4_2007",
            restoreEpoch = 2L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = baseTime + 70_015L
        )
        assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, delRecord) is DeliveryAppendResult.Appended)
        assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, witness) is WitnessAppendResult.Appended)

        val dao = database.directBootOccurrenceReceiptDao()

        // 1. CE Locked via unlockGateSeam
        DirectBootCeTakeoverStore.unlockGateSeam = { false }
        val lockedRes = dispatchUnlockAndAwait()
        assertTrue("Expected Rejected when CE unlock gate is false, got $lockedRes", lockedRes is DirectBootEventConsumerEngine.ConsumerExecutionResult.Rejected)
        assertEquals(0L, dao.count())
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.ValidWithRecords)
        DirectBootCeTakeoverStore.unlockGateSeam = { true }

        // 2. Corrupt DurableOwnerStore fails closed without mutating DE or writing Room 38
        val dePrefs = deContext.getSharedPreferences(DirectBootDurableOwnerStore.PREFS_NAME, Context.MODE_PRIVATE)
        val corruptOwnerPayload = "{corrupt_owner_json"
        dePrefs.edit().putString(DirectBootDurableOwnerStore.KEY_DIRECT_BOOT_DURABLE_OWNER, corruptOwnerPayload).commit()
        val corruptOwnerRes = dispatchUnlockAndAwait()
        assertTrue("Expected Rejected on corrupt DurableOwnerStore, got $corruptOwnerRes", corruptOwnerRes is DirectBootEventConsumerEngine.ConsumerExecutionResult.Rejected)
        assertEquals(0L, dao.count())
        assertEquals(corruptOwnerPayload, dePrefs.getString(DirectBootDurableOwnerStore.KEY_DIRECT_BOOT_DURABLE_OWNER, null))

        // 3. Active V2_ACTIVE DurableOwner matching occurrence blocks retirement until IDLE
        dePrefs.edit().remove(DirectBootDurableOwnerStore.KEY_DIRECT_BOOT_DURABLE_OWNER).commit()
        val activeOwner = DirectBootDurableOwner(
            schemaVersion = DirectBootDurableOwnerStore.CURRENT_SCHEMA_VERSION,
            state = OwnerState.V2_ACTIVE,
            ownerSeq = 1L,
            bootProvenance = BootProvenance(bootCount = 1, lastElapsedRealtime = 1000L),
            handoverClaim = null,
            activeSession = ActiveOwnerSession(
                alarmId = alarmId,
                triggerTime = triggerTime,
                generation = 1,
                instanceToken = "tok_p4_2007",
                restoreEpoch = 2L
            ),
            updatedAtEpochMs = baseTime + 70_012L
        ).let { it.copy(payloadDigest = it.computeDigest()) }
        assertTrue(DirectBootDurableOwnerStore.write(deContext, activeOwner, expectedOwnerSeq = 0L) is OwnerWriteResult.Success)

        dispatchUnlockAndAwait()
        assertEquals("Active V2_ACTIVE owner MUST block premature retirement/receipt", 0L, dao.count())
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.ValidWithRecords)
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidWithRecords)

        // Transition Owner -> IDLE and verify clean convergence
        val idleOwner = DirectBootDurableOwnerStore.createInitialIdle(
            bootProvenance = BootProvenance(bootCount = 1, lastElapsedRealtime = 1000L),
            nowEpochMs = baseTime + 70_100L
        ).copy(ownerSeq = 2L).let { it.copy(payloadDigest = it.computeDigest()) }
        assertTrue(DirectBootDurableOwnerStore.write(deContext, idleOwner, expectedOwnerSeq = 1L) is OwnerWriteResult.Success)

        val convergedRes = dispatchUnlockAndAwait()
        assertTrue("Expected Success after Owner returned to IDLE, got $convergedRes", convergedRes is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
        assertEquals(1L, dao.count())
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.ValidEmpty)
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)
        assertTrue(DirectBootCeTakeoverStore.inspectStorageState(ceContext) is CeTakeoverStorageState.ValidEmpty)

        // 4. Unsupported schema in DE DeliveryLedger fails closed & preserves raw payload
        val unsupportedLedgerPayload = """{"schemaVersion":99,"records":[]}"""
        dePrefs.edit().putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, unsupportedLedgerPayload).commit()
        val unsupportedRes = dispatchUnlockAndAwait()
        assertTrue("Expected Rejected on unsupported DeliveryLedger schema, got $unsupportedRes", unsupportedRes is DirectBootEventConsumerEngine.ConsumerExecutionResult.Rejected)
        assertEquals(unsupportedLedgerPayload, dePrefs.getString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, null))
    }

    // =========================================================================
    // P4-7: 8s Timeout Preserves Unretired Evidence & Converges on Retry
    // =========================================================================

    @Test
    fun p4_unlockReceiver_8sTimeout_preservesUnretiredEvidenceAndAllowsRetry() = runBlocking {
        assertEquals(8_000L, DirectBootUnlockReceiver.timeoutMsSeam)

        val alarmId = 2008L
        val triggerTime = baseTime + 80_000L
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            deliveredAt = baseTime + 80_010L,
            generation = 1,
            instanceToken = "tok_p4_2008",
            restoreEpoch = 2L
        )
        val witness = DirectBootFiredWitnessRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = 1,
            instanceToken = "tok_p4_2008",
            restoreEpoch = 2L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = baseTime + 80_015L
        )
        assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, delRecord) is DeliveryAppendResult.Appended)
        assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, witness) is WitnessAppendResult.Appended)

        val timeoutLatch = CountDownLatch(1)
        var capturedError: Throwable? = null
        DirectBootUnlockReceiver.timeoutMsSeam = 50L
        DirectBootUnlockReceiver.preConsumerHookSeam = { kotlinx.coroutines.delay(250L) }
        DirectBootUnlockReceiver.databaseSeam = { database }
        DirectBootUnlockReceiver.onExecutionTerminatedSeam = { err ->
            capturedError = err
            timeoutLatch.countDown()
        }

        DirectBootUnlockReceiver().onReceive(ceContext, Intent(Intent.ACTION_USER_UNLOCKED))
        assertTrue("Timed-out unlock execution did not finish cleanly", timeoutLatch.await(5, TimeUnit.SECONDS))
        assertTrue("Expected TimeoutCancellationException, got $capturedError", capturedError is kotlinx.coroutines.TimeoutCancellationException)

        val dao = database.directBootOccurrenceReceiptDao()
        assertEquals("Timed-out unlock MUST NOT leave partial Room 38 receipt", 0L, dao.count())
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.ValidWithRecords)
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidWithRecords)
        assertTrue("DE->CE handoff evidence MUST be preserved safely in CE for retry", DirectBootCeTakeoverStore.inspectStorageState(ceContext) is CeTakeoverStorageState.ValidWithRecords)

        // Reset timeout seams and retry unlock -> converges cleanly
        DirectBootUnlockReceiver.resetSeamsForTesting()
        val retryRes = dispatchUnlockAndAwait()
        assertTrue("Expected Success on retry after timeout, got $retryRes", retryRes is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
        assertEquals(1L, dao.count())
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.ValidEmpty)
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)
        assertTrue(DirectBootCeTakeoverStore.inspectStorageState(ceContext) is CeTakeoverStorageState.ValidEmpty)
    }

    // =========================================================================
    // P4-8: Flag OFF / LEGACY_ONLY / KNO-228 HOLD Invariants Preserved
    // =========================================================================

    @Test
    fun p4_unlockReceiver_preservesFlagOffLegacyOnlyAndKno228Hold() {
        assertEquals(8_000L, DirectBootUnlockReceiver.timeoutMsSeam)
        val routeAuthority = ProductionSchedulingRouteAuthority()
        assertEquals(ProductionSchedulingRoute.LEGACY_ONLY, routeAuthority.currentRoute)
        assertFalse(routeAuthority.isOwnerRouteActive())
        assertFalse(SharedOutputAuthorityGate.getInstance(ceContext).isEnabled)
        assertFalse(BootReceiver().v2DeliveryCapableProvider())
    }

    // =========================================================================
    // P4-RETRY-1: TimeoutCancellationException Recovers via Recovery Entry Without Rebroadcast
    // =========================================================================

    @Test
    fun p4_retry1_timeoutCancellation_recoversViaRecoveryWithoutRebroadcast() = runBlocking {
        val alarmId = 3001L
        val triggerTime = baseTime + 100_000L
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            deliveredAt = baseTime + 100_010L,
            generation = 1,
            instanceToken = "tok_retry_3001",
            restoreEpoch = 2L
        )
        val witness = DirectBootFiredWitnessRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = 1,
            instanceToken = "tok_retry_3001",
            restoreEpoch = 2L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = baseTime + 100_015L
        )
        assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, delRecord) is DeliveryAppendResult.Appended)
        assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, witness) is WitnessAppendResult.Appended)

        // Force timeout in unlock receiver
        DirectBootUnlockReceiver.timeoutMsSeam = 50L
        DirectBootUnlockReceiver.preConsumerHookSeam = { kotlinx.coroutines.delay(250L) }
        DirectBootUnlockReceiver.databaseSeam = { database }
        val timeoutLatch = CountDownLatch(1)
        DirectBootUnlockReceiver.onExecutionTerminatedSeam = { timeoutLatch.countDown() }

        DirectBootUnlockReceiver().onReceive(ceContext, Intent(Intent.ACTION_USER_UNLOCKED))
        assertTrue(timeoutLatch.await(5, TimeUnit.SECONDS))

        val dao = database.directBootOccurrenceReceiptDao()
        assertEquals(0L, dao.count())
        assertTrue("Evidence must remain pending after timeout", DirectBootUnlockReceiver.hasPendingTakeoverRecords(deContext, ceContext))

        // Reset seams and run recovery directly without ACTION_USER_UNLOCKED rebroadcast
        DirectBootUnlockReceiver.resetSeamsForTesting()
        val recoveryRes = DirectBootUnlockReceiver.runUnlockedRecovery(ceContext, deContext, ceContext, database)
        assertTrue("Recovery must succeed, got $recoveryRes", recoveryRes is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
        assertEquals(1L, dao.count())
        assertFalse("Pending records must be cleared after recovery", DirectBootUnlockReceiver.hasPendingTakeoverRecords(deContext, ceContext))
    }

    // =========================================================================
    // P4-RETRY-2: IncompleteRetirement Recovers via Recovery Entry
    // =========================================================================

    @Test
    fun p4_retry2_incompleteRetirement_recoversViaRecovery() = runBlocking {
        val alarmId = 3002L
        val triggerTime = baseTime + 110_000L
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            deliveredAt = baseTime + 110_010L,
            generation = 1,
            instanceToken = "tok_retry_3002",
            restoreEpoch = 2L
        )
        val witness = DirectBootFiredWitnessRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = 1,
            instanceToken = "tok_retry_3002",
            restoreEpoch = 2L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = baseTime + 110_015L
        )
        assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, delRecord) is DeliveryAppendResult.Appended)
        assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, witness) is WitnessAppendResult.Appended)

        // Induce IncompleteRetirement
        DirectBootDeliveryLedger.commitSeam = { _, _ -> false }
        val crashRes = dispatchUnlockAndAwait()
        assertTrue("Expected IncompleteRetirement, got $crashRes", crashRes is DirectBootEventConsumerEngine.ConsumerExecutionResult.IncompleteRetirement)
        assertTrue(DirectBootUnlockReceiver.hasPendingTakeoverRecords(deContext, ceContext))

        DirectBootDeliveryLedger.resetSeamsForTesting()
        val recoveryRes = DirectBootUnlockReceiver.runUnlockedRecovery(ceContext, deContext, ceContext, database)
        assertTrue("Recovery must complete retirement, got $recoveryRes", recoveryRes is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
        val dao = database.directBootOccurrenceReceiptDao()
        assertEquals(1L, dao.count())
        assertFalse(DirectBootUnlockReceiver.hasPendingTakeoverRecords(deContext, ceContext))
    }

    // =========================================================================
    // P4-RETRY-3: UNKNOWN Witness Confirmed Recovers Without Rebroadcast
    // =========================================================================

    @Test
    fun p4_retry3_unknownWitnessConfirmed_recoversWithoutRebroadcast() = runBlocking {
        val alarmId = 3003L
        val triggerTime = baseTime + 120_000L
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            deliveredAt = baseTime + 120_010L,
            generation = 1,
            instanceToken = "tok_retry_3003",
            restoreEpoch = 2L
        )
        val witness = DirectBootFiredWitnessRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = 1,
            instanceToken = "tok_retry_3003",
            restoreEpoch = 2L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_STATUS_UNKNOWN,
            observedAtEpochMs = baseTime + 120_015L
        )
        assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, delRecord) is DeliveryAppendResult.Appended)
        assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, witness) is WitnessAppendResult.Appended)

        // First unlock defers because witness is UNKNOWN
        val initialRes = dispatchUnlockAndAwait()
        val dao = database.directBootOccurrenceReceiptDao()
        assertEquals(0L, dao.count())
        assertTrue(DirectBootUnlockReceiver.hasPendingTakeoverRecords(deContext, ceContext))

        // Confirm witness outcome later
        assertTrue(
            DirectBootFiredWitnessLedger.updateWitnessOutcome(
                context = deContext,
                recordId = witness.recordId,
                deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
                deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED
            ) is WitnessUpdateResult.Updated
        )

        // Recovery picks up without ACTION_USER_UNLOCKED broadcast
        val recoveryRes = DirectBootUnlockReceiver.runUnlockedRecovery(ceContext, deContext, ceContext, database)
        assertTrue("Recovery must succeed after witness confirmation, got $recoveryRes", recoveryRes is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
        assertEquals(1L, dao.count())
        assertFalse(DirectBootUnlockReceiver.hasPendingTakeoverRecords(deContext, ceContext))
    }

    // =========================================================================
    // P4-RETRY-4: Owner V2_ACTIVE -> IDLE Recovers Without Rebroadcast
    // =========================================================================

    @Test
    fun p4_retry4_ownerV2ActiveToIdle_recoversWithoutRebroadcast() = runBlocking {
        val alarmId = 3004L
        val triggerTime = baseTime + 130_000L
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            deliveredAt = baseTime + 130_010L,
            generation = 1,
            instanceToken = "tok_retry_3004",
            restoreEpoch = 2L
        )
        val witness = DirectBootFiredWitnessRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = 1,
            instanceToken = "tok_retry_3004",
            restoreEpoch = 2L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = baseTime + 130_015L
        )
        assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, delRecord) is DeliveryAppendResult.Appended)
        assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, witness) is WitnessAppendResult.Appended)

        // Set Owner to V2_ACTIVE
        val activeOwner = DirectBootDurableOwner(
            schemaVersion = DirectBootDurableOwnerStore.CURRENT_SCHEMA_VERSION,
            state = OwnerState.V2_ACTIVE,
            ownerSeq = 1L,
            bootProvenance = BootProvenance(bootCount = 1, lastElapsedRealtime = 1000L),
            handoverClaim = null,
            activeSession = ActiveOwnerSession(
                alarmId = alarmId,
                triggerTime = triggerTime,
                generation = 1,
                instanceToken = "tok_retry_3004",
                restoreEpoch = 2L
            ),
            updatedAtEpochMs = baseTime + 130_012L
        ).let { it.copy(payloadDigest = it.computeDigest()) }
        assertTrue(DirectBootDurableOwnerStore.write(deContext, activeOwner, expectedOwnerSeq = 0L) is OwnerWriteResult.Success)

        // Unlock defers because of active owner
        dispatchUnlockAndAwait()
        val dao = database.directBootOccurrenceReceiptDao()
        assertEquals(0L, dao.count())
        assertTrue(DirectBootUnlockReceiver.hasUnretiredDeRecords(deContext))

        // Owner releases to IDLE
        val idleOwner = DirectBootDurableOwnerStore.createInitialIdle(
            bootProvenance = BootProvenance(bootCount = 1, lastElapsedRealtime = 1000L),
            nowEpochMs = baseTime + 130_100L
        ).copy(ownerSeq = 2L).let { it.copy(payloadDigest = it.computeDigest()) }
        assertTrue(DirectBootDurableOwnerStore.write(deContext, idleOwner, expectedOwnerSeq = 1L) is OwnerWriteResult.Success)

        // Recovery picks up without ACTION_USER_UNLOCKED broadcast
        val recoveryRes = DirectBootUnlockReceiver.runUnlockedRecovery(ceContext, deContext, ceContext, database)
        assertTrue("Recovery must succeed once owner is IDLE, got $recoveryRes", recoveryRes is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
        assertEquals(1L, dao.count())
        assertFalse(DirectBootUnlockReceiver.hasUnretiredDeRecords(deContext))
    }

    // =========================================================================
    // P4-RETRY-5: Cold Start Post-Unlock Detects Pending Records
    // =========================================================================

    @Test
    fun p4_retry5_unlockedColdStart_detectsPendingRecordsAndRecovers() = runBlocking {
        val alarmId = 3005L
        val triggerTime = baseTime + 140_000L
        val missedRecord = DirectBootMissedRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            missedAt = baseTime + 140_050L,
            generation = 1,
            instanceToken = "tok_retry_3005",
            classification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE,
            restoreEpoch = 2L
        )
        assertTrue(DirectBootMissedStore.recordMissed(deContext, missedRecord) is MissedRecordResult.Recorded)

        // App cold starts after unlock with pending DE records
        assertTrue("Cold start must detect pending unretired records", DirectBootUnlockReceiver.hasPendingTakeoverRecords(deContext, ceContext))

        val recoveryRes = DirectBootUnlockReceiver.runUnlockedRecovery(ceContext, deContext, ceContext, database)
        assertTrue("Cold start recovery must succeed, got $recoveryRes", recoveryRes is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)

        val dao = database.directBootOccurrenceReceiptDao()
        assertEquals(1L, dao.count())
        val receipt = dao.findByOccurrenceKey(DirectBootCeTakeoverStore.computeOccurrenceKey(alarmId, triggerTime, 1, "tok_retry_3005"))
        assertEquals("CONSUMED_MISSED", receipt?.consumerOutcome)
        assertFalse(DirectBootUnlockReceiver.hasPendingTakeoverRecords(deContext, ceContext))
    }

    // =========================================================================
    // P4-RETRY-6: Single Flight Mutex Prevents Concurrent Execution
    // =========================================================================

    @Test
    fun p4_retry6_singleFlightMutex_preventsConcurrentExecution() = runBlocking {
        val alarmId = 3006L
        val triggerTime = baseTime + 150_000L
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            deliveredAt = baseTime + 150_010L,
            generation = 1,
            instanceToken = "tok_retry_3006",
            restoreEpoch = 2L
        )
        val witness = DirectBootFiredWitnessRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = 1,
            instanceToken = "tok_retry_3006",
            restoreEpoch = 2L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = baseTime + 150_015L
        )
        assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, delRecord) is DeliveryAppendResult.Appended)
        assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, witness) is WitnessAppendResult.Appended)

        // Run recovery concurrently
        val (r1, r2) = coroutineScope {
            val d1 = async(kotlinx.coroutines.Dispatchers.IO) {
                DirectBootUnlockReceiver.runUnlockedRecovery(ceContext, deContext, ceContext, database)
            }
            val d2 = async(kotlinx.coroutines.Dispatchers.IO) {
                DirectBootUnlockReceiver.runUnlockedRecovery(ceContext, deContext, ceContext, database)
            }
            Pair(d1.await(), d2.await())
        }

        val results = listOf(r1, r2)
        assertTrue("One execution must succeed, got $results", results.any { it is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success })
        assertTrue("Other execution must be NoPendingRecords or Success, got $results", results.all {
            it is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success ||
                it is DirectBootEventConsumerEngine.ConsumerExecutionResult.NoPendingRecords
        })
        val dao = database.directBootOccurrenceReceiptDao()
        assertEquals("Single flight mutex must prevent duplicate Room 38 receipts", 1L, dao.count())
    }

    // =========================================================================
    // P4-RETRY-7: Permanent Conflict Retains Evidence & Caps Retries
    // =========================================================================

    @Test
    fun p4_retry7_permanentConflict_retainsEvidenceAndCapsRetries() = runBlocking {
        assertEquals(3, AlarmHealthWorker.MAX_ATTEMPTS)

        val dePrefs = deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
        val conflictedPayload = """{"schemaVersion":99,"records":[]}"""
        dePrefs.edit().putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, conflictedPayload).commit()

        val recoveryRes = DirectBootUnlockReceiver.runUnlockedRecovery(ceContext, deContext, ceContext, database)
        assertTrue("Permanent conflict must be rejected, got $recoveryRes", recoveryRes is DirectBootEventConsumerEngine.ConsumerExecutionResult.Rejected)

        // Evidence must be preserved in DE (not deleted)
        assertEquals(conflictedPayload, dePrefs.getString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, null))
        val dao = database.directBootOccurrenceReceiptDao()
        assertEquals("Conflicted store must NOT write receipt", 0L, dao.count())
    }
}
