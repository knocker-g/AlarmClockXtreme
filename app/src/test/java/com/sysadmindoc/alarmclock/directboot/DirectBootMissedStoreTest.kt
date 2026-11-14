package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.model.Alarm
import org.json.JSONArray
import org.json.JSONObject
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
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Deterministic unit tests for KNO-75 Phase D3-C2-P3B / D3-C2-P3B-C1 Safety Corrections.
 *
 * Test coverage:
 * - M01 missing payload (Absent storage state)
 * - M02 valid empty payload (ValidEmpty storage state)
 * - M03 single valid record recording and readback
 * - M04 duplicate exact identity returns DuplicateIgnored without modifying storage
 * - M05 same alarm different generation creates distinct records
 * - M06 different instanceToken creates distinct records
 * - M07 different triggerTime creates distinct records
 * - M08 capacity minus one (15 records accepted)
 * - M09 capacity exact (16 records accepted)
 * - M10 capacity exceeded (17th distinct record rejected with CapacityExhausted, zero eviction)
 * - M11 duplicate when full returns DuplicateIgnored before capacity check
 * - M12 corrupt JSON cannot be overwritten by append or ACK (fail-closed, raw bytes preserved)
 * - M13 unsupported schema preserves raw bytes (fail-closed, no migration)
 * - M14 invalid record identity or fields rejected with RejectedIneligible
 * - M15 no silent truncation (stored list is never pruned or dropped)
 * - M16 real failure injection: commit returns false -> PersistenceUncertain with PRIOR_PAYLOAD_OBSERVED
 * - M17 real failure injection: commit throws IOException -> PersistenceUncertain with PRIOR_PAYLOAD_OBSERVED
 * - M18 real failure injection: cancellation at commit boundary -> PersistenceUncertain
 * - M19 real failure injection: commit false with in-memory intended payload -> PersistenceUncertain (never false success)
 * - M20 real failure injection: commit false with corrupt payload -> CORRUPT_PAYLOAD_OBSERVED
 * - M21 non-destructive ACK safety: acknowledgeRecord returns AckNotQualified, preserves all records intact
 * - M22 Queue and Ledger payloads completely untouched by MissedStore operations
 * - M23 P3-A MissedStorageState mapping fidelity
 * - M24 privacy audit: zero user-authored labels, contacts, URIs, or secrets
 * - M25 concurrent in-process appends serialize safely under single-writer contract
 * - M26 stored over-capacity (> 16 records) is strictly CORRUPT and non-writable
 * - M27 stored duplicate recordIds is strictly CORRUPT and non-writable
 * - M28 approved classifications (OVERDUE, COLD_BOOT) accepted
 * - M29 unknown classification rejected on append
 * - M30 unknown classification in storage strictly CORRUPT and non-writable
 * - M31 P3-C pure serialization helpers
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DirectBootMissedStoreTest {

    private lateinit var context: Context
    private val baseTime = 1_700_000_000_000L

    private fun resetMissedStoreForTesting(context: Context): Boolean {
        return context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootMissedStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD)
            .commit()
    }

    private fun normalMissedRecord(
        alarmId: Long,
        triggerTime: Long = baseTime + 100_000L,
        missedAt: Long = baseTime + 100_500L,
        generation: Int = 1,
        instanceToken: String = "token_$alarmId",
        classification: String = DirectBootMissedRecord.CLASSIFICATION_OVERDUE,
        restoreEpoch: Long = 1L
    ): DirectBootMissedRecord = DirectBootMissedRecord.createNormal(
        alarmId = alarmId,
        triggerTime = triggerTime,
        missedAt = missedAt,
        generation = generation,
        instanceToken = instanceToken,
        classification = classification,
        restoreEpoch = restoreEpoch
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetMissedStoreForTesting(context)
        DirectBootMissedStore.commitSeam = { editor ->
            if (editor.commit()) CommitExecutionOutcome.Success else CommitExecutionOutcome.CommitFalse
        }
    }

    @After
    fun tearDown() {
        DirectBootMissedStore.commitSeam = { editor ->
            if (editor.commit()) CommitExecutionOutcome.Success else CommitExecutionOutcome.CommitFalse
        }
    }

    @Test
    fun M01_missingPayload() {
        assertEquals(MissedStoreClassification.ABSENT, DirectBootMissedStore.classifyStorage(context))
        val state = DirectBootMissedStore.inspectStorageState(context)
        assertTrue("Storage must be Absent", state is MissedStoreStorageState.Absent)
        val readState = DirectBootMissedStore.readUnacknowledgedRecords(context)
        assertTrue("Read unacknowledged must be Absent", readState is MissedStoreStorageState.Absent)

        val policyState = DirectBootMissedStore.toPolicyState(context)
        assertTrue("Absent storage maps to MissedStorageState.VerifiedEmpty", policyState is MissedStorageState.VerifiedEmpty)
    }

    @Test
    fun M02_validEmptyPayload() {
        val emptyJson = JSONObject().apply {
            put("schemaVersion", DirectBootMissedStore.CURRENT_SCHEMA_VERSION)
            put("records", JSONArray())
        }.toString()

        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootMissedStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD, emptyJson).commit()

        assertEquals(MissedStoreClassification.VALID_EMPTY, DirectBootMissedStore.classifyStorage(context))
        assertTrue(DirectBootMissedStore.inspectStorageState(context) is MissedStoreStorageState.ValidEmpty)
        assertTrue(DirectBootMissedStore.readUnacknowledgedRecords(context) is MissedStoreStorageState.ValidEmpty)

        val policyState = DirectBootMissedStore.toPolicyState(context)
        assertTrue("ValidEmpty storage maps to MissedStorageState.VerifiedEmpty", policyState is MissedStorageState.VerifiedEmpty)
    }

    @Test
    fun M03_singleValidRecord() {
        val record = normalMissedRecord(alarmId = 1L)
        val recordResult = DirectBootMissedStore.recordMissed(context, record)
        assertTrue("Recording valid missed record must succeed", recordResult is MissedRecordResult.Recorded)
        assertEquals(1, (recordResult as MissedRecordResult.Recorded).totalCount)

        assertEquals(MissedStoreClassification.VALID_WITH_RECORDS, DirectBootMissedStore.classifyStorage(context))
        val readState = DirectBootMissedStore.readUnacknowledgedRecords(context)
        assertTrue(readState is MissedStoreStorageState.ValidWithRecords)
        val list = (readState as MissedStoreStorageState.ValidWithRecords).records
        assertEquals(1, list.size)
        assertEquals(record.recordId, list[0].recordId)
        assertEquals(record.alarmId, list[0].alarmId)
        assertEquals(record.triggerTime, list[0].triggerTime)
        assertEquals(record.missedAt, list[0].missedAt)
        assertEquals(record.generation, list[0].generation)
        assertEquals(record.instanceToken, list[0].instanceToken)
        assertEquals(record.classification, list[0].classification)

        val policyState = DirectBootMissedStore.toPolicyState(context)
        assertTrue(policyState is MissedStorageState.VerifiedRecords)
        val ids = (policyState as MissedStorageState.VerifiedRecords).recordIds
        assertEquals(setOf(record.recordId), ids)
    }

    @Test
    fun M04_duplicateExactIdentity() {
        val record = normalMissedRecord(alarmId = 1L)
        val firstRes = DirectBootMissedStore.recordMissed(context, record)
        assertTrue(firstRes is MissedRecordResult.Recorded)

        val duplicateRes = DirectBootMissedStore.recordMissed(context, record)
        assertTrue("Duplicate recordId must return DuplicateIgnored", duplicateRes is MissedRecordResult.DuplicateIgnored)
        val dup = duplicateRes as MissedRecordResult.DuplicateIgnored
        assertEquals(record.recordId, dup.existing.recordId)
        assertEquals(1, dup.totalCount)

        val readState = DirectBootMissedStore.readUnacknowledgedRecords(context)
        assertTrue(readState is MissedStoreStorageState.ValidWithRecords)
        assertEquals(1, (readState as MissedStoreStorageState.ValidWithRecords).records.size)
    }

    @Test
    fun M05_sameAlarmDifferentGeneration() {
        val recordGen1 = normalMissedRecord(alarmId = 1L, generation = 1)
        val recordGen2 = normalMissedRecord(alarmId = 1L, generation = 2)

        assertFalse("Different generations must have different recordIds", recordGen1.recordId == recordGen2.recordId)

        assertTrue(DirectBootMissedStore.recordMissed(context, recordGen1) is MissedRecordResult.Recorded)
        val res2 = DirectBootMissedStore.recordMissed(context, recordGen2)
        assertTrue("Distinct generation must be recorded", res2 is MissedRecordResult.Recorded)
        assertEquals(2, (res2 as MissedRecordResult.Recorded).totalCount)

        val readState = DirectBootMissedStore.readUnacknowledgedRecords(context)
        assertTrue(readState is MissedStoreStorageState.ValidWithRecords)
        val records = (readState as MissedStoreStorageState.ValidWithRecords).records
        assertEquals(2, records.size)
        assertTrue(records.any { it.generation == 1 })
        assertTrue(records.any { it.generation == 2 })
    }

    @Test
    fun M06_differentInstanceToken() {
        val recordTokenA = normalMissedRecord(alarmId = 1L, instanceToken = "token_A")
        val recordTokenB = normalMissedRecord(alarmId = 1L, instanceToken = "token_B")

        assertFalse("Different tokens must produce distinct recordIds", recordTokenA.recordId == recordTokenB.recordId)

        assertTrue(DirectBootMissedStore.recordMissed(context, recordTokenA) is MissedRecordResult.Recorded)
        val resB = DirectBootMissedStore.recordMissed(context, recordTokenB)
        assertTrue(resB is MissedRecordResult.Recorded)
        assertEquals(2, (resB as MissedRecordResult.Recorded).totalCount)
    }

    @Test
    fun M07_differentTriggerTime() {
        val recordTime1 = normalMissedRecord(alarmId = 1L, triggerTime = baseTime + 1000L)
        val recordTime2 = normalMissedRecord(alarmId = 1L, triggerTime = baseTime + 2000L)

        assertFalse("Different trigger times must produce distinct recordIds", recordTime1.recordId == recordTime2.recordId)

        assertTrue(DirectBootMissedStore.recordMissed(context, recordTime1) is MissedRecordResult.Recorded)
        val res2 = DirectBootMissedStore.recordMissed(context, recordTime2)
        assertTrue(res2 is MissedRecordResult.Recorded)
        assertEquals(2, (res2 as MissedRecordResult.Recorded).totalCount)
    }

    @Test
    fun M08_capacityMinusOne() {
        for (i in 1..15) {
            val res = DirectBootMissedStore.recordMissed(context, normalMissedRecord(alarmId = i.toLong()))
            assertTrue("Record $i must succeed", res is MissedRecordResult.Recorded)
            assertEquals(i, (res as MissedRecordResult.Recorded).totalCount)
        }

        val readState = DirectBootMissedStore.readUnacknowledgedRecords(context)
        assertTrue(readState is MissedStoreStorageState.ValidWithRecords)
        assertEquals(15, (readState as MissedStoreStorageState.ValidWithRecords).records.size)
    }

    @Test
    fun M09_capacityExact() {
        for (i in 1..16) {
            val res = DirectBootMissedStore.recordMissed(context, normalMissedRecord(alarmId = i.toLong()))
            assertTrue("Record $i must succeed", res is MissedRecordResult.Recorded)
            assertEquals(i, (res as MissedRecordResult.Recorded).totalCount)
        }

        val readState = DirectBootMissedStore.readUnacknowledgedRecords(context)
        assertTrue(readState is MissedStoreStorageState.ValidWithRecords)
        assertEquals(16, (readState as MissedStoreStorageState.ValidWithRecords).records.size)
    }

    @Test
    fun M10_capacityExceededNoEviction() {
        val initialIds = mutableListOf<String>()
        for (i in 1..16) {
            val rec = normalMissedRecord(alarmId = i.toLong())
            initialIds.add(rec.recordId)
            DirectBootMissedStore.recordMissed(context, rec)
        }

        val record17 = normalMissedRecord(alarmId = 17L)
        val res17 = DirectBootMissedStore.recordMissed(context, record17)
        assertTrue("17th record must be rejected with CapacityExhausted", res17 is MissedRecordResult.CapacityExhausted)
        val capEx = res17 as MissedRecordResult.CapacityExhausted
        assertEquals(16, capEx.currentCount)
        assertEquals(16, capEx.maxCapacity)

        // Strict verification: all prior 16 records are intact and zero eviction occurred
        val readState = DirectBootMissedStore.readUnacknowledgedRecords(context)
        assertTrue(readState is MissedStoreStorageState.ValidWithRecords)
        val currentRecords = (readState as MissedStoreStorageState.ValidWithRecords).records
        assertEquals(16, currentRecords.size)
        assertEquals(initialIds, currentRecords.map { it.recordId })
        assertFalse(currentRecords.any { it.recordId == record17.recordId })
    }

    @Test
    fun M11_duplicateWhenFull() {
        val records = (1..16).map { normalMissedRecord(alarmId = it.toLong()) }
        records.forEach { DirectBootMissedStore.recordMissed(context, it) }

        // Attempt to record record[5] again while capacity is full
        val targetDup = records[5]
        val resDup = DirectBootMissedStore.recordMissed(context, targetDup)
        assertTrue("Duplicate check must precede capacity check", resDup is MissedRecordResult.DuplicateIgnored)
        val dupIgnored = resDup as MissedRecordResult.DuplicateIgnored
        assertEquals(targetDup.recordId, dupIgnored.existing.recordId)
        assertEquals(16, dupIgnored.totalCount)

        // Storage state remains intact
        val readState = DirectBootMissedStore.readUnacknowledgedRecords(context)
        assertTrue(readState is MissedStoreStorageState.ValidWithRecords)
        assertEquals(16, (readState as MissedStoreStorageState.ValidWithRecords).records.size)
    }

    @Test
    fun M12_corruptJsonCannotBeOverwritten() {
        val corruptString = "{ corrupt raw unparseable json string !!!"
        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootMissedStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD, corruptString).commit()

        assertEquals(MissedStoreClassification.CORRUPT, DirectBootMissedStore.classifyStorage(context))
        val readState = DirectBootMissedStore.readUnacknowledgedRecords(context)
        assertTrue("Corrupt storage must return Corrupt state", readState is MissedStoreStorageState.Corrupt)

        val policyState = DirectBootMissedStore.toPolicyState(context)
        assertTrue("Corrupt store maps to MissedStorageState.Corrupt", policyState is MissedStorageState.Corrupt)

        val appendRes = DirectBootMissedStore.recordMissed(context, normalMissedRecord(alarmId = 1L))
        assertTrue("Append onto corrupt storage must fail closed", appendRes is MissedRecordResult.StorageCorrupt)

        val ackRes = DirectBootMissedStore.acknowledgeRecord(context, "some_id")
        assertTrue("ACK onto corrupt storage must fail closed", ackRes is MissedAckResult.StorageCorrupt)

        // Raw payload must remain completely untouched
        val rawAfter = DirectBootMissedStore.getRawPayload(context)
        assertEquals("Corrupt payload must remain unchanged", corruptString, rawAfter)
    }

    @Test
    fun M13_unsupportedSchemaPreservesRawBytes() {
        val unsupportedJson = JSONObject().apply {
            put("schemaVersion", 42)
            put("records", JSONArray().apply {
                put(normalMissedRecord(1L).toJson())
            })
        }.toString()

        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootMissedStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD, unsupportedJson).commit()

        assertEquals(MissedStoreClassification.UNSUPPORTED_SCHEMA, DirectBootMissedStore.classifyStorage(context))
        val readState = DirectBootMissedStore.readUnacknowledgedRecords(context)
        assertTrue(readState is MissedStoreStorageState.UnsupportedSchema)
        val unsupp = readState as MissedStoreStorageState.UnsupportedSchema
        assertEquals(42, unsupp.foundVersion)
        assertEquals(DirectBootMissedStore.CURRENT_SCHEMA_VERSION, unsupp.expectedVersion)

        val policyState = DirectBootMissedStore.toPolicyState(context)
        assertTrue("Unsupported schema maps to MissedStorageState.UnsupportedSchema", policyState is MissedStorageState.UnsupportedSchema)

        val appendRes = DirectBootMissedStore.recordMissed(context, normalMissedRecord(alarmId = 2L))
        assertTrue(appendRes is MissedRecordResult.UnsupportedSchema)

        val ackRes = DirectBootMissedStore.acknowledgeRecord(context, "some_id")
        assertTrue(ackRes is MissedAckResult.UnsupportedSchema)

        // Raw payload preserved without migration
        val rawAfter = DirectBootMissedStore.getRawPayload(context)
        assertEquals("Unsupported schema payload must be preserved verbatim", unsupportedJson, rawAfter)
    }

    @Test
    fun M14_invalidRecordIdentityOrFieldsRejected() {
        // Invalid alarmId <= 0
        val invalidAlarmId = normalMissedRecord(alarmId = 1L).copy(alarmId = 0L)
        val res1 = DirectBootMissedStore.recordMissed(context, invalidAlarmId)
        assertTrue(res1 is MissedRecordResult.RejectedIneligible)

        // Invalid triggerTime <= 0
        val invalidTrigger = normalMissedRecord(alarmId = 1L).copy(triggerTime = -1L)
        val res2 = DirectBootMissedStore.recordMissed(context, invalidTrigger)
        assertTrue(res2 is MissedRecordResult.RejectedIneligible)

        // Invalid missedAt <= 0
        val invalidMissedAt = normalMissedRecord(alarmId = 1L).copy(missedAt = 0L)
        val res3 = DirectBootMissedStore.recordMissed(context, invalidMissedAt)
        assertTrue(res3 is MissedRecordResult.RejectedIneligible)

        // Invalid generation < 1
        val invalidGen = normalMissedRecord(alarmId = 1L).copy(generation = 0)
        val res4 = DirectBootMissedStore.recordMissed(context, invalidGen)
        assertTrue(res4 is MissedRecordResult.RejectedIneligible)

        // Blank instanceToken
        val blankToken = normalMissedRecord(alarmId = 1L).copy(instanceToken = "   ")
        val res5 = DirectBootMissedStore.recordMissed(context, blankToken)
        assertTrue(res5 is MissedRecordResult.RejectedIneligible)

        // Blank or invalid classification
        val blankClass = normalMissedRecord(alarmId = 1L).copy(classification = "")
        val res6 = DirectBootMissedStore.recordMissed(context, blankClass)
        assertTrue(res6 is MissedRecordResult.RejectedIneligible)
        assertEquals(MissedValidationStatus.INVALID_CLASSIFICATION, (res6 as MissedRecordResult.RejectedIneligible).status)

        // Mismatched recordId
        val mismatchedId = normalMissedRecord(alarmId = 1L).copy(recordId = "arbitrary_forged_id")
        val res7 = DirectBootMissedStore.recordMissed(context, mismatchedId)
        assertTrue(res7 is MissedRecordResult.RejectedIneligible)

        // Storage remains absent
        assertEquals(MissedStoreClassification.ABSENT, DirectBootMissedStore.classifyStorage(context))
    }

    @Test
    fun M15_noSilentTruncation() {
        for (i in 1..16) {
            DirectBootMissedStore.recordMissed(context, normalMissedRecord(alarmId = i.toLong()))
        }
        val read1 = DirectBootMissedStore.readUnacknowledgedRecords(context)
        assertTrue(read1 is MissedStoreStorageState.ValidWithRecords)
        assertEquals(16, (read1 as MissedStoreStorageState.ValidWithRecords).records.size)

        // Repeated re-reads do not truncate or mutate
        val read2 = DirectBootMissedStore.readUnacknowledgedRecords(context)
        assertTrue(read2 is MissedStoreStorageState.ValidWithRecords)
        assertEquals(16, (read2 as MissedStoreStorageState.ValidWithRecords).records.size)
    }

    @Test
    fun M16_commitReturnsFalse_reportsPersistenceUncertainWithPriorPayload() {
        val record = normalMissedRecord(1L)
        DirectBootMissedStore.commitSeam = { _ -> CommitExecutionOutcome.CommitFalse }

        val res = DirectBootMissedStore.recordMissed(context, record)
        assertTrue("Commit false must return PersistenceUncertain", res is MissedRecordResult.PersistenceUncertain)
        val uncertain = res as MissedRecordResult.PersistenceUncertain
        assertTrue(uncertain.reason.contains("commit returned false"))
        assertEquals(CommitReadbackObservation.PRIOR_PAYLOAD_OBSERVED, uncertain.observation)
        assertEquals(0, uncertain.priorCount)

        // Verify storage was not mutated to claim false success
        assertEquals(MissedStoreClassification.ABSENT, DirectBootMissedStore.classifyStorage(context))
    }

    @Test
    fun M17_commitThrowsException_reportsPersistenceUncertain() {
        val record = normalMissedRecord(1L)
        DirectBootMissedStore.commitSeam = { _ -> throw IOException("Simulated disk full") }

        val res = DirectBootMissedStore.recordMissed(context, record)
        assertTrue("Exception must return PersistenceUncertain", res is MissedRecordResult.PersistenceUncertain)
        val uncertain = res as MissedRecordResult.PersistenceUncertain
        assertTrue(uncertain.reason.contains("Simulated disk full"))
        assertEquals(CommitReadbackObservation.PRIOR_PAYLOAD_OBSERVED, uncertain.observation)
    }

    @Test
    fun M18_commitBoundaryCancellation_reportsPersistenceUncertain() {
        val record = normalMissedRecord(1L)
        DirectBootMissedStore.commitSeam = { _ -> throw CancellationException("Job cancelled during commit") }

        val res = DirectBootMissedStore.recordMissed(context, record)
        assertTrue("Cancellation must return PersistenceUncertain", res is MissedRecordResult.PersistenceUncertain)
        val uncertain = res as MissedRecordResult.PersistenceUncertain
        assertTrue(uncertain.reason.contains("Job cancelled during commit"))
    }

    @Test
    fun M19_commitFalseWithIntendedPayloadObserved_neverInfersDurableSuccess() {
        val record = normalMissedRecord(1L)
        // Simulate case where editor writes in-memory, but commit reports false (e.g. disk write failed)
        DirectBootMissedStore.commitSeam = { editor ->
            editor.commit() // write into robolectric in-memory prefs
            CommitExecutionOutcome.CommitFalse // but report commit failed
        }

        val res = DirectBootMissedStore.recordMissed(context, record)
        assertFalse("Must never infer durable success from in-memory readback", res is MissedRecordResult.Recorded)
        assertTrue("Must return PersistenceUncertain", res is MissedRecordResult.PersistenceUncertain)
        val uncertain = res as MissedRecordResult.PersistenceUncertain
        assertEquals(CommitReadbackObservation.INTENDED_PAYLOAD_OBSERVED, uncertain.observation)
        assertEquals("SharedPreferences commit returned false", uncertain.reason)
    }

    @Test
    fun M20_commitFalseWithCorruptPayloadObserved() {
        val record = normalMissedRecord(1L)
        DirectBootMissedStore.commitSeam = { _ ->
            context.applicationContext.createDeviceProtectedStorageContext()
                .getSharedPreferences(DirectBootMissedStore.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD, "{ corrupt raw data").commit()
            CommitExecutionOutcome.CommitFalse
        }

        val res = DirectBootMissedStore.recordMissed(context, record)
        assertTrue(res is MissedRecordResult.PersistenceUncertain)
        val uncertain = res as MissedRecordResult.PersistenceUncertain
        assertEquals(CommitReadbackObservation.CORRUPT_PAYLOAD_OBSERVED, uncertain.observation)
        assertEquals("{ corrupt raw data", uncertain.rawPayloadAfter)
    }

    @Test
    fun M21_ackIsNotQualified_preservesAllRecordsIntact() {
        val record1 = normalMissedRecord(1L)
        val record2 = normalMissedRecord(2L)
        DirectBootMissedStore.recordMissed(context, record1)
        DirectBootMissedStore.recordMissed(context, record2)

        // Attempt ACK on record1
        val ackRes = DirectBootMissedStore.acknowledgeRecord(context, record1.recordId)
        assertTrue("ACK must report AckNotQualified in inactive slice", ackRes is MissedAckResult.AckNotQualified)
        val notQualified = ackRes as MissedAckResult.AckNotQualified
        assertEquals(2, notQualified.currentCount)
        assertTrue(notQualified.reason.contains("requires P3-D qualification"))

        // Strict verification: both record1 and record2 remain completely intact in storage
        val readState = DirectBootMissedStore.readUnacknowledgedRecords(context)
        assertTrue(readState is MissedStoreStorageState.ValidWithRecords)
        val records = (readState as MissedStoreStorageState.ValidWithRecords).records
        assertEquals(2, records.size)
        assertEquals(record1.recordId, records[0].recordId)
        assertEquals(record2.recordId, records[1].recordId)
    }

    @Test
    fun M22_queueAndLedgerPayloadsUntouched() {
        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootMissedStore.PREFS_NAME, Context.MODE_PRIVATE)

        // Seed fake Queue and Ledger payloads
        val queuePayload = "{\"queue\": \"untouched\"}"
        val ledgerPayload = "{\"ledger\": \"untouched\"}"
        prefs.edit()
            .putString("direct_boot_queue", queuePayload)
            .putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, ledgerPayload)
            .commit()

        // Perform MissedStore operations
        val record = normalMissedRecord(1L)
        DirectBootMissedStore.recordMissed(context, record)
        DirectBootMissedStore.acknowledgeRecord(context, record.recordId)

        // Verify Queue and Ledger payloads are completely untouched
        assertEquals(queuePayload, prefs.getString("direct_boot_queue", null))
        assertEquals(ledgerPayload, prefs.getString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, null))
    }

    @Test
    fun M23_p3aStateConversionFidelity() {
        // Absent -> VerifiedEmpty
        assertEquals(MissedStorageState.VerifiedEmpty, MissedStoreStorageState.Absent.toPolicyMissedStorageState())

        // ValidEmpty -> VerifiedEmpty
        assertEquals(MissedStorageState.VerifiedEmpty, MissedStoreStorageState.ValidEmpty.toPolicyMissedStorageState())

        // ValidWithRecords -> VerifiedRecords(setOf(ids))
        val rec1 = normalMissedRecord(1L)
        val rec2 = normalMissedRecord(2L)
        val validState = MissedStoreStorageState.ValidWithRecords(listOf(rec1, rec2))
        val policyRecords = validState.toPolicyMissedStorageState()
        assertTrue(policyRecords is MissedStorageState.VerifiedRecords)
        assertEquals(setOf(rec1.recordId, rec2.recordId), (policyRecords as MissedStorageState.VerifiedRecords).recordIds)

        // Corrupt -> Corrupt
        val corruptState = MissedStoreStorageState.Corrupt("Bad checksum")
        val policyCorrupt = corruptState.toPolicyMissedStorageState()
        assertTrue(policyCorrupt is MissedStorageState.Corrupt)
        assertEquals("Bad checksum", (policyCorrupt as MissedStorageState.Corrupt).reason)

        // UnsupportedSchema -> UnsupportedSchema
        val unsuppState = MissedStoreStorageState.UnsupportedSchema(foundVersion = 3, expectedVersion = 1)
        val policyUnsupp = unsuppState.toPolicyMissedStorageState()
        assertTrue(policyUnsupp is MissedStorageState.UnsupportedSchema)
        assertEquals(3, (policyUnsupp as MissedStorageState.UnsupportedSchema).foundVersion)
        assertEquals(1, (policyUnsupp as MissedStorageState.UnsupportedSchema).expectedVersion)

        // PersistenceUncertain -> UnknownUncertain
        val uncertainState = MissedStoreStorageState.PersistenceUncertain("Lock timed out")
        val policyUncertain = uncertainState.toPolicyMissedStorageState()
        assertTrue(policyUncertain is MissedStorageState.UnknownUncertain)
        assertEquals("Lock timed out", (policyUncertain as MissedStorageState.UnknownUncertain).reason)
    }

    @Test
    fun M24_privacyAudit() {
        val record = normalMissedRecord(alarmId = 1L, classification = DirectBootMissedRecord.CLASSIFICATION_COLD_BOOT)
        DirectBootMissedStore.recordMissed(context, record)

        val raw = DirectBootMissedStore.getRawPayload(context) ?: ""
        assertTrue("Payload must be present", raw.isNotEmpty())
        assertFalse("No label field", raw.contains("\"label\":"))
        assertFalse("No contacts", raw.contains("contacts"))
        assertFalse("No guardian", raw.contains("guardian"))
        assertFalse("No content uri", raw.contains("content://"))
        assertFalse("No secret", raw.contains("secret"))
        assertFalse("No audio uri", raw.contains("uri"))
        assertFalse("No media path", raw.contains("path"))
    }

    @Test
    fun M25_concurrentInProcessAppendsSerializeSafely() {
        val threadCount = 8
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val finishLatch = CountDownLatch(threadCount)

        for (i in 1..threadCount) {
            val record = normalMissedRecord(alarmId = i.toLong())
            executor.execute {
                try {
                    startLatch.await(5, TimeUnit.SECONDS)
                    DirectBootMissedStore.recordMissed(context, record)
                } finally {
                    finishLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue("All threads must finish", finishLatch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        val readState = DirectBootMissedStore.readUnacknowledgedRecords(context)
        assertTrue(readState is MissedStoreStorageState.ValidWithRecords)
        val records = (readState as MissedStoreStorageState.ValidWithRecords).records
        assertEquals(threadCount, records.size)
        val ids = records.map { it.alarmId }.toSet()
        assertEquals((1..threadCount).map { it.toLong() }.toSet(), ids)
    }

    @Test
    fun M26_storedOverCapacityIsStrictlyCorrupt() {
        val array = JSONArray()
        for (i in 1..17) {
            array.put(normalMissedRecord(alarmId = i.toLong()).toJson())
        }
        val overCapacityJson = JSONObject().apply {
            put("schemaVersion", DirectBootMissedStore.CURRENT_SCHEMA_VERSION)
            put("records", array)
        }.toString()

        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootMissedStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD, overCapacityJson).commit()

        assertEquals(MissedStoreClassification.CORRUPT, DirectBootMissedStore.classifyStorage(context))
        val readState = DirectBootMissedStore.readUnacknowledgedRecords(context)
        assertTrue("Over-capacity storage must be classified as CORRUPT", readState is MissedStoreStorageState.Corrupt)

        val appendRes = DirectBootMissedStore.recordMissed(context, normalMissedRecord(alarmId = 18L))
        assertTrue(appendRes is MissedRecordResult.StorageCorrupt)

        val ackRes = DirectBootMissedStore.acknowledgeRecord(context, "some_id")
        assertTrue(ackRes is MissedAckResult.StorageCorrupt)

        assertEquals("Raw payload must remain completely untouched", overCapacityJson, DirectBootMissedStore.getRawPayload(context))
    }

    @Test
    fun M27_storedDuplicateRecordIdsIsStrictlyCorrupt() {
        val record = normalMissedRecord(alarmId = 33L)
        val array = JSONArray().apply {
            put(record.toJson())
            put(record.toJson()) // duplicate recordId in stored payload
        }
        val duplicateJson = JSONObject().apply {
            put("schemaVersion", DirectBootMissedStore.CURRENT_SCHEMA_VERSION)
            put("records", array)
        }.toString()

        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootMissedStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD, duplicateJson).commit()

        assertEquals(MissedStoreClassification.CORRUPT, DirectBootMissedStore.classifyStorage(context))
        val readState = DirectBootMissedStore.readUnacknowledgedRecords(context)
        assertTrue("Storage with duplicate recordIds must be classified as CORRUPT", readState is MissedStoreStorageState.Corrupt)

        val appendRes = DirectBootMissedStore.recordMissed(context, normalMissedRecord(alarmId = 34L))
        assertTrue(appendRes is MissedRecordResult.StorageCorrupt)

        val ackRes = DirectBootMissedStore.acknowledgeRecord(context, record.recordId)
        assertTrue(ackRes is MissedAckResult.StorageCorrupt)

        assertEquals("Raw payload must remain completely untouched", duplicateJson, DirectBootMissedStore.getRawPayload(context))
    }

    @Test
    fun M28_approvedClassificationsAccepted() {
        val overdueRecord = normalMissedRecord(alarmId = 1L, classification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE)
        val coldBootRecord = normalMissedRecord(alarmId = 2L, classification = DirectBootMissedRecord.CLASSIFICATION_COLD_BOOT)

        val res1 = DirectBootMissedStore.recordMissed(context, overdueRecord)
        assertTrue("OVERDUE classification must be accepted", res1 is MissedRecordResult.Recorded)

        val res2 = DirectBootMissedStore.recordMissed(context, coldBootRecord)
        assertTrue("COLD_BOOT classification must be accepted", res2 is MissedRecordResult.Recorded)

        val readState = DirectBootMissedStore.readUnacknowledgedRecords(context)
        assertTrue(readState is MissedStoreStorageState.ValidWithRecords)
        val list = (readState as MissedStoreStorageState.ValidWithRecords).records
        assertEquals(2, list.size)
        assertEquals(DirectBootMissedRecord.CLASSIFICATION_OVERDUE, list[0].classification)
        assertEquals(DirectBootMissedRecord.CLASSIFICATION_COLD_BOOT, list[1].classification)
    }

    @Test
    fun M29_unknownClassificationRejectedOnAppend() {
        val badClassRecord = normalMissedRecord(alarmId = 1L).copy(classification = "CUSTOM_CLASSIFICATION")
        val res = DirectBootMissedStore.recordMissed(context, badClassRecord)
        assertTrue("Unknown classification must be rejected", res is MissedRecordResult.RejectedIneligible)
        assertEquals(MissedValidationStatus.INVALID_CLASSIFICATION, (res as MissedRecordResult.RejectedIneligible).status)

        assertEquals(MissedStoreClassification.ABSENT, DirectBootMissedStore.classifyStorage(context))
    }

    @Test
    fun M30_unknownClassificationInStorageIsStrictlyCorrupt() {
        // Craft JSON containing a record with unknown classification
        val badJson = JSONObject().apply {
            put("schemaVersion", DirectBootMissedStore.CURRENT_SCHEMA_VERSION)
            put("records", JSONArray().apply {
                val rec = normalMissedRecord(1L).toJson()
                rec.put("classification", "FABRICATED_CLASSIFICATION")
                put(rec)
            })
        }.toString()

        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootMissedStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD, badJson).commit()

        assertEquals(MissedStoreClassification.CORRUPT, DirectBootMissedStore.classifyStorage(context))
        val readState = DirectBootMissedStore.readUnacknowledgedRecords(context)
        assertTrue("Unknown classification in storage must be CORRUPT", readState is MissedStoreStorageState.Corrupt)

        // Storage is non-writable
        val appendRes = DirectBootMissedStore.recordMissed(context, normalMissedRecord(2L))
        assertTrue(appendRes is MissedRecordResult.StorageCorrupt)

        val ackRes = DirectBootMissedStore.acknowledgeRecord(context, "some_id")
        assertTrue(ackRes is MissedAckResult.StorageCorrupt)

        assertEquals("Raw payload must remain completely untouched", badJson, DirectBootMissedStore.getRawPayload(context))
    }

    @Test
    fun M31_p3cPureSerializationHelpers() {
        val records = listOf(normalMissedRecord(1L), normalMissedRecord(2L))
        val payload = DirectBootMissedStore.serializeRecordsToPayload(records)
        assertEquals(DirectBootMissedStore.CURRENT_SCHEMA_VERSION, payload.getInt("schemaVersion"))
        assertEquals(2, payload.getJSONArray("records").length())

        val jsonString = DirectBootMissedStore.serializeRecordsToJson(records)
        assertTrue(jsonString.contains(records[0].recordId))
        assertTrue(jsonString.contains(records[1].recordId))
    }
}
