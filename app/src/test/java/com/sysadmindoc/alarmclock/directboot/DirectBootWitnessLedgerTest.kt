package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Targeted RED/GREEN Test Suite for V2 Dedicated Durable Witness Ledger (KNO-75 Phase C7-V2-WITNESS-W1A).
 *
 * Verifies W1-R1 through W1-R12 persistence & checksum invariants:
 * - W1-R1: Valid witness record appended & read back correctly.
 * - W1-R2: Duplicate witness append is idempotent (DuplicateIgnored).
 * - W1-R3: Duplicate ID with different content returns ContentConflict.
 * - W1-R4: Lineage identity differentiation (generation, token, epoch, triggerTime).
 * - W1-R5: Max 16 capacity -> 17th item returns CapacityExhausted.
 * - W1-R6: At capacity, existing 16 records remain unmodified (zero un-ACKed record eviction).
 * - W1-R7: Corrupt payload or Checksum mismatch returns WitnessLedgerState.Corrupt.
 * - W1-R8: Unsupported schema version returns WitnessLedgerState.UnsupportedSchema.
 * - W1-R9: Commit failure returns WitnessAppendResult.PersistenceFailed.
 * - W1-R10: Concurrent append under DirectBootStorageLock preserves capacity & uniqueness.
 * - W1-R11: Ineligible identity or blank instanceToken or unauthorized Quick route rejected.
 * - W1-R12: Re-instantiated reader reads persisted state from DE SharedPreferences on disk.
 */
@RunWith(RobolectricTestRunner::class)
class DirectBootWitnessLedgerTest {

    private lateinit var deContext: Context
    private val baseTime = 1_700_000_000_000L

    private fun clearWitnessStorage() {
        val dePrefs = deContext.getSharedPreferences(DirectBootFiredWitnessLedger.PREFS_NAME, Context.MODE_PRIVATE)
        dePrefs.edit().clear().commit()
    }

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        deContext = appContext.createDeviceProtectedStorageContext()
        DirectBootFiredWitnessLedger.resetSeamsForTesting()
        clearWitnessStorage()
    }

    @After
    fun tearDown() {
        DirectBootFiredWitnessLedger.resetSeamsForTesting()
        clearWitnessStorage()
    }

    // =========================================================================
    // W1-R1: Valid Witness Append & Readback
    // =========================================================================

    @Test
    fun w1_r1_validWitness_appendedAndReadBackCorrectly() {
        val record = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 101L,
            triggerTime = baseTime + 10_000L,
            generation = 1,
            instanceToken = "tok_101",
            restoreEpoch = 1L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED
        )

        val res = DirectBootFiredWitnessLedger.appendWitness(deContext, record)
        assertTrue(res is WitnessAppendResult.Appended)
        assertEquals(1, (res as WitnessAppendResult.Appended).totalCount)

        val state = DirectBootFiredWitnessLedger.readWitnesses(deContext)
        assertTrue(state is WitnessLedgerState.ValidWithRecords)
        val records = (state as WitnessLedgerState.ValidWithRecords).records
        assertEquals(1, records.size)
        assertEquals(record, records[0])
    }

    // =========================================================================
    // W1-R2 & W1-R3: Idempotency & Content Conflict
    // =========================================================================

    @Test
    fun w1_r2_w1_r3_idempotency_and_contentConflict() {
        val record = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 102L,
            triggerTime = baseTime + 20_000L,
            generation = 1,
            instanceToken = "tok_102",
            restoreEpoch = 1L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED
        )

        // First append
        assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, record) is WitnessAppendResult.Appended)

        // W1-R2: Duplicate exact append
        val dupRes = DirectBootFiredWitnessLedger.appendWitness(deContext, record)
        assertTrue(dupRes is WitnessAppendResult.DuplicateIgnored)

        // W1-R3: Same recordId, different outcome
        val conflictingRecord = record.copy(deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_FAILED)
        val conflictRes = DirectBootFiredWitnessLedger.appendWitness(deContext, conflictingRecord)
        assertTrue(conflictRes is WitnessAppendResult.ContentConflict)
    }

    // =========================================================================
    // W1-R4: Lineage Identity Differentiation
    // =========================================================================

    @Test
    fun w1_r4_lineageIdentityDifferentiation() {
        val mode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION
        val outcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED

        val r1 = DirectBootFiredWitnessRecord.createNormal(alarmId = 201L, triggerTime = 1000L, generation = 1, instanceToken = "tok_a", restoreEpoch = 1L, deliveryMode = mode, deliveryOutcome = outcome)
        val r2 = DirectBootFiredWitnessRecord.createNormal(alarmId = 201L, triggerTime = 1000L, generation = 2, instanceToken = "tok_a", restoreEpoch = 1L, deliveryMode = mode, deliveryOutcome = outcome)
        val r3 = DirectBootFiredWitnessRecord.createNormal(alarmId = 201L, triggerTime = 1000L, generation = 1, instanceToken = "tok_b", restoreEpoch = 1L, deliveryMode = mode, deliveryOutcome = outcome)
        val r4 = DirectBootFiredWitnessRecord.createNormal(alarmId = 201L, triggerTime = 1000L, generation = 1, instanceToken = "tok_a", restoreEpoch = 2L, deliveryMode = mode, deliveryOutcome = outcome)

        assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, r1) is WitnessAppendResult.Appended)
        assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, r2) is WitnessAppendResult.Appended)
        assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, r3) is WitnessAppendResult.Appended)
        assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, r4) is WitnessAppendResult.Appended)

        val state = DirectBootFiredWitnessLedger.readWitnesses(deContext)
        assertTrue(state is WitnessLedgerState.ValidWithRecords)
        assertEquals(4, (state as WitnessLedgerState.ValidWithRecords).records.size)
    }

    // =========================================================================
    // W1-R5 & W1-R6: Capacity 16 Fencing & Zero Eviction
    // =========================================================================

    @Test
    fun w1_r5_w1_r6_capacity16_failClosed_noEviction() {
        val mode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION
        val outcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED

        for (i in 1..16) {
            val rec = DirectBootFiredWitnessRecord.createNormal(
                alarmId = i.toLong(),
                triggerTime = baseTime + (i * 1000L),
                generation = 1,
                instanceToken = "tok_$i",
                restoreEpoch = 1L,
                deliveryMode = mode,
                deliveryOutcome = outcome
            )
            val res = DirectBootFiredWitnessLedger.appendWitness(deContext, rec)
            assertTrue("Item $i append MUST succeed", res is WitnessAppendResult.Appended)
        }

        // W1-R5: 17th item rejected
        val rec17 = DirectBootFiredWitnessRecord.createNormal(alarmId = 17L, triggerTime = baseTime + 17000L, generation = 1, instanceToken = "tok_17", restoreEpoch = 1L, deliveryMode = mode, deliveryOutcome = outcome)
        val res17 = DirectBootFiredWitnessLedger.appendWitness(deContext, rec17)
        assertTrue(res17 is WitnessAppendResult.CapacityExhausted)

        // W1-R6: Existing 16 records remain untouched
        val state = DirectBootFiredWitnessLedger.readWitnesses(deContext)
        assertTrue(state is WitnessLedgerState.ValidWithRecords)
        val records = (state as WitnessLedgerState.ValidWithRecords).records
        assertEquals(16, records.size)
        assertEquals(1L, records[0].alarmId) // First item NOT evicted!
    }

    // =========================================================================
    // I1 - I9 & R1 - R8: Reader Fail-Closed Invariants & No Partial Salvage (W1C)
    // =========================================================================

    @Test
    fun i1_i9_integrity_digest_validation() {
        val prefs = deContext.getSharedPreferences(DirectBootFiredWitnessLedger.PREFS_NAME, Context.MODE_PRIVATE)

        // I1 & I2: Missing or blank checksum -> Corrupt
        val missingChecksumJson = """[{"recordId":"w1","kind":"NORMAL","alarmId":1,"triggerTime":1000,"generation":1,"instanceToken":"tok","restoreEpoch":1,"deliveryMode":"SOUND_ONLY","deliveryOutcome":"OUTPUT_START_ACCEPTED","observedAtEpochMs":1000,"schemaVersion":1}]"""
        prefs.edit().putString(DirectBootFiredWitnessLedger.KEY_WITNESS_PAYLOAD, missingChecksumJson).commit()
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.Corrupt)

        val emptyChecksumJson = """[{"recordId":"w1","kind":"NORMAL","alarmId":1,"triggerTime":1000,"generation":1,"instanceToken":"tok","restoreEpoch":1,"deliveryMode":"SOUND_ONLY","deliveryOutcome":"OUTPUT_START_ACCEPTED","observedAtEpochMs":1000,"checksum":"","schemaVersion":1}]"""
        prefs.edit().putString(DirectBootFiredWitnessLedger.KEY_WITNESS_PAYLOAD, emptyChecksumJson).commit()
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.Corrupt)

        // I3 & I6: Checksum mismatch / Tampered payload -> Corrupt
        val badChecksumJson = """[{"recordId":"w1","kind":"NORMAL","alarmId":1,"triggerTime":1000,"generation":1,"instanceToken":"tok","restoreEpoch":1,"deliveryMode":"SOUND_ONLY","deliveryOutcome":"OUTPUT_START_ACCEPTED","observedAtEpochMs":1000,"checksum":"bad_digest_hash","schemaVersion":1}]"""
        prefs.edit().putString(DirectBootFiredWitnessLedger.KEY_WITNESS_PAYLOAD, badChecksumJson).commit()
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.Corrupt)

        // I4: Missing schemaVersion -> Corrupt
        val missingSchemaJson = """[{"recordId":"w1","kind":"NORMAL","alarmId":1,"triggerTime":1000,"generation":1,"instanceToken":"tok","restoreEpoch":1,"deliveryMode":"SOUND_ONLY","deliveryOutcome":"OUTPUT_START_ACCEPTED","observedAtEpochMs":1000,"checksum":"abc"}]"""
        prefs.edit().putString(DirectBootFiredWitnessLedger.KEY_WITNESS_PAYLOAD, missingSchemaJson).commit()
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.Corrupt)

        // I5: Unsupported schemaVersion (99) -> UnsupportedSchema
        val validRecord = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 1L, triggerTime = 1000L, generation = 1, instanceToken = "tok", restoreEpoch = 1L,
            deliveryMode = "SOUND_ONLY", deliveryOutcome = "OUTPUT_START_ACCEPTED"
        )
        val unsupportedJson = """[{"recordId":"w1","kind":"NORMAL","alarmId":1,"triggerTime":1000,"generation":1,"instanceToken":"tok","restoreEpoch":1,"deliveryMode":"SOUND_ONLY","deliveryOutcome":"OUTPUT_START_ACCEPTED","observedAtEpochMs":1000,"checksum":"${validRecord.checksum}","schemaVersion":99}]"""
        prefs.edit().putString(DirectBootFiredWitnessLedger.KEY_WITNESS_PAYLOAD, unsupportedJson).commit()
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.UnsupportedSchema)
    }

    @Test
    fun r1_r8_readerInvariants_noPartialSalvage() {
        val prefs = deContext.getSharedPreferences(DirectBootFiredWitnessLedger.PREFS_NAME, Context.MODE_PRIVATE)
        val validRec1 = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 1L, triggerTime = 1000L, generation = 1, instanceToken = "tok_1", restoreEpoch = 1L,
            deliveryMode = "SOUND_ONLY", deliveryOutcome = "OUTPUT_START_ACCEPTED"
        )

        // R3: recordId Mismatch -> Corrupt
        val tamperedIdRecord = validRec1.toJson().apply { put("recordId", "witness_NORMAL_999_gen1_tok_1_epoch1_trig1000") }
        prefs.edit().putString(DirectBootFiredWitnessLedger.KEY_WITNESS_PAYLOAD, "[$tamperedIdRecord]").commit()
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.Corrupt)

        // R4: Duplicate recordId -> Corrupt
        val dupArray = "[${validRec1.toJson()}, ${validRec1.toJson()}]"
        prefs.edit().putString(DirectBootFiredWitnessLedger.KEY_WITNESS_PAYLOAD, dupArray).commit()
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.Corrupt)

        // R6: Invalid deliveryMode -> Corrupt
        val invalidModeRecord = validRec1.copy(deliveryMode = "INVALID_MODE")
        val invalidModeArray = "[${invalidModeRecord.toJson()}]"
        prefs.edit().putString(DirectBootFiredWitnessLedger.KEY_WITNESS_PAYLOAD, invalidModeArray).commit()
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.Corrupt)

        // R8: No Partial Salvage -> 1 valid item + 1 corrupt item fails ENTIRE read
        val mixedArray = "[${validRec1.toJson()}, {\"corrupt\":\"item\"}]"
        prefs.edit().putString(DirectBootFiredWitnessLedger.KEY_WITNESS_PAYLOAD, mixedArray).commit()
        val mixedState = DirectBootFiredWitnessLedger.readWitnesses(deContext)
        assertTrue("Mixed array MUST fail closed as Corrupt with zero records salvaged", mixedState is WitnessLedgerState.Corrupt)
    }

    // =========================================================================
    // M1 - M5: Mode & Outcome Invariant Closure Tests (W1C / W2-Closure)
    // =========================================================================

    @Test
    fun m1_m5_modeInvariantValidation() {
        val mode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION
        val record = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 801L, triggerTime = 1000L, generation = 1, instanceToken = "tok_801", restoreEpoch = 1L,
            deliveryMode = mode, deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_STATUS_UNKNOWN
        )

        // Reserve slot
        DirectBootFiredWitnessLedger.reserveWitnessSlot(deContext, record)

        // M1: UNKNOWN + Same Mode + Final Outcome -> Updated
        val resM1 = DirectBootFiredWitnessLedger.updateWitnessOutcome(deContext, record.recordId, mode, DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED)
        assertTrue("M1: Same mode update MUST succeed", resM1 is WitnessUpdateResult.Updated)

        // M2: UNKNOWN + Different Mode + Final Outcome -> Rejected fail-closed (StorageCorrupt)
        val unkRecord = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 802L, triggerTime = 1000L, generation = 1, instanceToken = "tok_802", restoreEpoch = 1L,
            deliveryMode = mode, deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_STATUS_UNKNOWN
        )
        DirectBootFiredWitnessLedger.reserveWitnessSlot(deContext, unkRecord)

        val resM2 = DirectBootFiredWitnessLedger.updateWitnessOutcome(deContext, unkRecord.recordId, DirectBootFiredWitnessRecord.MODE_SOUND_ONLY, DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED)
        assertTrue("M2: Different mode update on UNKNOWN record MUST fail closed", resM2 is WitnessUpdateResult.StorageCorrupt)

        // Verify unkRecord remains UNCHANGED in storage
        val state = DirectBootFiredWitnessLedger.readWitnesses(deContext)
        assertTrue(state is WitnessLedgerState.ValidWithRecords)
        val rec802 = (state as WitnessLedgerState.ValidWithRecords).records.first { it.alarmId == 802L }
        assertEquals(mode, rec802.deliveryMode)
        assertEquals(DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_STATUS_UNKNOWN, rec802.deliveryOutcome)

        // M3: Finalized Outcome + Same Mode & Outcome -> Idempotent Updated
        val resM3 = DirectBootFiredWitnessLedger.updateWitnessOutcome(deContext, record.recordId, mode, DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED)
        assertTrue("M3: Same mode & outcome update MUST return Updated", resM3 is WitnessUpdateResult.Updated)

        // M4: Finalized Outcome + Different Outcome -> Conflict rejected
        val resM4 = DirectBootFiredWitnessLedger.updateWitnessOutcome(deContext, record.recordId, mode, DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_FAILED)
        assertTrue("M4: Different outcome update MUST fail closed", resM4 is WitnessUpdateResult.StorageCorrupt)

        // M5: Commit failure during update of UNKNOWN record -> State unchanged & returns PersistenceFailed
        DirectBootFiredWitnessLedger.commitSeam = { false }
        val resM5 = DirectBootFiredWitnessLedger.updateWitnessOutcome(deContext, unkRecord.recordId, mode, DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED)
        assertTrue("M5: Commit failure MUST return PersistenceFailed", resM5 is WitnessUpdateResult.PersistenceFailed)
    }

    // =========================================================================
    // W1-R9: Commit Failure Handling
    // =========================================================================

    @Test
    fun w1_r9_commitFailure_returnsPersistenceFailed() {
        val mode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION
        val outcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED
        val record = DirectBootFiredWitnessRecord.createNormal(alarmId = 901L, triggerTime = 1000L, generation = 1, instanceToken = "tok_901", restoreEpoch = 1L, deliveryMode = mode, deliveryOutcome = outcome)

        DirectBootFiredWitnessLedger.commitSeam = { false }
        val res = DirectBootFiredWitnessLedger.appendWitness(deContext, record)

        assertTrue(res is WitnessAppendResult.PersistenceFailed)
    }

    // =========================================================================
    // W1-R10: Concurrent Append under Lock
    // =========================================================================

    @Test
    fun w1_r10_concurrentAppend_underLock() {
        val mode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION
        val outcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED
        val threads = 10
        val latch = CountDownLatch(threads)
        val executor = Executors.newFixedThreadPool(threads)

        for (i in 1..threads) {
            val alarmId = i.toLong()
            executor.execute {
                try {
                    val rec = DirectBootFiredWitnessRecord.createNormal(alarmId = alarmId, triggerTime = baseTime + (alarmId * 100L), generation = 1, instanceToken = "tok_$alarmId", restoreEpoch = 1L, deliveryMode = mode, deliveryOutcome = outcome)
                    DirectBootFiredWitnessLedger.appendWitness(deContext, rec)
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        val state = DirectBootFiredWitnessLedger.readWitnesses(deContext)
        assertTrue(state is WitnessLedgerState.ValidWithRecords)
        assertEquals(threads, (state as WitnessLedgerState.ValidWithRecords).records.size)
    }

    // =========================================================================
    // W1-R11 & W1-R12: Ineligible Filter, Blank InstanceToken & Re-instantiated Reader
    // =========================================================================

    @Test
    fun w1_r11_w1_r12_ineligibleFilter_and_reinstantiatedRead() {
        val mode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION
        val outcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED

        // W1-R11a: Quick alarm in W1 is ineligible/unauthorized
        val quickRecord = DirectBootFiredWitnessRecord(
            recordId = "witness_QUICK_99_gen1_tok_trig1000",
            kind = DirectBootFiredWitnessRecord.KIND_QUICK,
            alarmId = 99L, triggerTime = 1000L, generation = 1, instanceToken = "tok", restoreEpoch = 1L,
            deliveryMode = mode, deliveryOutcome = outcome, observedAtEpochMs = 1000L
        )
        val ineligRes = DirectBootFiredWitnessLedger.appendWitness(deContext, quickRecord)
        assertTrue(ineligRes is WitnessAppendResult.RejectedIneligible)

        // W1-R11b: Blank instanceToken for Normal record is ineligible
        val blankTokenRecord = DirectBootFiredWitnessRecord.createNormal(alarmId = 98L, triggerTime = 1000L, generation = 1, instanceToken = "", restoreEpoch = 1L, deliveryMode = mode, deliveryOutcome = outcome)
        val blankTokenRes = DirectBootFiredWitnessLedger.appendWitness(deContext, blankTokenRecord)
        assertTrue(blankTokenRes is WitnessAppendResult.RejectedIneligible)

        // W1-R12: Re-read from disk
        val record = DirectBootFiredWitnessRecord.createNormal(alarmId = 999L, triggerTime = baseTime + 999L, generation = 1, instanceToken = "tok_999", restoreEpoch = 1L, deliveryMode = mode, deliveryOutcome = outcome)
        DirectBootFiredWitnessLedger.appendWitness(deContext, record)

        val freshContext = ApplicationProvider.getApplicationContext<Context>().createDeviceProtectedStorageContext()
        assertTrue(DirectBootFiredWitnessLedger.hasWitness(freshContext, 999L, baseTime + 999L, 1, "tok_999"))
    }
}
