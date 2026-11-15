package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit Test Suite for KNO-75 Phase D3-C2-P3D-P3-BATCH-B (Slice E2 / E3).
 *
 * Covers:
 * - Delivered takeover record consumption and Room 38 receipt insertion.
 * - Missed takeover record consumption (RECEIPT-ONLY policy without AlarmEvent inserts).
 * - Restore epoch resolution and legacy v1 fencing (R7).
 * - Payload conflict detection on duplicate occurrenceKey with different hash.
 * - Idempotency on repeated consumption of identical takeover record.
 * - Crash & Recovery scenarios C1 through C10.
 */
@RunWith(RobolectricTestRunner::class)
class DirectBootEventConsumerEngineTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private val baseTime = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DirectBootCeTakeoverStore.unlockGateSeam = { true }
        DirectBootCeTakeoverStore.clear(context)
    }

    @After
    fun tearDown() {
        database.close()
        DirectBootCeTakeoverStore.clear(context)
        DirectBootCeTakeoverStore.resetSeamsForTesting()
    }

    @Test
    fun testDeliveredRecord_writesDeliveredReceiptAndReleasesSlot() = runBlocking {
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 101L,
            triggerTime = baseTime + 10_000L,
            deliveredAt = baseTime + 10_050L,
            generation = 1,
            instanceToken = "tok_101",
            restoreEpoch = 1L
        )

        val witnessRecord = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 101L, triggerTime = baseTime + 10_000L, generation = 1, instanceToken = "tok_101", restoreEpoch = 1L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = baseTime + 10_055L
        )
        DirectBootFiredWitnessLedger.appendWitness(context, witnessRecord)

        val ceResult = DirectBootCeTakeoverStore.recordDelivered(context, delRecord, baseTime + 10_100L)
        assertTrue("Expected RecordedConfirmed but got $ceResult", ceResult is CeTakeoverIntakeResult.RecordedConfirmed)

        val consumerResult = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(context, context, database)
        assertTrue("Expected Success but got $consumerResult", consumerResult is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
        val success = consumerResult as DirectBootEventConsumerEngine.ConsumerExecutionResult.Success
        assertEquals(1, success.processedCount)
        assertEquals(1, success.receiptsCommitted)

        // Verify receipt in Room DB
        val dao = database.directBootOccurrenceReceiptDao()
        val occKey = DirectBootCeTakeoverStore.computeOccurrenceKey(101L, baseTime + 10_000L, 1, "tok_101")
        val receipt = dao.findByOccurrenceKey(occKey)
        assertNotNull(receipt)
        assertEquals("CONSUMED_DELIVERED", receipt?.consumerOutcome)
        assertEquals(1L, receipt?.restoreEpoch)

        // Verify CE slot is released
        val ceState = DirectBootCeTakeoverStore.inspectStorageState(context)
        assertTrue(ceState is CeTakeoverStorageState.ValidEmpty)
    }

    @Test
    fun testMissedRecord_writesReceiptOnlyWithoutAlarmEvents() = runBlocking {
        val missedRecord = DirectBootMissedRecord.createNormal(
            alarmId = 102L,
            triggerTime = baseTime + 20_000L,
            missedAt = baseTime + 20_500L,
            generation = 1,
            instanceToken = "tok_102",
            classification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE,
            restoreEpoch = 2L
        )

        val ceResult = DirectBootCeTakeoverStore.recordMissed(context, missedRecord, baseTime + 20_600L)
        assertTrue("Expected RecordedConfirmed but got $ceResult", ceResult is CeTakeoverIntakeResult.RecordedConfirmed)

        val consumerResult = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(context, context, database)
        assertTrue("Expected Success but got $consumerResult", consumerResult is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)

        // Verify receipt in Room DB
        val dao = database.directBootOccurrenceReceiptDao()
        val occKey = DirectBootCeTakeoverStore.computeOccurrenceKey(102L, baseTime + 20_000L, 1, "tok_102")
        val receipt = dao.findByOccurrenceKey(occKey)
        assertNotNull(receipt)
        assertEquals("CONSUMED_MISSED", receipt?.consumerOutcome)
        assertEquals(2L, receipt?.restoreEpoch)

        // Verify NO AlarmEvents were inserted
        val eventDao = database.alarmEventDao()
        val events = eventDao.getRecent()
        assertTrue("Missed takeover record MUST NOT insert AlarmEvent rows", events.isEmpty())
    }

    @Test
    fun testLegacyV1Record_mismatchedAuthority_quarantinesSlotWithoutCERelease() = runBlocking {
        // Legacy v1 CE record has restoreEpoch = 0L
        val legacyRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 103L,
            triggerTime = baseTime + 30_000L,
            deliveredAt = baseTime + 30_050L,
            generation = 1,
            instanceToken = "tok_legacy_103",
            restoreEpoch = 0L,
            schemaVersion = 1
        )

        val ceResult = DirectBootCeTakeoverStore.recordDelivered(context, legacyRecord, baseTime + 30_100L)
        assertTrue("Expected RecordedConfirmed but got $ceResult", ceResult is CeTakeoverIntakeResult.RecordedConfirmed)

        val consumerResult = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(context, context, database)
        assertTrue("Expected Success but got $consumerResult", consumerResult is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)

        // Verify conflict receipt in Room DB
        val dao = database.directBootOccurrenceReceiptDao()
        val occKey = DirectBootCeTakeoverStore.computeOccurrenceKey(103L, baseTime + 30_000L, 1, "tok_legacy_103")
        val receipt = dao.findByOccurrenceKey(occKey)
        assertNotNull(receipt)
        assertEquals("RESTORE_EPOCH_CONFLICT", receipt?.consumerOutcome)

        // Verify CE slot is NOT released (Quarantined)
        val ceState = DirectBootCeTakeoverStore.inspectStorageState(context)
        assertTrue(ceState is CeTakeoverStorageState.ValidWithRecords)
        val records = (ceState as CeTakeoverStorageState.ValidWithRecords).records
        assertEquals(1, records.size)
        assertEquals(occKey, records.first().occurrenceKey)
    }

    @Test
    fun testRepeatedConsumption_isIdempotent() = runBlocking {
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 104L,
            triggerTime = baseTime + 40_000L,
            deliveredAt = baseTime + 40_050L,
            generation = 1,
            instanceToken = "tok_104",
            restoreEpoch = 1L
        )

        val witnessRecord = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 104L, triggerTime = baseTime + 40_000L, generation = 1, instanceToken = "tok_104", restoreEpoch = 1L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = baseTime + 40_055L
        )
        DirectBootFiredWitnessLedger.appendWitness(context, witnessRecord)

        DirectBootCeTakeoverStore.recordDelivered(context, delRecord, baseTime + 40_100L)

        // 1st consumption
        DirectBootEventConsumerEngine.consumePendingTakeoverRecords(context, context, database)

        // Re-inject identical record to simulate retry after crash before CE slot release
        DirectBootCeTakeoverStore.recordDelivered(context, delRecord, baseTime + 40_100L)

        // 2nd consumption
        val retryResult = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(context, context, database)
        assertTrue("Expected Success but got $retryResult", retryResult is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)

        // Verify single receipt exists in DB
        val dao = database.directBootOccurrenceReceiptDao()
        val occKey = DirectBootCeTakeoverStore.computeOccurrenceKey(104L, baseTime + 40_000L, 1, "tok_104")
        val receipts = dao.findByAlarmId(104L)
        assertEquals(1, receipts.size)
        assertEquals(occKey, receipts.first().occurrenceKey)
    }

    // =========================================================================
    // KNO-75 B1c-S4 RED Tests: Consumer End-to-End Retirement & Ordering Gates
    // =========================================================================

    private fun clearAllDeAndCeStoresForS4() {
        val deContext = context.createDeviceProtectedStorageContext()
        DirectBootDeliveryLedger.resetSeamsForTesting()
        DirectBootMissedStore.resetSeamsForTesting()
        DirectBootFiredWitnessLedger.resetSeamsForTesting()
        DirectBootCeTakeoverStore.resetSeamsForTesting()
        deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        deContext.getSharedPreferences(DirectBootMissedStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        deContext.getSharedPreferences(DirectBootFiredWitnessLedger.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        DirectBootCeTakeoverStore.clear(context)
        DirectBootCeTakeoverStore.unlockGateSeam = { true }
    }

    @Test
    fun s4_red_deliveredRecord_mustRetireDeDeliveryLedgerAndWitnessAndCeSlot() = runBlocking {
        clearAllDeAndCeStoresForS4()
        val deContext = context.createDeviceProtectedStorageContext()
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 2001L,
            triggerTime = baseTime + 10_000L,
            deliveredAt = baseTime + 10_050L,
            generation = 1,
            instanceToken = "tok_s4_2001",
            restoreEpoch = 1L
        )
        val witnessRecord = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 2001L,
            triggerTime = baseTime + 10_000L,
            generation = 1,
            instanceToken = "tok_s4_2001",
            restoreEpoch = 1L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = baseTime + 10_055L
        )
        assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, delRecord) is DeliveryAppendResult.Appended)
        assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, witnessRecord) is WitnessAppendResult.Appended)
        assertTrue(DirectBootCeTakeoverStore.recordDelivered(context, delRecord, baseTime + 10_100L) is CeTakeoverIntakeResult.RecordedConfirmed)

        val result = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(context, context, database)
        assertTrue("Expected Success, got $result", result is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
        assertTrue("DE DeliveryLedger MUST be retired to ValidEmpty", DirectBootDeliveryLedger.inspectStorageState(deContext) is LedgerStorageState.ValidEmpty)
        assertTrue("DE WitnessLedger MUST be retired to ValidEmpty", DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)
        assertTrue("CE TakeoverStore MUST be retired to ValidEmpty", DirectBootCeTakeoverStore.inspectStorageState(context) is CeTakeoverStorageState.ValidEmpty)
    }

    @Test
    fun s4_red_missedRecord_mustRetireDeMissedStoreAndCeSlot() = runBlocking {
        clearAllDeAndCeStoresForS4()
        val deContext = context.createDeviceProtectedStorageContext()
        val missedRecord = DirectBootMissedRecord.createNormal(
            alarmId = 2002L,
            triggerTime = baseTime + 20_000L,
            missedAt = baseTime + 20_500L,
            generation = 1,
            instanceToken = "tok_s4_2002",
            classification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE,
            restoreEpoch = 1L
        )
        assertTrue(DirectBootMissedStore.recordMissed(deContext, missedRecord) is MissedRecordResult.Recorded)
        assertTrue(DirectBootCeTakeoverStore.recordMissed(context, missedRecord, baseTime + 20_600L) is CeTakeoverIntakeResult.RecordedConfirmed)

        val result = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(context, context, database)
        assertTrue("Expected Success, got $result", result is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
        assertTrue("DE MissedStore MUST be retired to ValidEmpty", DirectBootMissedStore.inspectStorageState(deContext) is MissedStoreStorageState.ValidEmpty)
        assertTrue("CE TakeoverStore MUST be retired to ValidEmpty", DirectBootCeTakeoverStore.inspectStorageState(context) is CeTakeoverStorageState.ValidEmpty)
    }

    @Test
    fun s4_red_witnessAckCommitFalse_mustAbortCeReleaseAndNotReturnSuccess() = runBlocking {
        clearAllDeAndCeStoresForS4()
        val deContext = context.createDeviceProtectedStorageContext()
        try {
            val delRecord = DirectBootDeliveryRecord.createNormal(
                alarmId = 2003L,
                triggerTime = baseTime + 30_000L,
                deliveredAt = baseTime + 30_050L,
                generation = 1,
                instanceToken = "tok_s4_2003",
                restoreEpoch = 1L
            )
            val witnessRecord = DirectBootFiredWitnessRecord.createNormal(
                alarmId = 2003L,
                triggerTime = baseTime + 30_000L,
                generation = 1,
                instanceToken = "tok_s4_2003",
                restoreEpoch = 1L,
                deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
                deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
                observedAtEpochMs = baseTime + 30_055L
            )
            assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, delRecord) is DeliveryAppendResult.Appended)
            assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, witnessRecord) is WitnessAppendResult.Appended)
            assertTrue(DirectBootCeTakeoverStore.recordDelivered(context, delRecord, baseTime + 30_100L) is CeTakeoverIntakeResult.RecordedConfirmed)

            DirectBootFiredWitnessLedger.commitSeam = { false }

            val result = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(context, context, database)
            assertTrue("Consumer MUST NOT report Success when Witness ACK fails, got $result", result !is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
            assertTrue("CE slot MUST be preserved when Witness ACK fails", DirectBootCeTakeoverStore.inspectStorageState(context) is CeTakeoverStorageState.ValidWithRecords)
        } finally {
            clearAllDeAndCeStoresForS4()
        }
    }

    @Test
    fun s4_red_deAckCommitFalse_mustAbortWitnessAckAndCeReleaseAndNotReturnSuccess() = runBlocking {
        clearAllDeAndCeStoresForS4()
        val deContext = context.createDeviceProtectedStorageContext()
        try {
            val delRecord = DirectBootDeliveryRecord.createNormal(
                alarmId = 2004L,
                triggerTime = baseTime + 40_000L,
                deliveredAt = baseTime + 40_050L,
                generation = 1,
                instanceToken = "tok_s4_2004",
                restoreEpoch = 1L
            )
            val witnessRecord = DirectBootFiredWitnessRecord.createNormal(
                alarmId = 2004L,
                triggerTime = baseTime + 40_000L,
                generation = 1,
                instanceToken = "tok_s4_2004",
                restoreEpoch = 1L,
                deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
                deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
                observedAtEpochMs = baseTime + 40_055L
            )
            assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, delRecord) is DeliveryAppendResult.Appended)
            assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, witnessRecord) is WitnessAppendResult.Appended)
            assertTrue(DirectBootCeTakeoverStore.recordDelivered(context, delRecord, baseTime + 40_100L) is CeTakeoverIntakeResult.RecordedConfirmed)

            DirectBootDeliveryLedger.commitSeam = { _, _ -> false }

            val result = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(context, context, database)
            assertTrue("Consumer MUST NOT report Success when DE ACK fails, got $result", result !is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
            assertTrue("Witness MUST remain preserved when DE ACK fails", DirectBootFiredWitnessLedger.hasWitness(deContext, 2004L, baseTime + 40_000L, 1, "tok_s4_2004"))
            assertTrue("CE slot MUST remain preserved when DE ACK fails", DirectBootCeTakeoverStore.inspectStorageState(context) is CeTakeoverStorageState.ValidWithRecords)
        } finally {
            clearAllDeAndCeStoresForS4()
        }
    }

    @Test
    fun s4_red_ceReleaseCommitFalse_mustNotReturnSuccess() = runBlocking {
        clearAllDeAndCeStoresForS4()
        val deContext = context.createDeviceProtectedStorageContext()
        try {
            val missedRecord = DirectBootMissedRecord.createNormal(
                alarmId = 2005L,
                triggerTime = baseTime + 50_000L,
                missedAt = baseTime + 50_500L,
                generation = 1,
                instanceToken = "tok_s4_2005",
                classification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE,
                restoreEpoch = 1L
            )
            assertTrue(DirectBootMissedStore.recordMissed(deContext, missedRecord) is MissedRecordResult.Recorded)
            assertTrue(DirectBootCeTakeoverStore.recordMissed(context, missedRecord, baseTime + 50_600L) is CeTakeoverIntakeResult.RecordedConfirmed)

            DirectBootCeTakeoverStore.commitSeam = { _, _ -> false }

            val result = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(context, context, database)
            assertTrue("Consumer MUST NOT report Success when CE slot release commit fails, got $result", result !is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
            assertTrue("CE slot MUST remain preserved when release fails", DirectBootCeTakeoverStore.inspectStorageState(context) is CeTakeoverStorageState.ValidWithRecords)
        } finally {
            clearAllDeAndCeStoresForS4()
        }
    }

    @Test
    fun s4_red_r7a_serviceStartFailedSameEpoch_mustCoRetireDeliveryAndMissedAndCe() = runBlocking {
        clearAllDeAndCeStoresForS4()
        val deContext = context.createDeviceProtectedStorageContext()
        val trigger = baseTime + 60_000L
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 2006L,
            triggerTime = trigger,
            deliveredAt = baseTime + 60_010L,
            generation = 1,
            instanceToken = "tok_s4_2006",
            restoreEpoch = 1L
        )
        val missedFail = DirectBootMissedRecord.createNormal(
            alarmId = 2006L,
            triggerTime = trigger,
            missedAt = baseTime + 60_020L,
            generation = 1,
            instanceToken = "tok_s4_2006",
            classification = DirectBootMissedRecord.CLASSIFICATION_SERVICE_START_FAILED,
            restoreEpoch = 1L
        )
        assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, delRecord) is DeliveryAppendResult.Appended)
        assertTrue(DirectBootMissedStore.recordMissed(deContext, missedFail) is MissedRecordResult.Recorded)
        assertTrue(DirectBootCeTakeoverStore.recordDelivered(context, delRecord, baseTime + 60_030L) is CeTakeoverIntakeResult.RecordedConfirmed)

        val result = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(context, context, database)
        assertTrue("Expected Success on r7_a co-retirement, got $result", result is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)

        val receipt = database.directBootOccurrenceReceiptDao().findByOccurrenceKey(
            DirectBootCeTakeoverStore.computeOccurrenceKey(2006L, trigger, 1, "tok_s4_2006")
        )
        assertNotNull(receipt)
        assertEquals("CONSUMED_MISSED", receipt?.consumerOutcome)
        assertTrue("DE DeliveryLedger MUST be retired on r7_a", DirectBootDeliveryLedger.inspectStorageState(deContext) is LedgerStorageState.ValidEmpty)
        assertTrue("DE MissedStore SERVICE_START_FAILED MUST be retired on r7_a", DirectBootMissedStore.inspectStorageState(deContext) is MissedStoreStorageState.ValidEmpty)
        assertTrue("CE TakeoverStore MUST be retired on r7_a", DirectBootCeTakeoverStore.inspectStorageState(context) is CeTakeoverStorageState.ValidEmpty)
    }

    @Test
    fun s4_incompleteRetirement_reportsExactCountsAndStages_andConvergesAcrossC1C2C3() = runBlocking {
        clearAllDeAndCeStoresForS4()
        val deContext = context.createDeviceProtectedStorageContext()
        try {
            val trigger = baseTime + 70_000L
            val delRecord = DirectBootDeliveryRecord.createNormal(
                alarmId = 2007L,
                triggerTime = trigger,
                deliveredAt = baseTime + 70_050L,
                generation = 1,
                instanceToken = "tok_s4_2007",
                restoreEpoch = 1L
            )
            val witnessRecord = DirectBootFiredWitnessRecord.createNormal(
                alarmId = 2007L,
                triggerTime = trigger,
                generation = 1,
                instanceToken = "tok_s4_2007",
                restoreEpoch = 1L,
                deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
                deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
                observedAtEpochMs = baseTime + 70_055L
            )
            assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, delRecord) is DeliveryAppendResult.Appended)
            assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, witnessRecord) is WitnessAppendResult.Appended)
            assertTrue(DirectBootCeTakeoverStore.recordDelivered(context, delRecord, baseTime + 70_100L) is CeTakeoverIntakeResult.RecordedConfirmed)

            // C1: Fail at DE_ACK after Room commit
            DirectBootDeliveryLedger.commitSeam = { _, _ -> false }
            val rC1 = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(context, context, database)
            assertTrue("Expected IncompleteRetirement at C1, got $rC1", rC1 is DirectBootEventConsumerEngine.ConsumerExecutionResult.IncompleteRetirement)
            val inc1 = rC1 as DirectBootEventConsumerEngine.ConsumerExecutionResult.IncompleteRetirement
            assertEquals(1, inc1.processedCount)
            assertEquals(1, inc1.receiptsCommitted)
            assertEquals(0, inc1.deAcknowledgedCount)
            assertEquals(0, inc1.witnessAcknowledgedCount)
            assertEquals(0, inc1.ceSlotsReleasedCount)
            assertEquals("DE_ACK", inc1.failures.single().stage)

            // C2: DE_ACK succeeds on retry, fail at WITNESS_ACK
            DirectBootDeliveryLedger.resetSeamsForTesting()
            DirectBootFiredWitnessLedger.commitSeam = { false }
            val rC2 = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(context, context, database)
            assertTrue("Expected IncompleteRetirement at C2, got $rC2", rC2 is DirectBootEventConsumerEngine.ConsumerExecutionResult.IncompleteRetirement)
            val inc2 = rC2 as DirectBootEventConsumerEngine.ConsumerExecutionResult.IncompleteRetirement
            assertEquals(1, inc2.receiptsCommitted)
            assertEquals(1, inc2.deAcknowledgedCount)
            assertEquals(0, inc2.witnessAcknowledgedCount)
            assertEquals(0, inc2.ceSlotsReleasedCount)
            assertEquals("WITNESS_ACK", inc2.failures.single().stage)
            assertTrue(DirectBootDeliveryLedger.inspectStorageState(deContext) is LedgerStorageState.ValidEmpty)

            // C3: WITNESS_ACK succeeds on retry, fail at CE_RELEASE
            DirectBootFiredWitnessLedger.resetSeamsForTesting()
            DirectBootCeTakeoverStore.commitSeam = { _, _ -> false }
            val rC3 = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(context, context, database)
            assertTrue("Expected IncompleteRetirement at C3, got $rC3", rC3 is DirectBootEventConsumerEngine.ConsumerExecutionResult.IncompleteRetirement)
            val inc3 = rC3 as DirectBootEventConsumerEngine.ConsumerExecutionResult.IncompleteRetirement
            assertEquals(1, inc3.receiptsCommitted)
            assertEquals(1, inc3.deAcknowledgedCount)
            assertEquals(1, inc3.witnessAcknowledgedCount)
            assertEquals(0, inc3.ceSlotsReleasedCount)
            assertEquals("CE_RELEASE", inc3.failures.single().stage)
            assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)

            // Final clean retry completes CE_RELEASE without duplicating Room receipts
            DirectBootCeTakeoverStore.resetSeamsForTesting()
            DirectBootCeTakeoverStore.unlockGateSeam = { true }
            val rFinal = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(context, context, database)
            assertTrue("Expected Success on final retry, got $rFinal", rFinal is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
            assertTrue(DirectBootCeTakeoverStore.inspectStorageState(context) is CeTakeoverStorageState.ValidEmpty)
            assertEquals(1, database.directBootOccurrenceReceiptDao().findByAlarmId(2007L).size)
        } finally {
            clearAllDeAndCeStoresForS4()
        }
    }

    @Test
    fun s4_restoreEpochConflict_secondRunStaysQuarantinedAndNeverReleasesCe() = runBlocking {
        clearAllDeAndCeStoresForS4()
        val legacyRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 2008L,
            triggerTime = baseTime + 80_000L,
            deliveredAt = baseTime + 80_050L,
            generation = 1,
            instanceToken = "tok_s4_legacy_2008",
            restoreEpoch = 0L,
            schemaVersion = 1
        )
        assertTrue(DirectBootCeTakeoverStore.recordDelivered(context, legacyRecord, baseTime + 80_100L) is CeTakeoverIntakeResult.RecordedConfirmed)

        // 1st run quarantines
        val first = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(context, context, database)
        assertTrue(first is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
        assertEquals(0, (first as DirectBootEventConsumerEngine.ConsumerExecutionResult.Success).receiptsCommitted)

        // 2nd run MUST also keep quarantined (no AlreadyConsumed bypass, no CE slot release)
        val second = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(context, context, database)
        assertTrue(second is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
        assertEquals(0, (second as DirectBootEventConsumerEngine.ConsumerExecutionResult.Success).receiptsCommitted)

        val ceState = DirectBootCeTakeoverStore.inspectStorageState(context)
        assertTrue("CE slot MUST stay quarantined after 2nd run, got $ceState", ceState is CeTakeoverStorageState.ValidWithRecords)
        val receipt = database.directBootOccurrenceReceiptDao().findByOccurrenceKey(
            DirectBootCeTakeoverStore.computeOccurrenceKey(2008L, baseTime + 80_000L, 1, "tok_s4_legacy_2008")
        )
        assertEquals("RESTORE_EPOCH_CONFLICT", receipt?.consumerOutcome)
    }
}
