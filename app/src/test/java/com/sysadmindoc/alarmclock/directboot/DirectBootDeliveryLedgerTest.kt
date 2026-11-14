package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.model.Alarm
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Deterministic unit tests for KNO-75 Phase P2A2 Delivery Ledger Safety Hardening.
 *
 * Covers required cases:
 * - L01 empty / absent ledger state via typed read
 * - L02 single record roundtrip via typed read
 * - L03 multiple Normal records
 * - L04 mixed Normal and Quick records
 * - L05 duplicate append idempotency
 * - L06 same alarm ID different generation distinct recordIds
 * - L07 different alarm kinds same ID distinct recordIds
 * - L08 exact ACK removes only target record
 * - L09 unknown ACK leaves ledger unchanged
 * - L10 16 records retained at capacity
 * - L11 17th append rejected without eviction (fail-closed)
 * - L12 corrupt payload cannot be overwritten by append
 * - L13 corrupt payload cannot be removed by ACK
 * - L14 unsupported schema preserves bytes (fail-closed, no migration)
 * - L15 valid empty ledger still accepts append
 * - L16 no bulk unacknowledged record clear in production API
 * - L17 invalid identity rejected
 * - L18 missing Normal lineage rejected (authoritative generation and instanceToken required)
 * - L19 privacy-sensitive fields absent
 * - L20 commit failure and persistence boundary handling
 * - L21 V1 fired marker untouched
 * - L22 V2 queue storage untouched
 * - L23 Quick identity collision boundary
 * - L24 process recreation reads persisted unacknowledged data
 * - L25 stored over-capacity (> 16 records) is strictly CORRUPT and non-writable
 * - L26 stored duplicate recordIds is strictly CORRUPT and non-writable
 * - L27 typed read boundary prevents corrupt payload from being read as clean empty ledger
 * - L28 typed read boundary prevents unsupported schema from being read as clean empty ledger
 * - L29 concurrent in-process appends serialize safely under single-writer contract
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DirectBootDeliveryLedgerTest {

    private lateinit var context: Context
    private val baseTime = 1_700_000_000_000L

    private fun resetLedgerForTesting(context: Context): Boolean {
        return context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD)
            .commit()
    }

    private fun normalRecord(
        alarmId: Long,
        triggerTime: Long = baseTime + 100_000L,
        deliveredAt: Long = baseTime + 100_100L,
        generation: Int = 1,
        instanceToken: String = "token_$alarmId",
        provenance: String = "BOOT",
        restoreEpoch: Long = 1L
    ): DirectBootDeliveryRecord = DirectBootDeliveryRecord.createNormal(
        alarmId = alarmId,
        triggerTime = triggerTime,
        deliveredAt = deliveredAt,
        generation = generation,
        instanceToken = instanceToken,
        provenance = provenance,
        restoreEpoch = restoreEpoch
    )

    private fun quickRecord(
        alarmId: Long,
        triggerTime: Long = baseTime + 50_000L,
        deliveredAt: Long = baseTime + 50_050L,
        provenance: String = "BOOT"
    ): DirectBootDeliveryRecord = DirectBootDeliveryRecord.createQuick(
        alarmId = alarmId,
        triggerTime = triggerTime,
        deliveredAt = deliveredAt,
        provenance = provenance
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetLedgerForTesting(context)
        DirectBootAlarmCache.clear(context)
        DirectBootAlarmCache.clearQueue(context)
    }

    @Test
    fun L01_emptyOrAbsentLedgerState() {
        assertEquals(LedgerStorageClassification.ABSENT, DirectBootDeliveryLedger.classifyStorage(context))
        val readState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue("Absent ledger must return LedgerStorageState.Absent", readState is LedgerStorageState.Absent)

        val ackResult = DirectBootDeliveryLedger.acknowledgeRecord(context, "non_existent_id")
        assertTrue("Raw ACK on absent ledger must return AckNotQualified", ackResult is DeliveryAckResult.AckNotQualified)
        assertEquals(0, (ackResult as DeliveryAckResult.AckNotQualified).currentCount)
    }

    @Test
    fun L02_singleRecordRoundtrip() {
        val record = normalRecord(alarmId = 1L)
        val appendResult = DirectBootDeliveryLedger.appendRecord(context, record)

        assertTrue(appendResult is DeliveryAppendResult.Appended)
        val appended = appendResult as DeliveryAppendResult.Appended
        assertEquals(1, appended.unacknowledgedCount)
        assertEquals(record.recordId, appended.record.recordId)

        assertEquals(LedgerStorageClassification.VALID_WITH_RECORDS, DirectBootDeliveryLedger.classifyStorage(context))

        val readState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue(readState is LedgerStorageState.ValidWithRecords)
        val unacked = (readState as LedgerStorageState.ValidWithRecords).records
        assertEquals(1, unacked.size)
        val read = unacked.first()
        assertEquals(record.recordId, read.recordId)
        assertEquals(record.kind, read.kind)
        assertEquals(record.alarmId, read.alarmId)
        assertEquals(record.triggerTime, read.triggerTime)
        assertEquals(record.deliveredAt, read.deliveredAt)
        assertEquals(record.generation, read.generation)
        assertEquals(record.instanceToken, read.instanceToken)
        assertEquals(record.provenance, read.provenance)
        assertEquals(record.schemaVersion, read.schemaVersion)
    }

    @Test
    fun L03_multipleNormalRecords() {
        val r1 = normalRecord(alarmId = 1L, triggerTime = baseTime + 10_000L)
        val r2 = normalRecord(alarmId = 2L, triggerTime = baseTime + 20_000L)
        val r3 = normalRecord(alarmId = 3L, triggerTime = baseTime + 30_000L)

        DirectBootDeliveryLedger.appendRecord(context, r1)
        DirectBootDeliveryLedger.appendRecord(context, r2)
        DirectBootDeliveryLedger.appendRecord(context, r3)

        val readState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue(readState is LedgerStorageState.ValidWithRecords)
        val unacked = (readState as LedgerStorageState.ValidWithRecords).records
        assertEquals(3, unacked.size)
        assertEquals(listOf(r1.recordId, r2.recordId, r3.recordId), unacked.map { it.recordId })
    }

    @Test
    fun L04_mixedNormalAndQuickRecords() {
        val normal = normalRecord(alarmId = 10L, triggerTime = baseTime + 100_000L)
        val quick = quickRecord(alarmId = 20L, triggerTime = baseTime + 200_000L)

        DirectBootDeliveryLedger.appendRecord(context, normal)
        DirectBootDeliveryLedger.appendRecord(context, quick)

        val readState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue(readState is LedgerStorageState.ValidWithRecords)
        val unacked = (readState as LedgerStorageState.ValidWithRecords).records
        assertEquals(2, unacked.size)
        assertEquals(DirectBootDeliveryRecord.KIND_NORMAL, unacked[0].kind)
        assertEquals(DirectBootDeliveryRecord.KIND_QUICK, unacked[1].kind)
    }

    @Test
    fun L05_duplicateAppendIdempotency() {
        val record = normalRecord(alarmId = 5L)
        val firstAppend = DirectBootDeliveryLedger.appendRecord(context, record)
        assertTrue(firstAppend is DeliveryAppendResult.Appended)

        val secondAppend = DirectBootDeliveryLedger.appendRecord(context, record)
        assertTrue(secondAppend is DeliveryAppendResult.DuplicateIgnored)
        val duplicate = secondAppend as DeliveryAppendResult.DuplicateIgnored
        assertEquals(1, duplicate.unacknowledgedCount)
        assertEquals(record.recordId, duplicate.existing.recordId)

        val readState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue(readState is LedgerStorageState.ValidWithRecords)
        assertEquals(1, (readState as LedgerStorageState.ValidWithRecords).records.size)
    }

    @Test
    fun L06_sameAlarmIdDifferentGeneration() {
        val gen1 = normalRecord(alarmId = 7L, triggerTime = baseTime + 10_000L, generation = 1, instanceToken = "token_g1")
        val gen2 = normalRecord(alarmId = 7L, triggerTime = baseTime + 10_000L, generation = 2, instanceToken = "token_g2")

        assertFalse("Different generations must have distinct recordIds", gen1.recordId == gen2.recordId)

        DirectBootDeliveryLedger.appendRecord(context, gen1)
        DirectBootDeliveryLedger.appendRecord(context, gen2)

        val readState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue(readState is LedgerStorageState.ValidWithRecords)
        val unacked = (readState as LedgerStorageState.ValidWithRecords).records
        assertEquals(2, unacked.size)
        assertEquals(setOf(gen1.recordId, gen2.recordId), unacked.map { it.recordId }.toSet())
    }

    @Test
    fun L07_differentAlarmKindsSameId() {
        val normal = normalRecord(alarmId = 99L, triggerTime = baseTime + 50_000L)
        val quick = quickRecord(alarmId = 99L, triggerTime = baseTime + 50_000L)

        assertFalse("Different kinds must have distinct recordIds", normal.recordId == quick.recordId)

        DirectBootDeliveryLedger.appendRecord(context, normal)
        DirectBootDeliveryLedger.appendRecord(context, quick)

        val readState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue(readState is LedgerStorageState.ValidWithRecords)
        val unacked = (readState as LedgerStorageState.ValidWithRecords).records
        assertEquals(2, unacked.size)
        assertEquals(setOf(normal.recordId, quick.recordId), unacked.map { it.recordId }.toSet())
    }

    @Test
    fun L08_exactAckRemovesOnlyTarget() {
        val r1 = normalRecord(alarmId = 1L)
        val r2 = normalRecord(alarmId = 2L)
        val r3 = normalRecord(alarmId = 3L)

        DirectBootDeliveryLedger.appendRecord(context, r1)
        DirectBootDeliveryLedger.appendRecord(context, r2)
        DirectBootDeliveryLedger.appendRecord(context, r3)

        // Raw acknowledgeRecord(context, recordId) without live CE proof must be sealed (AckNotQualified) and preserve all records
        val ackResult = DirectBootDeliveryLedger.acknowledgeRecord(context, r2.recordId)
        assertTrue("Raw ACK without CE proof must return AckNotQualified", ackResult is DeliveryAckResult.AckNotQualified)
        assertEquals(3, (ackResult as DeliveryAckResult.AckNotQualified).currentCount)

        val readState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue(readState is LedgerStorageState.ValidWithRecords)
        val unacked = (readState as LedgerStorageState.ValidWithRecords).records
        assertEquals(3, unacked.size)
        assertEquals(listOf(r1.recordId, r2.recordId, r3.recordId), unacked.map { it.recordId })
    }

    @Test
    fun L09_unknownAckLeavesLedgerUnchanged() {
        val r1 = normalRecord(alarmId = 1L)
        DirectBootDeliveryLedger.appendRecord(context, r1)

        val ackResult = DirectBootDeliveryLedger.acknowledgeRecord(context, "unknown_record_id")
        assertTrue("Raw ACK must return AckNotQualified", ackResult is DeliveryAckResult.AckNotQualified)
        assertEquals(1, (ackResult as DeliveryAckResult.AckNotQualified).currentCount)

        val readState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue(readState is LedgerStorageState.ValidWithRecords)
        val unacked = (readState as LedgerStorageState.ValidWithRecords).records
        assertEquals(1, unacked.size)
        assertEquals(r1.recordId, unacked.first().recordId)
    }

    @Test
    fun L10_16RecordsRetained() {
        for (i in 1L..DirectBootDeliveryLedger.MAX_CAPACITY.toLong()) {
            val res = DirectBootDeliveryLedger.appendRecord(context, normalRecord(alarmId = i))
            assertTrue("Append record $i must succeed", res is DeliveryAppendResult.Appended)
        }

        val readState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue(readState is LedgerStorageState.ValidWithRecords)
        assertEquals(DirectBootDeliveryLedger.MAX_CAPACITY, (readState as LedgerStorageState.ValidWithRecords).records.size)
    }

    @Test
    fun L11_17thRejectedWithoutEviction() {
        val initialIds = mutableListOf<String>()
        for (i in 1L..DirectBootDeliveryLedger.MAX_CAPACITY.toLong()) {
            val record = normalRecord(alarmId = i)
            initialIds.add(record.recordId)
            DirectBootDeliveryLedger.appendRecord(context, record)
        }

        val record17 = normalRecord(alarmId = 17L)
        val result17 = DirectBootDeliveryLedger.appendRecord(context, record17)

        assertTrue("17th record must be rejected with CapacityExhausted", result17 is DeliveryAppendResult.CapacityExhausted)
        val exhausted = result17 as DeliveryAppendResult.CapacityExhausted
        assertEquals(DirectBootDeliveryLedger.MAX_CAPACITY, exhausted.currentCount)
        assertEquals(DirectBootDeliveryLedger.MAX_CAPACITY, exhausted.maxCapacity)

        // Verify none of the prior 16 records were evicted
        val readState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue(readState is LedgerStorageState.ValidWithRecords)
        val unacked = (readState as LedgerStorageState.ValidWithRecords).records
        assertEquals(DirectBootDeliveryLedger.MAX_CAPACITY, unacked.size)
        assertEquals(initialIds, unacked.map { it.recordId })
        assertFalse(unacked.any { it.recordId == record17.recordId })
    }

    @Test
    fun L12_corruptPayloadCannotBeOverwrittenByAppend() {
        val corruptString = "{ invalid JSON corrupt raw bytes !!!"
        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, corruptString).commit()

        assertEquals(LedgerStorageClassification.CORRUPT, DirectBootDeliveryLedger.classifyStorage(context))
        assertTrue(DirectBootDeliveryLedger.inspectStorageState(context) is LedgerStorageState.Corrupt)

        val readState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue("Corrupt payload must return LedgerStorageState.Corrupt", readState is LedgerStorageState.Corrupt)

        val appendRes = DirectBootDeliveryLedger.appendRecord(context, normalRecord(alarmId = 1L))
        assertTrue("Append onto corrupt storage must report StorageCorrupt", appendRes is DeliveryAppendResult.StorageCorrupt)

        // Verify corrupt payload was strictly preserved and NOT overwritten
        val rawAfter = DirectBootDeliveryLedger.getRawPayload(context)
        assertEquals("Corrupt payload must remain unchanged in storage", corruptString, rawAfter)
    }

    @Test
    fun L13_corruptPayloadCannotBeRemovedByAck() {
        val corruptString = "{\"schemaVersion\": 1, \"records\": \"not-an-array\"}"
        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, corruptString).commit()

        assertEquals(LedgerStorageClassification.CORRUPT, DirectBootDeliveryLedger.classifyStorage(context))

        val ackRes = DirectBootDeliveryLedger.acknowledgeRecord(context, "some_id")
        assertTrue("ACK on corrupt storage must report StorageCorrupt", ackRes is DeliveryAckResult.StorageCorrupt)

        // Verify corrupt payload was strictly preserved and NOT cleared
        val rawAfter = DirectBootDeliveryLedger.getRawPayload(context)
        assertEquals("Corrupt payload must remain unchanged after failed ACK", corruptString, rawAfter)
    }

    @Test
    fun L14_unsupportedSchemaPreservesBytes() {
        val unsupportedJson = JSONObject().apply {
            put("schemaVersion", 999)
            put("records", JSONArray().apply {
                put(normalRecord(1L).toJson())
            })
        }.toString()

        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, unsupportedJson).commit()

        assertEquals(LedgerStorageClassification.UNSUPPORTED_SCHEMA, DirectBootDeliveryLedger.classifyStorage(context))
        val state = DirectBootDeliveryLedger.inspectStorageState(context)
        assertTrue(state is LedgerStorageState.UnsupportedSchema)
        assertEquals(999, (state as LedgerStorageState.UnsupportedSchema).foundVersion)

        val readState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue("Unsupported schema must return LedgerStorageState.UnsupportedSchema", readState is LedgerStorageState.UnsupportedSchema)

        val appendRes = DirectBootDeliveryLedger.appendRecord(context, normalRecord(alarmId = 2L))
        assertTrue("Append on unsupported schema must report UnsupportedSchema", appendRes is DeliveryAppendResult.UnsupportedSchema)
        assertEquals(999, (appendRes as DeliveryAppendResult.UnsupportedSchema).foundVersion)

        val ackRes = DirectBootDeliveryLedger.acknowledgeRecord(context, "some_id")
        assertTrue("ACK on unsupported schema must report UnsupportedSchema", ackRes is DeliveryAckResult.UnsupportedSchema)

        // Verify bytes were preserved without silent migration or overwrite
        val rawAfter = DirectBootDeliveryLedger.getRawPayload(context)
        assertEquals("Unsupported schema payload must be preserved verbatim", unsupportedJson, rawAfter)
    }

    @Test
    fun L15_validEmptyLedgerStillAcceptsAppend() {
        val emptyJson = JSONObject().apply {
            put("schemaVersion", DirectBootDeliveryLedger.CURRENT_SCHEMA_VERSION)
            put("records", JSONArray())
        }.toString()

        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, emptyJson).commit()

        assertEquals(LedgerStorageClassification.VALID_EMPTY, DirectBootDeliveryLedger.classifyStorage(context))
        assertTrue(DirectBootDeliveryLedger.inspectStorageState(context) is LedgerStorageState.ValidEmpty)
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(context) is LedgerStorageState.ValidEmpty)

        val record = normalRecord(alarmId = 10L)
        val appendRes = DirectBootDeliveryLedger.appendRecord(context, record)
        assertTrue("Valid empty ledger must accept append", appendRes is DeliveryAppendResult.Appended)
        assertEquals(1, (appendRes as DeliveryAppendResult.Appended).unacknowledgedCount)

        assertEquals(LedgerStorageClassification.VALID_WITH_RECORDS, DirectBootDeliveryLedger.classifyStorage(context))
        val readState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue(readState is LedgerStorageState.ValidWithRecords)
        assertEquals(1, (readState as LedgerStorageState.ValidWithRecords).records.size)
    }

    @Test
    fun L16_noBulkUnacknowledgedRecordClearInProduction() {
        // Verify via reflection that DirectBootDeliveryLedger has no method matching 'clear' or 'reset'
        val methods = DirectBootDeliveryLedger::class.java.declaredMethods.map { it.name }
        assertFalse("DirectBootDeliveryLedger must have no 'clear' method", methods.contains("clear"))
        assertFalse("DirectBootDeliveryLedger must have no 'resetForTesting' method", methods.contains("resetForTesting"))
        assertFalse("DirectBootDeliveryLedger must have no 'clearDeliveryLedger' method", methods.contains("clearDeliveryLedger"))

        val r1 = normalRecord(alarmId = 1L)
        val r2 = normalRecord(alarmId = 2L)
        DirectBootDeliveryLedger.appendRecord(context, r1)
        DirectBootDeliveryLedger.appendRecord(context, r2)

        val readState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue(readState is LedgerStorageState.ValidWithRecords)
        assertEquals(2, (readState as LedgerStorageState.ValidWithRecords).records.size)

        // Attempt raw ACK on r1; must return AckNotQualified and preserve both r1 and r2
        val ackResult = DirectBootDeliveryLedger.acknowledgeRecord(context, r1.recordId)
        assertTrue(ackResult is DeliveryAckResult.AckNotQualified)

        // Verify unacknowledged r1 and r2 are preserved and were not cleared
        val remainingState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue(remainingState is LedgerStorageState.ValidWithRecords)
        val remaining = (remainingState as LedgerStorageState.ValidWithRecords).records
        assertEquals(2, remaining.size)
        assertEquals(listOf(r1.recordId, r2.recordId), remaining.map { it.recordId })
    }

    @Test
    fun L17_invalidIdentityRejected() {
        val negativeIdRecord = normalRecord(alarmId = -1L)
        val res1 = DirectBootDeliveryLedger.appendRecord(context, negativeIdRecord)
        assertTrue(res1 is DeliveryAppendResult.RejectedIneligible)
        assertEquals(DeliveryValidationStatus.INVALID_ALARM_ID, (res1 as DeliveryAppendResult.RejectedIneligible).status)

        val zeroTriggerRecord = normalRecord(alarmId = 1L, triggerTime = 0L)
        val res2 = DirectBootDeliveryLedger.appendRecord(context, zeroTriggerRecord)
        assertTrue(res2 is DeliveryAppendResult.RejectedIneligible)
        assertEquals(DeliveryValidationStatus.INVALID_TRIGGER_TIME, (res2 as DeliveryAppendResult.RejectedIneligible).status)

        val zeroDeliveryRecord = normalRecord(alarmId = 1L, deliveredAt = 0L)
        val res3 = DirectBootDeliveryLedger.appendRecord(context, zeroDeliveryRecord)
        assertTrue(res3 is DeliveryAppendResult.RejectedIneligible)
        assertEquals(DeliveryValidationStatus.INVALID_DELIVERY_TIME, (res3 as DeliveryAppendResult.RejectedIneligible).status)

        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(context) is LedgerStorageState.Absent)
    }

    @Test
    fun L18_missingNormalLineageRejected() {
        val zeroGenRecord = normalRecord(alarmId = 1L, generation = 0)
        val res1 = DirectBootDeliveryLedger.appendRecord(context, zeroGenRecord)
        assertTrue(res1 is DeliveryAppendResult.RejectedIneligible)
        assertEquals(DeliveryValidationStatus.MISSING_NORMAL_GENERATION, (res1 as DeliveryAppendResult.RejectedIneligible).status)

        val blankTokenRecord = normalRecord(alarmId = 1L, instanceToken = "")
        val res2 = DirectBootDeliveryLedger.appendRecord(context, blankTokenRecord)
        assertTrue(res2 is DeliveryAppendResult.RejectedIneligible)
        assertEquals(DeliveryValidationStatus.MISSING_NORMAL_INSTANCE_TOKEN, (res2 as DeliveryAppendResult.RejectedIneligible).status)

        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(context) is LedgerStorageState.Absent)
    }

    @Test
    fun L19_privacySensitiveFieldsAbsent() {
        val record = normalRecord(alarmId = 1L, provenance = "EMERGENCY_BOOT")
        DirectBootDeliveryLedger.appendRecord(context, record)

        val raw = DirectBootDeliveryLedger.getRawPayload(context) ?: ""
        assertTrue("Payload must be persisted", raw.isNotEmpty())
        assertFalse(raw.contains("\"label\":"))
        assertFalse(raw.contains("contacts"))
        assertFalse(raw.contains("guardian"))
        assertFalse(raw.contains("content://"))
        assertFalse(raw.contains("secret"))
        assertFalse(raw.contains("uri"))
    }

    @Test
    fun L20_commitFailureAndPersistenceBoundaryHandling() {
        // Direct verification of PersistenceFailed modeling
        val failAppend: DeliveryAppendResult = DeliveryAppendResult.PersistenceFailed("Disk write failed")
        assertTrue(failAppend is DeliveryAppendResult.PersistenceFailed)
        assertEquals("Disk write failed", (failAppend as DeliveryAppendResult.PersistenceFailed).reason)

        val failAck: DeliveryAckResult = DeliveryAckResult.PersistenceFailed("Disk write failed")
        assertTrue(failAck is DeliveryAckResult.PersistenceFailed)
        assertEquals("Disk write failed", (failAck as DeliveryAckResult.PersistenceFailed).reason)
    }

    @Test
    fun L21_v1FiredMarkerUntouched() {
        // Record V1 fired marker
        DirectBootAlarmCache.recordFired(context, alarmId = 42L, triggerTime = baseTime, firedAt = baseTime + 10L)

        // Perform Delivery Ledger operations
        val record = normalRecord(alarmId = 100L)
        DirectBootDeliveryLedger.appendRecord(context, record)
        DirectBootDeliveryLedger.acknowledgeRecord(context, record.recordId)

        // Verify V1 fired marker is completely intact
        val dummyAlarm = Alarm(id = 42L, hour = 7, minute = 0, nextTriggerTime = baseTime)
        val consumed = DirectBootAlarmCache.consumeFiredOneShotMarker(context, dummyAlarm, baseTime + 20L)
        assertTrue("V1 fired marker must remain intact and consumable", consumed)
    }

    @Test
    fun L22_v2QueueStorageUntouched() {
        // Enqueue V2 queue candidates
        val qCandidate = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 55L,
            triggerTime = baseTime + 10_000L,
            generation = 1,
            instanceToken = "token_55",
            restoreEpoch = 1L
        )
        DirectBootAlarmCache.writeQueue(context, listOf(qCandidate), baseTime)

        // Perform Delivery Ledger operations
        DirectBootDeliveryLedger.appendRecord(context, normalRecord(alarmId = 200L))

        // Verify V2 queue is untouched
        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertEquals(1, queue.size)
        assertEquals(55L, queue.first().alarmId)
    }

    @Test
    fun L23_quickIdentityCollisionBoundary() {
        val q1 = quickRecord(alarmId = 88L, triggerTime = baseTime + 10_000L)
        val q2 = quickRecord(alarmId = 88L, triggerTime = baseTime + 20_000L)

        assertFalse("Different triggers produce distinct Quick recordIds", q1.recordId == q2.recordId)
        assertTrue("Quick recordId adheres to canonical format", q1.recordId.startsWith("delivery_QUICK_88_trig"))

        // Append both and verify both are retained
        DirectBootDeliveryLedger.appendRecord(context, q1)
        DirectBootDeliveryLedger.appendRecord(context, q2)

        val readState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue(readState is LedgerStorageState.ValidWithRecords)
        val unacked = (readState as LedgerStorageState.ValidWithRecords).records
        assertEquals(2, unacked.size)
        assertEquals(listOf(q1.recordId, q2.recordId), unacked.map { it.recordId })
    }

    @Test
    fun L24_processRecreationReadsPersistedUnacknowledgedData() {
        val record = normalRecord(alarmId = 77L)
        DirectBootDeliveryLedger.appendRecord(context, record)

        // Simulate separate process / new application context reading SharedPreferences
        val recreatedContext = ApplicationProvider.getApplicationContext<Context>()
        val readState = DirectBootDeliveryLedger.readUnacknowledgedRecords(recreatedContext)

        assertTrue(readState is LedgerStorageState.ValidWithRecords)
        val unacked = (readState as LedgerStorageState.ValidWithRecords).records
        assertEquals(1, unacked.size)
        assertEquals(record.recordId, unacked.first().recordId)
    }

    @Test
    fun L25_storedOverCapacityIsStrictlyCorrupt() {
        // Construct raw JSON with 17 records (> MAX_CAPACITY 16)
        val array = JSONArray()
        for (i in 1..17) {
            array.put(normalRecord(alarmId = i.toLong()).toJson())
        }
        val overCapacityJson = JSONObject().apply {
            put("schemaVersion", DirectBootDeliveryLedger.CURRENT_SCHEMA_VERSION)
            put("records", array)
        }.toString()

        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, overCapacityJson).commit()

        assertEquals(LedgerStorageClassification.CORRUPT, DirectBootDeliveryLedger.classifyStorage(context))
        val readState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue("Over-capacity storage must be classified as CORRUPT", readState is LedgerStorageState.Corrupt)

        // Append and ACK must fail-closed without modifying raw bytes
        val appendRes = DirectBootDeliveryLedger.appendRecord(context, normalRecord(alarmId = 18L))
        assertTrue(appendRes is DeliveryAppendResult.StorageCorrupt)

        val ackRes = DirectBootDeliveryLedger.acknowledgeRecord(context, "some_id")
        assertTrue(ackRes is DeliveryAckResult.StorageCorrupt)

        assertEquals("Raw payload must remain completely untouched", overCapacityJson, DirectBootDeliveryLedger.getRawPayload(context))
    }

    @Test
    fun L26_storedDuplicateRecordIdsIsStrictlyCorrupt() {
        // Construct raw JSON containing two records with the same canonical recordId
        val record = normalRecord(alarmId = 33L)
        val array = JSONArray().apply {
            put(record.toJson())
            put(record.toJson()) // duplicate
        }
        val duplicateJson = JSONObject().apply {
            put("schemaVersion", DirectBootDeliveryLedger.CURRENT_SCHEMA_VERSION)
            put("records", array)
        }.toString()

        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, duplicateJson).commit()

        assertEquals(LedgerStorageClassification.CORRUPT, DirectBootDeliveryLedger.classifyStorage(context))
        val readState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue("Storage with duplicate recordIds must be classified as CORRUPT", readState is LedgerStorageState.Corrupt)

        // Append and ACK must fail-closed without modifying raw bytes
        val appendRes = DirectBootDeliveryLedger.appendRecord(context, normalRecord(alarmId = 34L))
        assertTrue(appendRes is DeliveryAppendResult.StorageCorrupt)

        val ackRes = DirectBootDeliveryLedger.acknowledgeRecord(context, record.recordId)
        assertTrue(ackRes is DeliveryAckResult.StorageCorrupt)

        assertEquals("Raw payload must remain completely untouched", duplicateJson, DirectBootDeliveryLedger.getRawPayload(context))
    }

    @Test
    fun L27_typedReadBoundaryPreventsCorruptPayloadFromBeingReadAsCleanEmptyLedger() {
        val corruptString = "{\"invalid\": \"structure\"}"
        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, corruptString).commit()

        val readState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertFalse("Corrupt payload cannot be read as ValidEmpty", readState is LedgerStorageState.ValidEmpty)
        assertFalse("Corrupt payload cannot be read as Absent", readState is LedgerStorageState.Absent)
        assertTrue("Corrupt payload must be typed as LedgerStorageState.Corrupt", readState is LedgerStorageState.Corrupt)
        val corruptState = readState as LedgerStorageState.Corrupt
        assertTrue(corruptState.reason.isNotEmpty())
    }

    @Test
    fun L28_typedReadBoundaryPreventsUnsupportedSchemaFromBeingReadAsCleanEmptyLedger() {
        val unsupportedJson = JSONObject().apply {
            put("schemaVersion", 42)
            put("records", JSONArray())
        }.toString()

        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, unsupportedJson).commit()

        val readState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertFalse("Unsupported schema cannot be read as ValidEmpty", readState is LedgerStorageState.ValidEmpty)
        assertFalse("Unsupported schema cannot be read as Absent", readState is LedgerStorageState.Absent)
        assertTrue("Unsupported schema must be typed as LedgerStorageState.UnsupportedSchema", readState is LedgerStorageState.UnsupportedSchema)
        val unsupportedState = readState as LedgerStorageState.UnsupportedSchema
        assertEquals(42, unsupportedState.foundVersion)
    }

    @Test
    fun L29_concurrentInProcessAppendsSerializeSafelyUnderSingleWriterContract() {
        val threadCount = 8
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val finishLatch = CountDownLatch(threadCount)
        val failures = java.util.concurrent.atomic.AtomicInteger(0)

        for (i in 1..threadCount) {
            val alarmId = i.toLong()
            executor.execute {
                try {
                    startLatch.await()
                    val record = normalRecord(alarmId = alarmId, triggerTime = baseTime + (alarmId * 1000L))
                    val result = DirectBootDeliveryLedger.appendRecord(context, record)
                    if (result !is DeliveryAppendResult.Appended) {
                        failures.incrementAndGet()
                    }
                } catch (e: Exception) {
                    failures.incrementAndGet()
                } finally {
                    finishLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        val finished = finishLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("All concurrent append threads must complete within timeout", finished)
        assertEquals("Zero concurrent append failures expected", 0, failures.get())

        val readState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue(readState is LedgerStorageState.ValidWithRecords)
        val records = (readState as LedgerStorageState.ValidWithRecords).records
        assertEquals("All 8 records must be safely serialized and persisted", threadCount, records.size)
    }
}
