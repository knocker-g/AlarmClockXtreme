package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.Intent
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

/**
 * Exact Identity Collision Discriminator Test Suite for W3 CE Consumer Integration (KNO-75 Phase C7-V2-W3-EXACT-COLLISION-DISCRIMINATOR).
 *
 * Verifies Hostile Collision Discriminator Tests H1 & H2:
 * - H1: Exact 4-Key Collision (alarmId, triggerTime, generation, instanceToken match) with DIFFERENT restoreEpoch (1L vs 2L).
 * - H2: Exact Canonical Hash Collision with DIFFERENT restoreEpoch (1L vs 2L).
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
        clearStorage()
    }

    @After
    fun tearDown() {
        database.close()
        DirectBootCeTakeoverStore.clear(ceContext)
        DirectBootCeTakeoverStore.resetSeamsForTesting()
        DirectBootFiredWitnessLedger.resetSeamsForTesting()
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
}
