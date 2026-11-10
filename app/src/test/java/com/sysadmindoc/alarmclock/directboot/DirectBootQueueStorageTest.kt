package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.model.Alarm
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deterministic unit tests for KNO-75 Phase P1A / P1A1 Inactive Versioned DE Queue Storage.
 *
 * Covers:
 * - Empty queue and single entry
 * - Direct writeQueue capacity ordering (sort before truncate)
 * - Six entries in reverse chronological order via writeQueue
 * - Earliest entry at position 6 (index 5) via writeQueue
 * - Equal trigger instant deterministic ordering (stable tie-breaker)
 * - Repeated serialization/readback idempotency
 * - NORMAL vs QUICK logical identity isolation
 * - Repeated insertion idempotency
 * - Same-ID new generation replacement vs stale rejection
 * - Removal and head selection
 * - V1 scalar read compatibility
 * - Corrupt V2 storage safety preserving legacy V1
 * - Unsupported V2 schema version safety
 * - Expired V1 cannot become active
 * - V1 fallback cannot invent generation or authority lineage
 * - Absence of privacy-sensitive fields in serialized DE payload
 * - Missing authority instanceToken / Quick trigger eligibility validation
 * - Unknown/invalid item schema version fails closed
 * - Legacy single-slot behavior preservation
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DirectBootQueueStorageTest {

    private lateinit var context: Context
    private val baseTime = 1_700_000_000_000L

    private fun normalCandidate(
        id: Long,
        triggerTime: Long,
        generation: Int = 1,
        instanceToken: String = "token_$id",
        timeLabel: String = "7:00 AM",
        schemaVersion: Int = DirectBootQueueItem.CURRENT_SCHEMA_VERSION
    ) = DirectBootQueueItem(
        kind = DirectBootQueueItem.KIND_NORMAL,
        alarmId = id,
        triggerTime = triggerTime,
        generation = generation,
        scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
        instanceToken = instanceToken,
        timeLabel = timeLabel,
        playDefaultSound = true,
        vibrationEnabled = true,
        updatedAt = baseTime,
        schemaVersion = schemaVersion
    )

    private fun quickCandidate(
        id: Long,
        triggerTime: Long,
        timeLabel: String = "Quick Alarm",
        schemaVersion: Int = DirectBootQueueItem.CURRENT_SCHEMA_VERSION
    ) = DirectBootQueueItem(
        kind = DirectBootQueueItem.KIND_QUICK,
        alarmId = id,
        triggerTime = triggerTime,
        generation = 0,
        scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
        instanceToken = "",
        timeLabel = timeLabel,
        playDefaultSound = true,
        vibrationEnabled = true,
        updatedAt = baseTime,
        schemaVersion = schemaVersion
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DirectBootAlarmCache.clear(context)
        DirectBootAlarmCache.clearQueue(context)
    }

    @Test
    fun emptyQueueReturnsEmptyListAndNullHead() {
        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertTrue(queue.isEmpty())
        assertNull(DirectBootAlarmCache.readQueueHead(context, baseTime))
    }

    @Test
    fun singleEntryEnqueueAndReadBack() {
        val candidate = normalCandidate(id = 1L, triggerTime = baseTime + 100_000L)
        val result = DirectBootAlarmCache.enqueueCandidate(context, candidate, baseTime)

        assertTrue(result is QueueEnqueueResult.Enqueued)
        val enqueued = result as QueueEnqueueResult.Enqueued
        assertEquals(1, enqueued.queueSize)
        assertEquals(candidate.alarmId, enqueued.head.alarmId)

        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertEquals(1, queue.size)
        assertEquals(candidate.alarmId, queue[0].alarmId)
        assertEquals(candidate.instanceToken, queue[0].instanceToken)
        assertEquals(DirectBootQueueItem.KIND_NORMAL, queue[0].kind)
        assertEquals(candidate.triggerTime, DirectBootAlarmCache.readQueueHead(context, baseTime)?.triggerTime)
    }

    @Test
    fun fiveEntriesCapacityMaintainedInStrictChronologicalOrder() {
        val c1 = normalCandidate(1L, baseTime + 500_000L)
        val c2 = normalCandidate(2L, baseTime + 200_000L)
        val c3 = normalCandidate(3L, baseTime + 400_000L)
        val c4 = normalCandidate(4L, baseTime + 100_000L)
        val c5 = normalCandidate(5L, baseTime + 300_000L)

        listOf(c1, c2, c3, c4, c5).forEach { candidate ->
            val res = DirectBootAlarmCache.enqueueCandidate(context, candidate, baseTime)
            assertTrue(res is QueueEnqueueResult.Enqueued)
        }

        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertEquals(5, queue.size)
        // Expected ascending trigger time order: 4, 2, 5, 3, 1
        assertEquals(4L, queue[0].alarmId)
        assertEquals(2L, queue[1].alarmId)
        assertEquals(5L, queue[2].alarmId)
        assertEquals(3L, queue[3].alarmId)
        assertEquals(1L, queue[4].alarmId)

        assertEquals(4L, DirectBootAlarmCache.readQueueHead(context, baseTime)?.alarmId)
    }

    @Test
    fun directWriteQueueSortsBeforeTruncatingNearestFive() {
        // Six entries passed in reverse chronological order
        val candidates = (6 downTo 1).map { i ->
            normalCandidate(i.toLong(), baseTime + (i * 100_000L))
        }
        assertEquals(6, candidates.size)
        assertEquals(6L, candidates.first().alarmId)

        val success = DirectBootAlarmCache.writeQueue(context, candidates)
        assertTrue(success)

        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertEquals(5, queue.size)
        // Nearest 5 entries (1..5) must be preserved in chronological order
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), queue.map { it.alarmId })
        // Candidate 6 (farthest) must have been truncated
        assertFalse(queue.any { it.alarmId == 6L })
    }

    @Test
    fun directWriteQueueWithEarliestEntryAtPositionSixIsRetained() {
        // Entries 1..5 have large offsets, entry 6 has the earliest offset
        val c1 = normalCandidate(1L, baseTime + 200_000L)
        val c2 = normalCandidate(2L, baseTime + 300_000L)
        val c3 = normalCandidate(3L, baseTime + 400_000L)
        val c4 = normalCandidate(4L, baseTime + 500_000L)
        val c5 = normalCandidate(5L, baseTime + 600_000L)
        val c6 = normalCandidate(6L, baseTime + 100_000L) // Earliest at index 5

        val list = listOf(c1, c2, c3, c4, c5, c6)
        DirectBootAlarmCache.writeQueue(context, list)

        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertEquals(5, queue.size)
        // Candidate 6 must become head (index 0)
        assertEquals(6L, queue[0].alarmId)
        // Candidate 5 (farthest: 600_000L) must be pruned
        assertFalse(queue.any { it.alarmId == 5L })
        assertEquals(listOf(6L, 1L, 2L, 3L, 4L), queue.map { it.alarmId })
    }

    @Test
    fun directWriteQueueEqualTimeTieBreakingIsDeterministic() {
        val equalTime = baseTime + 200_000L
        val q1 = quickCandidate(1L, equalTime)
        val n2 = normalCandidate(2L, equalTime)
        val n1 = normalCandidate(1L, equalTime)

        // Pass in arbitrary order: q1, n2, n1
        DirectBootAlarmCache.writeQueue(context, listOf(q1, n2, n1))

        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertEquals(3, queue.size)
        // Tie-breaker: NORMAL < QUICK; n1 < n2
        assertEquals(DirectBootQueueItem.KIND_NORMAL, queue[0].kind)
        assertEquals(1L, queue[0].alarmId)
        assertEquals(DirectBootQueueItem.KIND_NORMAL, queue[1].kind)
        assertEquals(2L, queue[1].alarmId)
        assertEquals(DirectBootQueueItem.KIND_QUICK, queue[2].kind)
        assertEquals(1L, queue[2].alarmId)
    }

    @Test
    fun repeatedSerializationAndReadbackIsIdempotent() {
        val candidates = (1..5).map { i ->
            normalCandidate(i.toLong(), baseTime + (i * 100_000L))
        }

        DirectBootAlarmCache.writeQueue(context, candidates)
        val read1 = DirectBootAlarmCache.readQueue(context, baseTime)

        DirectBootAlarmCache.writeQueue(context, read1)
        val read2 = DirectBootAlarmCache.readQueue(context, baseTime)

        assertEquals(read1, read2)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), read2.map { it.alarmId })
    }

    @Test
    fun sixthEntryRejectedWhenLaterThanAllFive() {
        for (i in 1..5) {
            val res = DirectBootAlarmCache.enqueueCandidate(
                context,
                normalCandidate(i.toLong(), baseTime + (i * 100_000L)),
                baseTime
            )
            assertTrue(res is QueueEnqueueResult.Enqueued)
        }

        val sixth = normalCandidate(6L, baseTime + 600_000L)
        val sixthResult = DirectBootAlarmCache.enqueueCandidate(context, sixth, baseTime)

        assertTrue(sixthResult is QueueEnqueueResult.DroppedByCapacity)
        assertEquals(5, (sixthResult as QueueEnqueueResult.DroppedByCapacity).queueSize)

        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertEquals(5, queue.size)
        assertFalse(queue.any { it.alarmId == 6L })
    }

    @Test
    fun earlierCandidateDisplacesFarthestEntryInFullQueue() {
        for (i in 2..6) {
            DirectBootAlarmCache.enqueueCandidate(
                context,
                normalCandidate(i.toLong(), baseTime + (i * 100_000L)),
                baseTime
            )
        }

        val earlier = normalCandidate(1L, baseTime + 150_000L)
        val result = DirectBootAlarmCache.enqueueCandidate(context, earlier, baseTime)

        assertTrue(result is QueueEnqueueResult.Enqueued)
        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertEquals(5, queue.size)

        assertEquals(1L, queue[0].alarmId)
        assertFalse(queue.any { it.alarmId == 6L })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), queue.map { it.alarmId })
    }

    @Test
    fun normalVsQuickIdentityIsolationAllowsSameIdToCoexist() {
        val normal = normalCandidate(id = 42L, triggerTime = baseTime + 100_000L)
        val quick = quickCandidate(id = 42L, triggerTime = baseTime + 200_000L)

        DirectBootAlarmCache.enqueueCandidate(context, normal, baseTime)
        DirectBootAlarmCache.enqueueCandidate(context, quick, baseTime)

        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertEquals(2, queue.size)

        val normalInQueue = queue.firstOrNull { it.kind == DirectBootQueueItem.KIND_NORMAL }
        val quickInQueue = queue.firstOrNull { it.kind == DirectBootQueueItem.KIND_QUICK }

        assertNotNull(normalInQueue)
        assertNotNull(quickInQueue)
        assertEquals(42L, normalInQueue?.alarmId)
        assertEquals(42L, quickInQueue?.alarmId)
    }

    @Test
    fun repeatedInsertionIsIdempotent() {
        val candidate = normalCandidate(id = 1L, triggerTime = baseTime + 100_000L)

        val r1 = DirectBootAlarmCache.enqueueCandidate(context, candidate, baseTime)
        assertTrue(r1 is QueueEnqueueResult.Enqueued)

        val r2 = DirectBootAlarmCache.enqueueCandidate(context, candidate, baseTime)
        assertTrue(r2 is QueueEnqueueResult.Enqueued)

        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertEquals(1, queue.size)
        assertEquals(1L, queue[0].alarmId)
    }

    @Test
    fun sameIdNewGenerationReplacesOlderGeneration() {
        val gen1 = normalCandidate(id = 1L, triggerTime = baseTime + 100_000L, generation = 1)
        DirectBootAlarmCache.enqueueCandidate(context, gen1, baseTime)

        val gen2 = normalCandidate(id = 1L, triggerTime = baseTime + 200_000L, generation = 2)
        val result = DirectBootAlarmCache.enqueueCandidate(context, gen2, baseTime)

        assertTrue(result is QueueEnqueueResult.Enqueued)
        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertEquals(1, queue.size)
        assertEquals(2, queue[0].generation)
        assertEquals(baseTime + 200_000L, queue[0].triggerTime)
    }

    @Test
    fun staleGenerationIsRejectedWhenQueueHoldsNewerGeneration() {
        val gen2 = normalCandidate(id = 1L, triggerTime = baseTime + 200_000L, generation = 2)
        DirectBootAlarmCache.enqueueCandidate(context, gen2, baseTime)

        val gen1 = normalCandidate(id = 1L, triggerTime = baseTime + 100_000L, generation = 1)
        val result = DirectBootAlarmCache.enqueueCandidate(context, gen1, baseTime)

        assertTrue(result is QueueEnqueueResult.RejectedStaleGeneration)
        assertEquals(2, (result as QueueEnqueueResult.RejectedStaleGeneration).currentGeneration)

        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertEquals(1, queue.size)
        assertEquals(2, queue[0].generation)
        assertEquals(baseTime + 200_000L, queue[0].triggerTime)
    }

    @Test
    fun removalByLogicalIdentityAndHeadSelection() {
        val c1 = normalCandidate(1L, baseTime + 100_000L)
        val c2 = normalCandidate(2L, baseTime + 200_000L)

        DirectBootAlarmCache.enqueueCandidate(context, c1, baseTime)
        DirectBootAlarmCache.enqueueCandidate(context, c2, baseTime)

        assertEquals(1L, DirectBootAlarmCache.readQueueHead(context, baseTime)?.alarmId)

        val removed = DirectBootAlarmCache.removeCandidate(context, DirectBootQueueItem.KIND_NORMAL, 1L)
        assertTrue(removed)

        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertEquals(1, queue.size)
        assertEquals(2L, queue[0].alarmId)
        assertEquals(2L, DirectBootAlarmCache.readQueueHead(context, baseTime)?.alarmId)

        assertFalse(DirectBootAlarmCache.removeCandidate(context, DirectBootQueueItem.KIND_NORMAL, 999L))
    }

    @Test
    fun v1ScalarReadCompatibilityWhenQueuePayloadIsAbsent() {
        DirectBootAlarmCache.clearQueue(context)

        val legacyAlarm = Alarm(id = 7L, hour = 8, minute = 30)
        DirectBootAlarmCache.saveIfEarlier(context, legacyAlarm, baseTime + 500_000L, baseTime)

        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertEquals(1, queue.size)
        val item = queue[0]
        assertEquals(7L, item.alarmId)
        assertEquals(baseTime + 500_000L, item.triggerTime)
        assertEquals(DirectBootQueueItem.KIND_NORMAL, item.kind)
        assertEquals("", item.instanceToken) // Must not fabricate authority lineage
        assertEquals(0, item.generation)      // Must not fabricate generation
        assertEquals(1, item.schemaVersion)   // Explicitly legacy V1 schema

        // V1 fallback item cannot be enqueued as normal V2 candidate
        assertFalse(item.isEligible(baseTime, allowLegacyV1 = false))

        val head = DirectBootAlarmCache.readQueueHead(context, baseTime)
        assertEquals(7L, head?.alarmId)
    }

    @Test
    fun corruptV2StorageDoesNotClearLegacyV1() {
        // Write legacy V1 scalar snapshot
        val legacyAlarm = Alarm(id = 99L, hour = 9, minute = 0)
        DirectBootAlarmCache.saveIfEarlier(context, legacyAlarm, baseTime + 100_000L, baseTime)

        // Inject malformed JSON into KEY_QUEUE_PAYLOAD
        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, "{corrupt_payload: true, items: [").commit()

        // readQueue catches JSONException and safely falls back to legacy V1 without clearing it
        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertEquals(1, queue.size)
        assertEquals(99L, queue[0].alarmId)

        // Legacy V1 snapshot remains intact in prefs
        val v1Snapshot = DirectBootAlarmCache.read(context)
        assertNotNull(v1Snapshot)
        assertEquals(99L, v1Snapshot?.alarmId)
    }

    @Test
    fun unsupportedV2SchemaVersionSafelyFallsBackToV1OrEmpty() {
        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        val futureJson = JSONObject().apply {
            put("schemaVersion", 999)
            put("items", org.json.JSONArray())
        }
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, futureJson.toString()).commit()

        // Without V1 present, returns empty list
        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertTrue(queue.isEmpty())

        // With V1 present, safely falls back to V1 without treating unsupported V2 as valid
        DirectBootAlarmCache.saveIfEarlier(context, Alarm(id = 12L), baseTime + 200_000L, baseTime)
        val queueWithV1 = DirectBootAlarmCache.readQueue(context, baseTime)
        assertEquals(1, queueWithV1.size)
        assertEquals(12L, queueWithV1[0].alarmId)
    }

    @Test
    fun expiredV1CannotBecomeActive() {
        DirectBootAlarmCache.clearQueue(context)
        val expiredAlarm = Alarm(id = 55L, hour = 5, minute = 0)
        // Persist expired V1 snapshot with trigger time in the past
        DirectBootAlarmCache.saveIfEarlier(context, expiredAlarm, baseTime - 1_000L, baseTime - 2_000L)

        // readQueue with current time baseTime must not activate expired V1
        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertTrue("Expired V1 cannot become active", queue.isEmpty())
        assertNull(DirectBootAlarmCache.readQueueHead(context, baseTime))
    }

    @Test
    fun unknownItemSchemaVersionFailsClosed() {
        val invalidItem = normalCandidate(1L, baseTime + 100_000L, schemaVersion = 999)
        assertEquals(
            QueueCandidateStatus.INELIGIBLE_UNSUPPORTED_SCHEMA_VERSION,
            invalidItem.evaluateEligibility(baseTime)
        )
        assertFalse(invalidItem.isEligible(baseTime))

        val result = DirectBootAlarmCache.enqueueCandidate(context, invalidItem, baseTime)
        assertTrue(result is QueueEnqueueResult.RejectedIneligible)
        assertEquals(
            QueueCandidateStatus.INELIGIBLE_UNSUPPORTED_SCHEMA_VERSION,
            (result as QueueEnqueueResult.RejectedIneligible).status
        )
    }

    @Test
    fun privacySensitiveFieldsAreAbsentFromSerializedDEStorage() {
        val candidate = normalCandidate(1L, baseTime + 100_000L, timeLabel = "7:00 AM")
        DirectBootAlarmCache.enqueueCandidate(context, candidate, baseTime)

        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        val rawJson = prefs.getString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, "") ?: ""

        assertTrue("Serialized payload must not be empty", rawJson.isNotEmpty())
        assertFalse(rawJson.contains("\"label\":"))
        assertFalse(rawJson.contains("contacts"))
        assertFalse(rawJson.contains("content://"))
        assertFalse(rawJson.contains("guardian"))
        assertFalse(rawJson.contains("secret"))
    }

    @Test
    fun missingInstanceTokenInNormalAlarmCandidateIsRejectedAsIneligible() {
        val missingTokenCandidate = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 1L,
            triggerTime = baseTime + 100_000L,
            generation = 1,
            instanceToken = "", // Blank token
            timeLabel = "7:00 AM"
        )

        val result = DirectBootAlarmCache.enqueueCandidate(context, missingTokenCandidate, baseTime)
        assertTrue(result is QueueEnqueueResult.RejectedIneligible)
        assertEquals(
            QueueCandidateStatus.INELIGIBLE_MISSING_INSTANCE_TOKEN,
            (result as QueueEnqueueResult.RejectedIneligible).status
        )

        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun quickAlarmCandidateWithValidEpochTriggerIsEligible() {
        val validQuick = quickCandidate(1L, baseTime + 100_000L)
        assertEquals(QueueCandidateStatus.ELIGIBLE, validQuick.evaluateEligibility(baseTime))
        assertTrue(validQuick.isEligible(baseTime))

        val res = DirectBootAlarmCache.enqueueCandidate(context, validQuick, baseTime)
        assertTrue(res is QueueEnqueueResult.Enqueued)

        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertEquals(1, queue.size)
        assertEquals(DirectBootQueueItem.KIND_QUICK, queue[0].kind)
        assertEquals(baseTime + 100_000L, queue[0].triggerTime)
    }

    @Test
    fun quickAlarmCandidateWithNonPositiveOrPastTriggerIsRejected() {
        val pastQuick = quickCandidate(1L, baseTime - 1_000L)
        assertEquals(
            QueueCandidateStatus.INELIGIBLE_INVALID_TRIGGER_TIME,
            pastQuick.evaluateEligibility(baseTime)
        )

        val zeroQuick = quickCandidate(2L, 0L)
        assertEquals(
            QueueCandidateStatus.INELIGIBLE_INVALID_TRIGGER_TIME,
            zeroQuick.evaluateEligibility(baseTime)
        )
    }

    @Test
    fun legacySingleSlotBehaviorIsUnchangedAndCoexists() {
        val qCandidate = normalCandidate(10L, baseTime + 200_000L)
        DirectBootAlarmCache.enqueueCandidate(context, qCandidate, baseTime)

        val legacyAlarm = Alarm(id = 20L, hour = 6, minute = 0)
        DirectBootAlarmCache.saveIfEarlier(context, legacyAlarm, baseTime + 100_000L, baseTime)

        val v1Snapshot = DirectBootAlarmCache.read(context)
        assertEquals(20L, v1Snapshot?.alarmId)
        assertEquals(baseTime + 100_000L, v1Snapshot?.triggerTime)

        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertEquals(1, queue.size)
        assertEquals(10L, queue[0].alarmId)

        DirectBootAlarmCache.clear(context)
        assertNull(DirectBootAlarmCache.read(context))
        assertEquals(1, DirectBootAlarmCache.readQueue(context, baseTime).size)

        DirectBootAlarmCache.clearQueue(context)
        assertTrue(DirectBootAlarmCache.readQueue(context, baseTime).isEmpty())
    }

    @Test
    fun directWriteWithInvalidGenerationIsRejected() {
        val invalidGenCandidate = normalCandidate(id = 1L, triggerTime = baseTime + 100_000L, generation = 0)
        val success = DirectBootAlarmCache.writeQueue(context, listOf(invalidGenCandidate), baseTime)
        assertFalse("writeQueue with generation < 1 for Normal alarm must fail", success)
        assertTrue(DirectBootAlarmCache.readQueue(context, baseTime).isEmpty())
    }

    @Test
    fun directWriteWithMissingNormalTokenIsRejected() {
        val missingTokenCandidate = normalCandidate(id = 1L, triggerTime = baseTime + 100_000L, instanceToken = "")
        val success = DirectBootAlarmCache.writeQueue(context, listOf(missingTokenCandidate), baseTime)
        assertFalse("writeQueue with blank instanceToken for Normal alarm must fail", success)
        assertTrue(DirectBootAlarmCache.readQueue(context, baseTime).isEmpty())
    }

    @Test
    fun directWriteWithUnknownKindIsRejected() {
        val unknownKindCandidate = normalCandidate(id = 1L, triggerTime = baseTime + 100_000L)
            .copy(kind = "CUSTOM_ALARM")
        val success = DirectBootAlarmCache.writeQueue(context, listOf(unknownKindCandidate), baseTime)
        assertFalse("writeQueue with unknown kind must fail", success)
        assertTrue(DirectBootAlarmCache.readQueue(context, baseTime).isEmpty())
    }

    @Test
    fun directWriteWithInvalidSchemaIsRejected() {
        val invalidSchemaCandidate = normalCandidate(id = 1L, triggerTime = baseTime + 100_000L, schemaVersion = 999)
        val success = DirectBootAlarmCache.writeQueue(context, listOf(invalidSchemaCandidate), baseTime)
        assertFalse("writeQueue with invalid schemaVersion must fail", success)
        assertTrue(DirectBootAlarmCache.readQueue(context, baseTime).isEmpty())
    }

    @Test
    fun directWriteWithDuplicateLogicalIdentityIsRejected() {
        val candidate1 = normalCandidate(id = 1L, triggerTime = baseTime + 100_000L, instanceToken = "token_1a")
        val candidate2 = normalCandidate(id = 1L, triggerTime = baseTime + 200_000L, instanceToken = "token_1b")
        val success = DirectBootAlarmCache.writeQueue(context, listOf(candidate1, candidate2), baseTime)
        assertFalse("writeQueue with duplicate logical identity (kind+alarmId) must fail", success)
        assertTrue(DirectBootAlarmCache.readQueue(context, baseTime).isEmpty())
    }

    @Test
    fun invalidEntryLeavesPriorValidQueueIntact() {
        val valid1 = normalCandidate(id = 1L, triggerTime = baseTime + 100_000L)
        val valid2 = normalCandidate(id = 2L, triggerTime = baseTime + 200_000L)
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(valid1, valid2), baseTime))

        val initialQueue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertEquals(2, initialQueue.size)

        // Attempt invalid write with missing instanceToken
        val invalid = normalCandidate(id = 3L, triggerTime = baseTime + 300_000L, instanceToken = "")
        val writeFailed = DirectBootAlarmCache.writeQueue(context, listOf(invalid), baseTime)
        assertFalse("Write with invalid candidate must be rejected", writeFailed)

        // Verify prior queue is still intact
        val preservedQueue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertEquals(2, preservedQueue.size)
        assertEquals(1L, preservedQueue[0].alarmId)
        assertEquals(2L, preservedQueue[1].alarmId)
    }

    @Test
    fun userAuthoredTimeLabelIsNotSerializedToDeviceProtectedStorage() {
        val secretUserLabel = "Confidential Doctor Appointment with Dr. Smith"
        val candidate = normalCandidate(id = 1L, triggerTime = baseTime + 100_000L, timeLabel = secretUserLabel)

        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(candidate), baseTime))

        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        val rawPayload = prefs.getString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, "") ?: ""

        assertTrue("Payload must be persisted", rawPayload.isNotEmpty())
        assertFalse("DE storage must not contain user-authored label text", rawPayload.contains("Confidential"))
        assertFalse("DE storage must not contain doctor name", rawPayload.contains("Dr. Smith"))
        assertTrue("DE storage must contain timeLabel key", rawPayload.contains("\"timeLabel\":"))

        // Also verify read back candidate does not expose user-authored string
        val readBack = DirectBootAlarmCache.readQueueHead(context, baseTime)
        assertNotNull(readBack)
        assertFalse(readBack!!.timeLabel.contains("Confidential"))
        assertFalse(readBack.timeLabel.contains("Dr. Smith"))
        assertTrue("timeLabel must be safe generic time", readBack.timeLabel.isNotEmpty())
    }

    @Test
    fun malformedPersistedV2ItemRejectedDuringRead() {
        val validItem = normalCandidate(id = 10L, triggerTime = baseTime + 100_000L).toJson()
        val malformedItem = JSONObject().apply {
            put("kind", "NORMAL")
            put("alarmId", 20L)
            put("triggerTime", baseTime + 200_000L)
            put("generation", 0) // Invalid generation for Normal
            put("instanceToken", "") // Missing instance token
            put("schemaVersion", 2)
        }
        val rawJson = JSONObject().apply {
            put("schemaVersion", 2)
            put("items", org.json.JSONArray().apply {
                put(validItem)
                put(malformedItem)
            })
        }.toString()

        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, rawJson).commit()

        // Hardened B1A: malformed item invalidates entire storage; no silent partial salvage
        assertEquals(QueueStorageClassification.CORRUPT, DirectBootAlarmCache.classifyQueueStorage(context))
        val state = DirectBootAlarmCache.readQueueStorage(context)
        assertTrue(state is QueueStorageState.Corrupt)

        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertTrue("Malformed item must cause entire payload to be classified corrupt; no silent partial salvage", queue.isEmpty())

        // With V1 present, safely falls back to V1 without treating corrupt V2 as valid
        DirectBootAlarmCache.saveIfEarlier(context, Alarm(id = 99L), baseTime + 50_000L, baseTime)
        val queueWithV1 = DirectBootAlarmCache.readQueue(context, baseTime)
        assertEquals(1, queueWithV1.size)
        assertEquals(99L, queueWithV1[0].alarmId)
    }

    @Test
    fun typedClassificationDistinguishesAllFiveStates() {
        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)

        // 1. ABSENT
        prefs.edit().clear().commit()
        assertEquals(QueueStorageClassification.ABSENT, DirectBootAlarmCache.classifyQueueStorage(context))
        assertTrue(DirectBootAlarmCache.readQueueStorage(context) is QueueStorageState.Absent)

        // 2. VALID_EMPTY
        val emptyJson = JSONObject().apply {
            put("schemaVersion", 2)
            put("items", org.json.JSONArray())
        }
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, emptyJson.toString()).commit()
        assertEquals(QueueStorageClassification.VALID_EMPTY, DirectBootAlarmCache.classifyQueueStorage(context))
        assertTrue(DirectBootAlarmCache.readQueueStorage(context) is QueueStorageState.ValidEmpty)

        // 3. VALID_WITH_ITEMS
        val item1 = normalCandidate(id = 1L, triggerTime = baseTime + 10_000L)
        val item2 = quickCandidate(id = 2L, triggerTime = baseTime + 20_000L)
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(item1, item2), baseTime))
        assertEquals(QueueStorageClassification.VALID_WITH_ITEMS, DirectBootAlarmCache.classifyQueueStorage(context))
        val validState = DirectBootAlarmCache.readQueueStorage(context)
        assertTrue(validState is QueueStorageState.ValidWithItems)
        assertEquals(2, (validState as QueueStorageState.ValidWithItems).items.size)

        // 4. CORRUPT
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, "{not valid json").commit()
        assertEquals(QueueStorageClassification.CORRUPT, DirectBootAlarmCache.classifyQueueStorage(context))
        assertTrue(DirectBootAlarmCache.readQueueStorage(context) is QueueStorageState.Corrupt)

        // 5. UNSUPPORTED_SCHEMA
        val futureJson = JSONObject().apply {
            put("schemaVersion", 999)
            put("items", org.json.JSONArray())
        }
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, futureJson.toString()).commit()
        assertEquals(QueueStorageClassification.UNSUPPORTED_SCHEMA, DirectBootAlarmCache.classifyQueueStorage(context))
        val schemaState = DirectBootAlarmCache.readQueueStorage(context)
        assertTrue(schemaState is QueueStorageState.UnsupportedSchema)
        assertEquals(999, (schemaState as QueueStorageState.UnsupportedSchema).foundVersion)
        assertEquals(2, schemaState.expectedVersion)
    }

    @Test
    fun corruptStorageFailsClosedOnEnqueueAndPreservesRawBytes() {
        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        val corruptRaw = "{invalid_json_payload: true"
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, corruptRaw).commit()

        val candidate = normalCandidate(id = 5L, triggerTime = baseTime + 50_000L)
        val result = DirectBootAlarmCache.enqueueCandidate(context, candidate, baseTime)

        assertTrue("enqueueCandidate must fail closed on corrupt storage", result is QueueEnqueueResult.StorageCorrupt)
        assertEquals("Raw payload must remain exactly untouched", corruptRaw, DirectBootAlarmCache.getRawQueuePayload(context))
    }

    @Test
    fun unsupportedSchemaFailsClosedOnEnqueueAndPreservesRawBytes() {
        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        val futureJson = JSONObject().apply {
            put("schemaVersion", 3)
            put("items", org.json.JSONArray())
        }.toString()
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, futureJson).commit()

        val candidate = normalCandidate(id = 5L, triggerTime = baseTime + 50_000L)
        val result = DirectBootAlarmCache.enqueueCandidate(context, candidate, baseTime)

        assertTrue("enqueueCandidate must fail closed on unsupported schema", result is QueueEnqueueResult.UnsupportedSchema)
        val schemaResult = result as QueueEnqueueResult.UnsupportedSchema
        assertEquals(3, schemaResult.foundVersion)
        assertEquals(2, schemaResult.expectedVersion)
        assertEquals("Raw payload must remain exactly untouched", futureJson, DirectBootAlarmCache.getRawQueuePayload(context))
    }

    @Test
    fun corruptStorageFailsClosedOnWriteQueueAndPreservesRawBytes() {
        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        val corruptRaw = "{corrupt_raw: 123"
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, corruptRaw).commit()

        val candidate = normalCandidate(id = 1L, triggerTime = baseTime + 10_000L)
        val writeSuccess = DirectBootAlarmCache.writeQueue(context, listOf(candidate), baseTime)

        assertFalse("writeQueue must reject when storage is corrupt", writeSuccess)
        assertEquals("Raw payload must remain untouched", corruptRaw, DirectBootAlarmCache.getRawQueuePayload(context))
    }

    @Test
    fun unsupportedSchemaFailsClosedOnWriteQueueAndPreservesRawBytes() {
        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        val futureRaw = JSONObject().apply {
            put("schemaVersion", 4)
            put("items", org.json.JSONArray())
        }.toString()
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, futureRaw).commit()

        val candidate = normalCandidate(id = 1L, triggerTime = baseTime + 10_000L)
        val writeSuccess = DirectBootAlarmCache.writeQueue(context, listOf(candidate), baseTime)

        assertFalse("writeQueue must reject when storage has unsupported schema", writeSuccess)
        assertEquals("Raw payload must remain untouched", futureRaw, DirectBootAlarmCache.getRawQueuePayload(context))
    }

    @Test
    fun corruptStorageFailsClosedOnRemoveCandidateAndPreservesRawBytes() {
        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        val corruptRaw = "{corrupt_raw_remove: true"
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, corruptRaw).commit()

        val removeSuccess = DirectBootAlarmCache.removeCandidate(context, DirectBootQueueItem.KIND_NORMAL, 1L)

        assertFalse("removeCandidate must reject when storage is corrupt", removeSuccess)
        assertEquals("Raw payload must remain untouched", corruptRaw, DirectBootAlarmCache.getRawQueuePayload(context))
    }

    @Test
    fun storedPayloadExceedingCapacityClassifiedAsCorruptWithoutSilentSalvage() {
        val itemsArray = org.json.JSONArray()
        for (i in 1L..6L) {
            itemsArray.put(normalCandidate(id = i, triggerTime = baseTime + i * 10_000L).toJson())
        }
        val rawJson = JSONObject().apply {
            put("schemaVersion", 2)
            put("items", itemsArray)
        }.toString()

        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, rawJson).commit()

        assertEquals(QueueStorageClassification.CORRUPT, DirectBootAlarmCache.classifyQueueStorage(context))
        val state = DirectBootAlarmCache.readQueueStorage(context)
        assertTrue(state is QueueStorageState.Corrupt)
        assertTrue((state as QueueStorageState.Corrupt).reason.contains("exceeds QUEUE_MAX_CAPACITY"))

        // readQueue must NOT silently truncate to 5; it must treat corrupt V2 as corrupt
        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertTrue("Exceeding capacity must fail closed as corrupt without silent salvage", queue.isEmpty())
    }

    @Test
    fun storedPayloadWithDuplicateLogicalIdsClassifiedAsCorrupt() {
        val item1 = normalCandidate(id = 10L, triggerTime = baseTime + 10_000L).toJson()
        val item2 = normalCandidate(id = 10L, triggerTime = baseTime + 20_000L, instanceToken = "token_different").toJson()
        val rawJson = JSONObject().apply {
            put("schemaVersion", 2)
            put("items", org.json.JSONArray().apply {
                put(item1)
                put(item2)
            })
        }.toString()

        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, rawJson).commit()

        assertEquals(QueueStorageClassification.CORRUPT, DirectBootAlarmCache.classifyQueueStorage(context))
        val state = DirectBootAlarmCache.readQueueStorage(context)
        assertTrue(state is QueueStorageState.Corrupt)
        assertTrue((state as QueueStorageState.Corrupt).reason.contains("Duplicate logicalId"))
    }

    @Test
    fun storedPayloadWithDuplicateNormalInstanceTokensClassifiedAsCorrupt() {
        val item1 = normalCandidate(id = 10L, triggerTime = baseTime + 10_000L, instanceToken = "same_token").toJson()
        val item2 = normalCandidate(id = 20L, triggerTime = baseTime + 20_000L, instanceToken = "same_token").toJson()
        val rawJson = JSONObject().apply {
            put("schemaVersion", 2)
            put("items", org.json.JSONArray().apply {
                put(item1)
                put(item2)
            })
        }.toString()

        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, rawJson).commit()

        assertEquals(QueueStorageClassification.CORRUPT, DirectBootAlarmCache.classifyQueueStorage(context))
        val state = DirectBootAlarmCache.readQueueStorage(context)
        assertTrue(state is QueueStorageState.Corrupt)
        assertTrue((state as QueueStorageState.Corrupt).reason.contains("Duplicate instanceToken"))
    }

    @Test
    fun expiredCandidatesAreDistinguishableFromStructuralCorruption() {
        val expiredItem = normalCandidate(id = 10L, triggerTime = baseTime - 50_000L)
        val rawJson = JSONObject().apply {
            put("schemaVersion", 2)
            put("items", org.json.JSONArray().apply {
                put(expiredItem.toJson())
            })
        }.toString()

        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, rawJson).commit()

        // Structurally valid: classified as VALID_WITH_ITEMS, NOT Corrupt
        assertEquals(QueueStorageClassification.VALID_WITH_ITEMS, DirectBootAlarmCache.classifyQueueStorage(context))
        val state = DirectBootAlarmCache.readQueueStorage(context)
        assertTrue(state is QueueStorageState.ValidWithItems)
        assertEquals(1, (state as QueueStorageState.ValidWithItems).items.size)

        // Filtered by current wall clock in readQueue(context, baseTime)
        val queueFiltered = DirectBootAlarmCache.readQueue(context, baseTime)
        assertTrue("Expired candidate is filtered by active readQueue", queueFiltered.isEmpty())

        // Read with now = 0L returns all items without expiration filter
        val queueUnfiltered = DirectBootAlarmCache.readQueue(context, 0L)
        assertEquals(1, queueUnfiltered.size)
    }

    @Test
    fun clearQueueAllowsFreshWriteAfterCorruption() {
        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, "{corrupt").commit()

        assertEquals(QueueStorageClassification.CORRUPT, DirectBootAlarmCache.classifyQueueStorage(context))
        assertFalse(DirectBootAlarmCache.writeQueue(context, listOf(normalCandidate(1L, baseTime + 10_000L)), baseTime))

        // clearQueue explicitly clears corrupt state
        assertTrue(DirectBootAlarmCache.clearQueue(context))
        assertEquals(QueueStorageClassification.ABSENT, DirectBootAlarmCache.classifyQueueStorage(context))

        // Fresh write now succeeds cleanly
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(normalCandidate(1L, baseTime + 10_000L)), baseTime))
        assertEquals(QueueStorageClassification.VALID_WITH_ITEMS, DirectBootAlarmCache.classifyQueueStorage(context))
    }

    @Test
    fun validNearestFiveOrderingPreserved() {
        val items = (1L..7L).map { id ->
            normalCandidate(id = id, triggerTime = baseTime + id * 10_000L)
        }.reversed() // Reverse order input

        assertTrue(DirectBootAlarmCache.writeQueue(context, items, baseTime))

        val queue = DirectBootAlarmCache.readQueue(context, baseTime)
        assertEquals(DirectBootAlarmCache.QUEUE_MAX_CAPACITY, queue.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), queue.map { it.alarmId })
    }

    @Test
    fun legacyV1BehaviorUnchanged() {
        val alarm = Alarm(
            id = 42L,
            hour = 7,
            minute = 30,
            label = "Work",
            ringtoneUri = "content://media/alarm1",
            vibrationEnabled = true,
            overrideSystemVolume = false,
            volume = 80,
            nextTriggerTime = baseTime + 50_000L
        )

        DirectBootAlarmCache.saveIfEarlier(context, alarm, baseTime + 50_000L, baseTime)
        val snapshot = DirectBootAlarmCache.read(context)
        assertNotNull(snapshot)
        assertEquals(42L, snapshot?.alarmId)
        assertEquals(baseTime + 50_000L, snapshot?.triggerTime)
        assertEquals("", snapshot?.label)
        assertTrue(snapshot?.isSchedulable(baseTime) == true)

        DirectBootAlarmCache.removeIfMatches(context, 42L)
        assertNull(DirectBootAlarmCache.read(context))
    }

    @Test
    fun updateNormalQueue_rejectsNonNormalCandidates() {
        val quick = quickCandidate(1L, baseTime + 10_000L)
        val result = DirectBootAlarmCache.updateNormalQueue(context, listOf(quick), baseTime)
        assertTrue(result is NormalQueueUpdateResult.RejectedIneligible)
    }

    @Test
    fun updateNormalQueue_rejectsIneligibleCandidates() {
        val invalid = normalCandidate(id = 1L, triggerTime = baseTime + 10_000L, instanceToken = "")
        val result = DirectBootAlarmCache.updateNormalQueue(context, listOf(invalid), baseTime)
        assertTrue(result is NormalQueueUpdateResult.RejectedIneligible)
    }

    @Test
    fun updateNormalQueue_rejectsDuplicateLogicalIds() {
        val c1 = normalCandidate(id = 1L, triggerTime = baseTime + 10_000L, instanceToken = "tok_1")
        val c2 = normalCandidate(id = 1L, triggerTime = baseTime + 20_000L, instanceToken = "tok_2")
        val result = DirectBootAlarmCache.updateNormalQueue(context, listOf(c1, c2), baseTime)
        assertTrue(result is NormalQueueUpdateResult.RejectedIneligible)
    }

    @Test
    fun updateNormalQueue_rejectsDuplicateInstanceTokens() {
        val c1 = normalCandidate(id = 1L, triggerTime = baseTime + 10_000L, instanceToken = "same_token")
        val c2 = normalCandidate(id = 2L, triggerTime = baseTime + 20_000L, instanceToken = "same_token")
        val result = DirectBootAlarmCache.updateNormalQueue(context, listOf(c1, c2), baseTime)
        assertTrue(result is NormalQueueUpdateResult.RejectedIneligible)
    }

    @Test
    fun updateNormalQueue_failsClosedOnCorruptStorage() {
        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        val corruptRaw = "{bad_json: 123"
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, corruptRaw).commit()

        val candidate = normalCandidate(id = 1L, triggerTime = baseTime + 10_000L)
        val result = DirectBootAlarmCache.updateNormalQueue(context, listOf(candidate), baseTime)
        assertTrue(result is NormalQueueUpdateResult.StorageCorrupt)
        assertEquals(corruptRaw, DirectBootAlarmCache.getRawQueuePayload(context))
    }

    @Test
    fun updateNormalQueue_failsClosedOnUnsupportedSchema() {
        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        val unsupportedRaw = "{\"schemaVersion\": 99, \"items\": []}"
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, unsupportedRaw).commit()

        val candidate = normalCandidate(id = 1L, triggerTime = baseTime + 10_000L)
        val result = DirectBootAlarmCache.updateNormalQueue(context, listOf(candidate), baseTime)
        assertTrue(result is NormalQueueUpdateResult.UnsupportedSchema)
        assertEquals(unsupportedRaw, DirectBootAlarmCache.getRawQueuePayload(context))
    }

    @Test
    fun updateNormalQueue_preservesQuickCandidatesAndTruncates() {
        val q1 = quickCandidate(10L, baseTime + 20_000L)
        val q2 = quickCandidate(20L, baseTime + 40_000L)
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(q1, q2), baseTime))

        val n1 = normalCandidate(1L, baseTime + 10_000L)
        val n2 = normalCandidate(2L, baseTime + 30_000L)
        val n3 = normalCandidate(3L, baseTime + 50_000L)
        val n4 = normalCandidate(4L, baseTime + 60_000L)

        // Merged 6 candidates: [n1(10k), q1(20k), n2(30k), q2(40k), n3(50k), n4(60k)]
        // Retained 5: n1, q1, n2, q2, n3 (n4 dropped)
        val result = DirectBootAlarmCache.updateNormalQueue(context, listOf(n1, n2, n3, n4), baseTime)
        assertTrue(result is NormalQueueUpdateResult.Success)
        val success = result as NormalQueueUpdateResult.Success

        assertEquals(5, success.completeQueue.size)
        assertEquals(listOf(1L, 10L, 2L, 20L, 3L), success.completeQueue.map { it.alarmId })
        assertEquals(listOf(1L, 2L, 3L), success.retainedNormalCandidates.map { it.alarmId })
        assertFalse(success.retainedNormalCandidates.any { it.alarmId == 4L })
    }
}
