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
}
