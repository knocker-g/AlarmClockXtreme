package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.model.Alarm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DirectBootMissedTransitionEngineTest {

    private lateinit var context: Context
    private val baseTime = 1_700_000_000_000L
    private val validOverdueOffset = 350_000L // > DirectBootOverduePolicyEngine.MAX_RECOVERY_WINDOW_MS (300_000L)

    private val verifiedSameBootFacts = OverdueQualifiedFacts.of(
        rebootDetected = false,
        clockRollbackDetected = false,
        isCancelled = false
    )

    private val verifiedColdBootFacts = OverdueQualifiedFacts.of(
        rebootDetected = true,
        clockRollbackDetected = false,
        isCancelled = false
    )

    private fun getDePrefs(): SharedPreferences = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(DirectBootMissedStore.PREFS_NAME, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        getDePrefs().edit().clear().commit()
    }

    @After
    fun tearDown() {
        getDePrefs().edit().clear().commit()
    }

    private fun createNormalQueueItem(
        alarmId: Long,
        triggerTime: Long = baseTime + 10_000L,
        generation: Int = 1,
        instanceToken: String = "token_$alarmId",
        restoreEpoch: Long = 1L
    ): DirectBootQueueItem {
        return DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = generation,
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            instanceToken = instanceToken,
            timeLabel = "7:00 AM",
            playDefaultSound = true,
            vibrationEnabled = true,
            updatedAt = baseTime,
            restoreEpoch = restoreEpoch
        )
    }

    private fun normalIdentity(
        alarmId: Long,
        triggerTime: Long = baseTime + 10_000L,
        generation: Int = 1,
        instanceToken: String = "token_$alarmId",
        restoreEpoch: Long = 1L
    ): FiredOccurrenceIdentity {
        return FiredOccurrenceIdentity(
            kind = DirectBootDeliveryRecord.KIND_NORMAL,
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = generation,
            instanceToken = instanceToken,
            restoreEpoch = restoreEpoch
        )
    }

    private fun populateQueue(items: List<DirectBootQueueItem>) {
        val root = JSONObject().apply {
            put("schemaVersion", DirectBootQueueItem.CURRENT_SCHEMA_VERSION)
            val array = JSONArray()
            items.forEach { array.put(it.toJson()) }
            put("items", array)
        }
        getDePrefs().edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, root.toString()).commit()
    }

    private fun populateMissedStore(records: List<DirectBootMissedRecord>) {
        val jsonString = DirectBootMissedStore.serializeRecordsToJson(records)
        getDePrefs().edit().putString(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD, jsonString).commit()
    }

    // =========================================================================
    // T01: ValidHead atomic transition
    // =========================================================================
    @Test
    fun t01_validHead_atomicTransition() {
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = baseTime + 10_000L)
        val itemB = createNormalQueueItem(alarmId = 2L, triggerTime = baseTime + 20_000L)
        populateQueue(listOf(itemA, itemB))

        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = baseTime + 10_000L),
            missedAt = baseTime + 10_000L + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE
        )

        assertTrue("Expected Confirmed, got: $result", result is MissedTransitionResult.Confirmed)
        val confirmed = result as MissedTransitionResult.Confirmed
        assertEquals(1L, confirmed.consumedItem.alarmId)
        assertEquals("OVERDUE", confirmed.missedRecord.classification)
        assertEquals(1, confirmed.remainingQueue.size)
        assertEquals(2L, confirmed.remainingQueue[0].alarmId)
        assertEquals(2L, confirmed.nextCandidateToArm?.alarmId)

        // Verify storage
        val queueState = DirectBootAlarmCache.inspectQueueStorageState(context)
        assertTrue(queueState is QueueStorageState.ValidWithItems)
        val remainingInStorage = (queueState as QueueStorageState.ValidWithItems).items
        assertEquals(1, remainingInStorage.size)
        assertEquals(2L, remainingInStorage[0].alarmId)

        val missedState = DirectBootMissedStore.inspectStorageState(context)
        assertTrue(missedState is MissedStoreStorageState.ValidWithRecords)
        val records = (missedState as MissedStoreStorageState.ValidWithRecords).records
        assertEquals(1, records.size)
        assertEquals(confirmed.missedRecord.recordId, records[0].recordId)
    }

    // =========================================================================
    // T02: NonHead rejection
    // =========================================================================
    @Test
    fun t02_nonHead_rejection() {
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = baseTime + 10_000L)
        val itemB = createNormalQueueItem(alarmId = 2L, triggerTime = baseTime + 20_000L)
        populateQueue(listOf(itemA, itemB))

        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(2L, triggerTime = baseTime + 20_000L),
            missedAt = baseTime + 20_000L + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )

        assertTrue("Expected NotEligibleQueueHead, got: $result", result is MissedTransitionResult.Rejected.NotEligibleQueueHead)
        assertTrue((result as MissedTransitionResult.Rejected).isGuaranteedNoWrite)

        // Verify zero storage mutation
        val queueState = DirectBootAlarmCache.inspectQueueStorageState(context) as QueueStorageState.ValidWithItems
        assertEquals(2, queueState.items.size)
        val missedState = DirectBootMissedStore.inspectStorageState(context)
        assertTrue(missedState is MissedStoreStorageState.Absent)
    }

    // =========================================================================
    // T03: Queue absent
    // =========================================================================
    @Test
    fun t03_queueAbsent_rejection() {
        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L),
            missedAt = baseTime + 10_000L + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )

        assertTrue("Expected QueueAbsent, got: $result", result is MissedTransitionResult.Rejected.QueueAbsent)
        assertTrue((result as MissedTransitionResult.Rejected).isGuaranteedNoWrite)
    }

    // =========================================================================
    // T04: Queue empty
    // =========================================================================
    @Test
    fun t04_queueEmpty_rejection() {
        populateQueue(emptyList())

        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L),
            missedAt = baseTime + 10_000L + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )

        assertTrue("Expected OccurrenceNotFound, got: $result", result is MissedTransitionResult.Rejected.OccurrenceNotFound)
        assertTrue((result as MissedTransitionResult.Rejected).isGuaranteedNoWrite)
    }

    // =========================================================================
    // T05: Duplicate Missed identity
    // =========================================================================
    @Test
    fun t05_duplicateMissedIdentity_conflictRejection() {
        val itemA = createNormalQueueItem(alarmId = 1L)
        populateQueue(listOf(itemA))
        val missedA = DirectBootMissedRecord.createNormal(
            alarmId = 1L,
            triggerTime = itemA.triggerTime,
            missedAt = itemA.triggerTime + validOverdueOffset,
            generation = itemA.generation,
            instanceToken = itemA.instanceToken
        )
        populateMissedStore(listOf(missedA))

        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = itemA.triggerTime),
            missedAt = itemA.triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )

        assertTrue("Expected ConflictingState, got: $result", result is MissedTransitionResult.Rejected.ConflictingState)
        assertTrue((result as MissedTransitionResult.Rejected).isGuaranteedNoWrite)
    }

    // =========================================================================
    // T06: AlreadyApplied retry
    // =========================================================================
    @Test
    fun t06_alreadyApplied_retry() {
        val itemB = createNormalQueueItem(alarmId = 2L)
        populateQueue(listOf(itemB))
        val missedA = DirectBootMissedRecord.createNormal(
            alarmId = 1L,
            triggerTime = baseTime + 10_000L,
            missedAt = baseTime + 10_000L + validOverdueOffset,
            generation = 1,
            instanceToken = "token_1"
        )
        populateMissedStore(listOf(missedA))

        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = baseTime + 10_000L, instanceToken = "token_1"),
            missedAt = baseTime + 10_000L + validOverdueOffset + 5_000L,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )

        assertTrue("Expected AlreadyApplied, got: $result", result is MissedTransitionResult.AlreadyApplied)
        val applied = result as MissedTransitionResult.AlreadyApplied
        assertEquals(missedA.recordId, applied.existingRecord.recordId)
        assertEquals(1, applied.remainingQueue.size)
        assertEquals(2L, applied.nextCandidateToArm?.alarmId)
        assertTrue(applied.isGuaranteedNoWrite)
    }

    // =========================================================================
    // T07: Conflicting Queue/Missed state
    // =========================================================================
    @Test
    fun t07_conflictingQueueMissedState() {
        val itemA = createNormalQueueItem(alarmId = 1L)
        val itemB = createNormalQueueItem(alarmId = 2L)
        populateQueue(listOf(itemA, itemB))
        val missedA = DirectBootMissedRecord.createNormal(
            alarmId = 1L,
            triggerTime = itemA.triggerTime,
            missedAt = itemA.triggerTime + validOverdueOffset,
            generation = itemA.generation,
            instanceToken = itemA.instanceToken
        )
        populateMissedStore(listOf(missedA))

        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = itemA.triggerTime),
            missedAt = itemA.triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )

        assertTrue("Expected ConflictingState, got: $result", result is MissedTransitionResult.Rejected.ConflictingState)
        // Verify Queue was NOT mutated
        val queueState = DirectBootAlarmCache.inspectQueueStorageState(context) as QueueStorageState.ValidWithItems
        assertEquals(2, queueState.items.size)
    }

    // =========================================================================
    // T08: Capacity16 rejection
    // =========================================================================
    @Test
    fun t08_capacity16_rejection() {
        val itemA = createNormalQueueItem(alarmId = 999L)
        populateQueue(listOf(itemA))

        val existingRecords = (1..16).map { i ->
            DirectBootMissedRecord.createNormal(
                alarmId = i.toLong(),
                triggerTime = baseTime + (i * 1000L),
                missedAt = baseTime + (i * 1000L) + validOverdueOffset,
                generation = 1,
                instanceToken = "token_$i"
            )
        }
        populateMissedStore(existingRecords)

        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(999L, triggerTime = itemA.triggerTime, instanceToken = itemA.instanceToken),
            missedAt = itemA.triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )

        assertTrue("Expected CapacityExhausted, got: $result", result is MissedTransitionResult.Rejected.CapacityExhausted)
        val rejected = result as MissedTransitionResult.Rejected.CapacityExhausted
        assertEquals(16, rejected.currentCount)
        assertEquals(16, rejected.maxCapacity)

        // Invariant: Queue head MUST NOT be lost!
        val queueState = DirectBootAlarmCache.inspectQueueStorageState(context) as QueueStorageState.ValidWithItems
        assertEquals(1, queueState.items.size)
        assertEquals(999L, queueState.items[0].alarmId)
    }

    // =========================================================================
    // T09: Queue corruption
    // =========================================================================
    @Test
    fun t09_queueCorruption_rejection() {
        getDePrefs().edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, "{ malformed json").commit()

        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L),
            missedAt = baseTime + 10_000L + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )

        assertTrue("Expected QueueStorageCorrupt, got: $result", result is MissedTransitionResult.Rejected.QueueStorageCorrupt)
        assertTrue((result as MissedTransitionResult.Rejected).isGuaranteedNoWrite)
    }

    // =========================================================================
    // T10: Missed corruption
    // =========================================================================
    @Test
    fun t10_missedCorruption_rejection() {
        val itemA = createNormalQueueItem(alarmId = 1L)
        populateQueue(listOf(itemA))
        getDePrefs().edit().putString(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD, "{ malformed json").commit()

        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = itemA.triggerTime),
            missedAt = itemA.triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )

        assertTrue("Expected MissedStorageCorrupt, got: $result", result is MissedTransitionResult.Rejected.MissedStorageCorrupt)
        // Verify Queue intact
        val queueState = DirectBootAlarmCache.inspectQueueStorageState(context) as QueueStorageState.ValidWithItems
        assertEquals(1, queueState.items.size)
    }

    // =========================================================================
    // T11: Unsupported schema
    // =========================================================================
    @Test
    fun t11_unsupportedSchema_rejection() {
        val itemA = createNormalQueueItem(alarmId = 1L)
        populateQueue(listOf(itemA))
        val invalidSchemaMissed = JSONObject().apply {
            put("schemaVersion", 999)
            put("records", JSONArray())
        }
        getDePrefs().edit().putString(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD, invalidSchemaMissed.toString()).commit()

        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = itemA.triggerTime),
            missedAt = itemA.triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )

        assertTrue("Expected UnsupportedSchema, got: $result", result is MissedTransitionResult.Rejected.UnsupportedSchema)
        val unsupported = result as MissedTransitionResult.Rejected.UnsupportedSchema
        assertEquals("Missed", unsupported.domain)
        assertEquals(999, unsupported.found)
    }

    // =========================================================================
    // T12: Commit false
    // =========================================================================
    @Test
    fun t12_commitFalse_returnsUncertainCommitFailed() {
        val itemA = createNormalQueueItem(alarmId = 1L)
        populateQueue(listOf(itemA))

        val result = DirectBootMissedTransitionEngine.transitionMissedForTesting(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = itemA.triggerTime),
            missedAt = itemA.triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            editorCommitter = { _, _, _ -> false }
        )

        assertTrue("Expected CommitFailed, got: $result", result is MissedTransitionResult.Uncertain.CommitFailed)
        assertFalse(result.isGuaranteedNoWrite)
        assertTrue(result.deWriteAttempted)
    }

    // =========================================================================
    // T13: Commit exception
    // =========================================================================
    @Test
    fun t13_commitException_returnsUncertainCommitException() {
        val itemA = createNormalQueueItem(alarmId = 1L)
        populateQueue(listOf(itemA))

        val result = DirectBootMissedTransitionEngine.transitionMissedForTesting(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = itemA.triggerTime),
            missedAt = itemA.triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            editorCommitter = { _, _, _ -> throw IOException("I/O failure during commit") }
        )

        assertTrue("Expected CommitException, got: $result", result is MissedTransitionResult.Uncertain.CommitException)
        val exc = result as MissedTransitionResult.Uncertain.CommitException
        assertTrue(exc.cause is IOException)
    }

    // =========================================================================
    // T14: Cancellation before commit
    // =========================================================================
    @Test
    fun t14_cancellationBeforeCommit_rethrowsCancellation() {
        val itemA = createNormalQueueItem(alarmId = 1L)
        populateQueue(listOf(itemA))

        try {
            DirectBootMissedTransitionEngine.transitionMissedForTesting(
                context = context,
                occurrenceIdentity = normalIdentity(1L, triggerTime = itemA.triggerTime),
                missedAt = itemA.triggerTime + validOverdueOffset,
                facts = verifiedSameBootFacts,
                provenance = OverdueTriggerProvenance.NormalOsDispatch,
                preCommitHook = { throw CancellationException("Coroutines cancelled pre-commit") }
            )
            fail("Expected CancellationException to be rethrown")
        } catch (e: CancellationException) {
            assertEquals("Coroutines cancelled pre-commit", e.message)
        }

        // Verify zero storage mutation
        val queueState = DirectBootAlarmCache.inspectQueueStorageState(context) as QueueStorageState.ValidWithItems
        assertEquals(1, queueState.items.size)
    }

    // =========================================================================
    // T15: Cancellation during commit
    // =========================================================================
    @Test
    fun t15_cancellationDuringCommit_returnsUncertain() {
        val itemA = createNormalQueueItem(alarmId = 1L)
        populateQueue(listOf(itemA))

        val result = DirectBootMissedTransitionEngine.transitionMissedForTesting(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = itemA.triggerTime),
            missedAt = itemA.triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            editorCommitter = { _, _, _ -> throw CancellationException("Cancelled during commit") }
        )

        assertTrue("Expected CommitException, got: $result", result is MissedTransitionResult.Uncertain.CommitException)
        val exc = result as MissedTransitionResult.Uncertain.CommitException
        assertTrue(exc.cause is CancellationException)
    }

    // =========================================================================
    // T16: Cancellation after commit
    // =========================================================================
    @Test
    fun t16_cancellationAfterCommit_returnsCancellationAfterCommit() {
        val itemA = createNormalQueueItem(alarmId = 1L)
        populateQueue(listOf(itemA))

        val result = DirectBootMissedTransitionEngine.transitionMissedForTesting(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = itemA.triggerTime),
            missedAt = itemA.triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            postCommitHook = { throw CancellationException("Cancelled post-commit") }
        )

        assertTrue("Expected CancellationAfterCommit, got: $result", result is MissedTransitionResult.CancellationAfterCommit)
        val cancellationResult = result as MissedTransitionResult.CancellationAfterCommit
        assertEquals(1L, cancellationResult.consumedItem.alarmId)
        assertEquals("Cancelled post-commit", cancellationResult.cancellation.message)

        // Verify storage was mutated prior to post-commit cancellation
        val queueState = DirectBootAlarmCache.inspectQueueStorageState(context)
        assertTrue(queueState is QueueStorageState.ValidEmpty)
        val missedState = DirectBootMissedStore.inspectStorageState(context)
        assertTrue(missedState is MissedStoreStorageState.ValidWithRecords)
    }

    // =========================================================================
    // T17: Process death equivalent retry
    // =========================================================================
    @Test
    fun t17_processDeathEquivalentRetry() {
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = baseTime + 10_000L)
        populateQueue(listOf(itemA))

        val first = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = baseTime + 10_000L),
            missedAt = baseTime + 10_000L + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )
        assertTrue(first is MissedTransitionResult.Confirmed)

        // Retry same identity (simulating process restart and re-evaluation)
        val retry = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = baseTime + 10_000L),
            missedAt = baseTime + 10_000L + validOverdueOffset + 5_000L,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )

        assertTrue("Expected AlreadyApplied on retry, got: $retry", retry is MissedTransitionResult.AlreadyApplied)
        val applied = retry as MissedTransitionResult.AlreadyApplied
        assertEquals((first as MissedTransitionResult.Confirmed).missedRecord.recordId, applied.existingRecord.recordId)

        // Verify storage unchanged on retry
        val missedState = DirectBootMissedStore.inspectStorageState(context) as MissedStoreStorageState.ValidWithRecords
        assertEquals(1, missedState.records.size)
    }

    // =========================================================================
    // T18: Classification strict validation
    // =========================================================================
    @Test
    fun t18_classificationStrictValidation() {
        val itemA = createNormalQueueItem(alarmId = 1L)
        populateQueue(listOf(itemA))

        // Invalid classification
        val invalidResult = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = itemA.triggerTime),
            missedAt = itemA.triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = "OVERDUE_WINDOW_EXCEEDED"
        )
        assertTrue(invalidResult is MissedTransitionResult.Rejected.InvalidClassification)
        assertEquals("OVERDUE_WINDOW_EXCEEDED", (invalidResult as MissedTransitionResult.Rejected.InvalidClassification).classification)

        // Valid COLD_BOOT classification with verified reboot facts & ColdBoot provenance
        val coldBootResult = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = itemA.triggerTime),
            missedAt = itemA.triggerTime + 15_000L,
            facts = verifiedColdBootFacts,
            provenance = OverdueTriggerProvenance.ColdBoot,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_COLD_BOOT
        )
        assertTrue("Expected Confirmed for COLD_BOOT, got: $coldBootResult", coldBootResult is MissedTransitionResult.Confirmed)
        assertEquals("COLD_BOOT", (coldBootResult as MissedTransitionResult.Confirmed).missedRecord.classification)
    }

    // =========================================================================
    // T19: Shared lock concurrency
    // =========================================================================
    @Test
    fun t19_sharedLockConcurrency() = runBlocking {
        val items = (1..5).map { createNormalQueueItem(it.toLong(), triggerTime = baseTime + (it * 10_000L)) }
        populateQueue(items)

        // Concurrent retries of the same eligible head (1L) under DirectBootStorageLock.lock:
        // Exactly one must Confirm; the remaining 4 must return AlreadyApplied without duplicate writes.
        val jobs = (1..5).map {
            async(Dispatchers.Default) {
                DirectBootMissedTransitionEngine.transitionMissed(
                    context = context,
                    occurrenceIdentity = normalIdentity(1L, triggerTime = baseTime + 10_000L),
                    missedAt = baseTime + 10_000L + validOverdueOffset,
                    facts = verifiedSameBootFacts,
                    provenance = OverdueTriggerProvenance.NormalOsDispatch
                )
            }
        }
        val results = jobs.awaitAll()

        val confirmedCount = results.count { it is MissedTransitionResult.Confirmed }
        assertEquals(1, confirmedCount)
        val alreadyAppliedCount = results.count { it is MissedTransitionResult.AlreadyApplied }
        assertEquals(4, alreadyAppliedCount)
    }

    // =========================================================================
    // T20: Other preference keys unchanged
    // =========================================================================
    @Test
    fun t20_otherPreferenceKeysUnchanged() {
        val itemA = createNormalQueueItem(alarmId = 1L)
        populateQueue(listOf(itemA))

        // Set unrelated keys
        getDePrefs().edit()
            .putString("unrelated_custom_key", "custom_value_123")
            .commit()

        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = itemA.triggerTime),
            missedAt = itemA.triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )

        assertTrue(result is MissedTransitionResult.Confirmed)
        // Verify unrelated keys are still present and unaltered
        assertEquals("custom_value_123", getDePrefs().getString("unrelated_custom_key", null))
    }

    // =========================================================================
    // E01: Future head cannot transition to Missed
    // =========================================================================
    @Test
    fun e01_futureHead_cannotTransitionToMissed() {
        val triggerTime = baseTime + 50_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        // missedAt is 20_000ms BEFORE triggerTime (future alarm)
        val prematureMissedAt = triggerTime - 20_000L

        val overdueAttempt = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = prematureMissedAt,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE
        )
        assertTrue(
            "Future head must be rejected with PrematureTrigger for OVERDUE, but got: $overdueAttempt",
            overdueAttempt is MissedTransitionResult.Rejected.PrematureTrigger
        )
        val prematureOverdue = overdueAttempt as MissedTransitionResult.Rejected.PrematureTrigger
        assertEquals(prematureMissedAt, prematureOverdue.missedAt)
        assertEquals(triggerTime, prematureOverdue.triggerTime)
        assertTrue(prematureOverdue.isGuaranteedNoWrite)

        val coldBootAttempt = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = prematureMissedAt,
            facts = verifiedColdBootFacts,
            provenance = OverdueTriggerProvenance.ColdBoot,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_COLD_BOOT
        )
        assertTrue(
            "Future head must be rejected with PrematureTrigger for COLD_BOOT, but got: $coldBootAttempt",
            coldBootAttempt is MissedTransitionResult.Rejected.PrematureTrigger
        )

        // Verify Queue head preserved intact and Missed Store untouched
        val queueState = DirectBootAlarmCache.inspectQueueStorageState(context) as QueueStorageState.ValidWithItems
        assertEquals(1, queueState.items.size)
        assertEquals(1L, queueState.items[0].alarmId)
        assertTrue(DirectBootMissedStore.inspectStorageState(context) is MissedStoreStorageState.Absent)
    }

    // =========================================================================
    // E02: Insufficient lateness rejected
    // =========================================================================
    @Test
    fun e02_insufficientLateness_rejected() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        // Case 1: lateness = 0ms (at exact trigger time) -> within 300,000ms window
        val exactAttempt = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE
        )
        assertTrue(
            "0ms lateness must be rejected with InsufficientLateness for OVERDUE, got: $exactAttempt",
            exactAttempt is MissedTransitionResult.Rejected.InsufficientLateness
        )
        assertEquals(0L, (exactAttempt as MissedTransitionResult.Rejected.InsufficientLateness).latenessMs)
        assertEquals(DirectBootOverduePolicyEngine.MAX_RECOVERY_WINDOW_MS, exactAttempt.requiredCeilingMs)

        // Case 2: lateness = 300,000ms (exact boundary MAX_RECOVERY_WINDOW_MS) -> still eligible for delivery, NOT Missed!
        val boundaryAttempt = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + DirectBootOverduePolicyEngine.MAX_RECOVERY_WINDOW_MS,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE
        )
        assertTrue(
            "300,000ms lateness must be rejected with InsufficientLateness for OVERDUE, got: $boundaryAttempt",
            boundaryAttempt is MissedTransitionResult.Rejected.InsufficientLateness
        )
        assertEquals(300_000L, (boundaryAttempt as MissedTransitionResult.Rejected.InsufficientLateness).latenessMs)

        // Queue head must remain intact
        val queueState = DirectBootAlarmCache.inspectQueueStorageState(context) as QueueStorageState.ValidWithItems
        assertEquals(1, queueState.items.size)
    }

    // =========================================================================
    // E03: Valid overdue head accepted
    // =========================================================================
    @Test
    fun e03_validOverdueHead_accepted() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        // lateness = 300,001ms (strictly > MAX_RECOVERY_WINDOW_MS)
        val validOverdueInstant = triggerTime + DirectBootOverduePolicyEngine.MAX_RECOVERY_WINDOW_MS + 1L
        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = validOverdueInstant,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE
        )

        assertTrue("Expected Confirmed at 300,001ms lateness, got: $result", result is MissedTransitionResult.Confirmed)
        val confirmed = result as MissedTransitionResult.Confirmed
        assertEquals(validOverdueInstant, confirmed.missedRecord.missedAt)
        assertEquals(DirectBootMissedRecord.CLASSIFICATION_OVERDUE, confirmed.missedRecord.classification)
    }

    // =========================================================================
    // E04: Classification/policy consistency
    // =========================================================================
    @Test
    fun e04_classificationPolicyConsistency() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        // For 5,000ms lateness on same boot: OVERDUE is rejected (<= 300,000ms), whereas COLD_BOOT with verified reboot is accepted (>= 0ms)
        val missedAt = triggerTime + 5_000L

        val overdueResult = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = missedAt,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE
        )
        assertTrue(
            "OVERDUE at 5,000ms lateness must be rejected with InsufficientLateness, got: $overdueResult",
            overdueResult is MissedTransitionResult.Rejected.InsufficientLateness
        )

        // Queue still has itemA; now transition as COLD_BOOT at 0ms lateness with verified reboot facts
        val coldBootAtZero = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime,
            facts = verifiedColdBootFacts,
            provenance = OverdueTriggerProvenance.ColdBoot,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_COLD_BOOT
        )
        assertTrue(
            "COLD_BOOT at 0ms lateness with verified reboot facts must be Confirmed, got: $coldBootAtZero",
            coldBootAtZero is MissedTransitionResult.Confirmed
        )
    }

    // =========================================================================
    // E05: Caller-supplied lateness inconsistency
    // =========================================================================
    @Test
    fun e05_callerSuppliedLatenessInconsistency_rejected() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        // Case 1: Actual missedAt is only +5,000ms (insufficient), but caller spoofs latenessMs = 400,000ms
        val spoofedHighLateness = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + 5_000L,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE,
            latenessMs = 400_000L
        )
        assertTrue(
            "Spoofed high latenessMs must be rejected with LatenessInconsistent, got: $spoofedHighLateness",
            spoofedHighLateness is MissedTransitionResult.Rejected.LatenessInconsistent
        )
        val incon1 = spoofedHighLateness as MissedTransitionResult.Rejected.LatenessInconsistent
        assertEquals(400_000L, incon1.suppliedLatenessMs)
        assertEquals(5_000L, incon1.calculatedLatenessMs)

        // Case 2: Actual missedAt is +350,000ms (valid), but caller supplies mismatched latenessMs = 360,000ms
        val mismatchedLateness = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + 350_000L,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE,
            latenessMs = 360_000L
        )
        assertTrue(
            "Mismatched caller-supplied latenessMs must be rejected with LatenessInconsistent, got: $mismatchedLateness",
            mismatchedLateness is MissedTransitionResult.Rejected.LatenessInconsistent
        )

        // Case 3: Negative caller-supplied latenessMs
        val negativeLateness = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + 350_000L,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE,
            latenessMs = -100L
        )
        assertTrue(
            "Negative caller-supplied latenessMs must be rejected with LatenessInconsistent, got: $negativeLateness",
            negativeLateness is MissedTransitionResult.Rejected.LatenessInconsistent
        )

        val queueState = DirectBootAlarmCache.inspectQueueStorageState(context) as QueueStorageState.ValidWithItems
        assertEquals(1, queueState.items.size)
    }

    // =========================================================================
    // E06: Missing eligibility provenance fail-closed
    // =========================================================================
    @Test
    fun e06_missingEligibilityProvenance_failClosed() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        // Blank, unknown, or non-missed policy classifications must fail closed
        for (invalidClassification in listOf("", "   ", "UNKNOWN", "NORMAL_DISPATCH", "RECOVERY_DELIVERY", "PREMATURE_BLOCK")) {
            val result = DirectBootMissedTransitionEngine.transitionMissed(
                context = context,
                occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
                missedAt = triggerTime + validOverdueOffset,
                facts = verifiedSameBootFacts,
                provenance = OverdueTriggerProvenance.NormalOsDispatch,
                expectedClassification = invalidClassification
            )
            assertTrue(
                "Classification '$invalidClassification' must fail closed, got: $result",
                result is MissedTransitionResult.Rejected.InvalidClassification
            )
        }

        // Queue preserved
        val queueState = DirectBootAlarmCache.inspectQueueStorageState(context) as QueueStorageState.ValidWithItems
        assertEquals(1, queueState.items.size)
    }

    // =========================================================================
    // E07: Clock arithmetic boundary
    // =========================================================================
    @Test
    fun e07_clockArithmeticBoundary_failClosed() {
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = 10_000L)
        populateQueue(listOf(itemA))

        // Non-positive missedAt
        val zeroMissedAt = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = 10_000L),
            missedAt = 0L,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE
        )
        assertTrue(zeroMissedAt is MissedTransitionResult.Rejected.InvalidOccurrenceIdentity)

        val negativeMissedAt = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = 10_000L),
            missedAt = -5_000L,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE
        )
        assertTrue(negativeMissedAt is MissedTransitionResult.Rejected.InvalidOccurrenceIdentity)

        // Null latenessMs (representing overflow in caller arithmetic)
        val overflowNullLateness = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = 10_000L),
            missedAt = 10_000L + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE,
            latenessMs = null
        )
        assertTrue(
            "Null latenessMs must fail closed with ClockArithmeticOverflow, got: $overflowNullLateness",
            overflowNullLateness is MissedTransitionResult.Rejected.ClockArithmeticOverflow
        )

        // Long.MAX_VALUE boundary where missedAt = Long.MAX_VALUE and triggerTime = 10_000L with mismatched lateness
        val maxBoundaryMismatch = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = 10_000L),
            missedAt = Long.MAX_VALUE,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE,
            latenessMs = 500_000L
        )
        assertTrue(
            "Long.MAX_VALUE with mismatched latenessMs must be rejected with LatenessInconsistent, got: $maxBoundaryMismatch",
            maxBoundaryMismatch is MissedTransitionResult.Rejected.LatenessInconsistent
        )

        val queueState = DirectBootAlarmCache.inspectQueueStorageState(context) as QueueStorageState.ValidWithItems
        assertEquals(1, queueState.items.size)
    }

    // =========================================================================
    // E08: Rejection preserves Queue and Missed bytes
    // =========================================================================
    @Test
    fun e08_rejectionPreservesQueueAndMissedBytes() {
        val triggerTime = baseTime + 50_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        val itemB = createNormalQueueItem(alarmId = 2L, triggerTime = triggerTime + 60_000L)
        populateQueue(listOf(itemA, itemB))

        val existingMissed = DirectBootMissedRecord.createNormal(
            alarmId = 99L,
            triggerTime = baseTime - 500_000L,
            missedAt = baseTime - 100_000L,
            generation = 1,
            instanceToken = "token_99"
        )
        populateMissedStore(listOf(existingMissed))

        val queueBytesBefore = getDePrefs().getString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, null)
        val missedBytesBefore = getDePrefs().getString(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD, null)

        // Attempt 1: Premature (future head)
        val res1 = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime - 1_000L,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE
        )
        assertTrue(res1 is MissedTransitionResult.Rejected.PrematureTrigger)

        // Attempt 2: Insufficient lateness (within 300s window)
        val res2 = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + 120_000L,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE
        )
        assertTrue(res2 is MissedTransitionResult.Rejected.InsufficientLateness)

        // Attempt 3: Inconsistent caller lateness
        val res3 = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + 400_000L,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE,
            latenessMs = 300_001L
        )
        assertTrue(res3 is MissedTransitionResult.Rejected.LatenessInconsistent)

        // Exact string/byte equality before and after rejections
        assertEquals(queueBytesBefore, getDePrefs().getString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, null))
        assertEquals(missedBytesBefore, getDePrefs().getString(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD, null))
    }

    // =========================================================================
    // E09: Existing valid transition remains compatible
    // =========================================================================
    @Test
    fun e09_existingValidTransition_remainsCompatible() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        val intentIdentity = DirectBootV2NormalIntentIdentity(
            alarmId = 1L,
            triggerTime = triggerTime,
            generation = 1,
            instanceToken = "token_1"
        )

        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            identity = intentIdentity,
            missedAt = triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE
        )

        assertTrue("Expected Confirmed via DirectBootV2NormalIntentIdentity overload, got: $result", result is MissedTransitionResult.Confirmed)
        val confirmed = result as MissedTransitionResult.Confirmed
        assertEquals(1L, confirmed.consumedItem.alarmId)
        assertTrue(confirmed.remainingQueue.isEmpty())
        assertEquals(null, confirmed.nextCandidateToArm)
    }

    // =========================================================================
    // E10: Retry remains idempotent
    // =========================================================================
    @Test
    fun e10_retryRemainsIdempotent_underEligibilityRules() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        val first = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE
        )
        assertTrue(first is MissedTransitionResult.Confirmed)

        val missedBytesAfterFirst = getDePrefs().getString(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD, null)

        // Subsequent retry with updated valid timestamp returns AlreadyApplied without mutating stored record
        val retry = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + validOverdueOffset + 60_000L,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE
        )
        assertTrue("Expected AlreadyApplied on retry, got: $retry", retry is MissedTransitionResult.AlreadyApplied)
        val applied = retry as MissedTransitionResult.AlreadyApplied
        assertEquals(triggerTime + validOverdueOffset, applied.existingRecord.missedAt)
        assertEquals(missedBytesAfterFirst, getDePrefs().getString(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD, null))
    }

    // =========================================================================
    // P3-C-C3 Targeted Policy Authority & Post-Commit Semantics Tests (P01-P20)
    // =========================================================================

    // P01: ColdBoot without verified reboot rejected
    @Test
    fun p01_coldBootWithoutVerifiedReboot_rejected() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        // rebootDetected = false, but ColdBoot provenance / classification claimed
        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + 5_000L,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.ColdBoot,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_COLD_BOOT
        )

        assertTrue(
            "P01: ColdBoot without verified reboot must be rejected by Policy, got: $result",
            result is MissedTransitionResult.Rejected.PolicyRejected
        )
        val rejected = result as MissedTransitionResult.Rejected.PolicyRejected
        assertTrue(rejected.policyOutcome is OverduePolicyOutcome.UnknownProvenanceBlock)
    }

    // P02: ColdBoot with valid reboot facts accepted
    @Test
    fun p02_coldBootWithValidRebootFacts_accepted() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + 5_000L,
            facts = verifiedColdBootFacts,
            provenance = OverdueTriggerProvenance.ColdBoot,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_COLD_BOOT
        )

        assertTrue("P02: ColdBoot with valid reboot facts must be Confirmed, got: $result", result is MissedTransitionResult.Confirmed)
        assertEquals(DirectBootMissedRecord.CLASSIFICATION_COLD_BOOT, (result as MissedTransitionResult.Confirmed).missedRecord.classification)
    }

    // P03: ColdBoot with wrong provenance rejected
    @Test
    fun p03_coldBootWithWrongProvenance_rejected() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        // rebootDetected = true, but provenance is NormalOsDispatch while claiming COLD_BOOT
        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + 5_000L,
            facts = verifiedColdBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_COLD_BOOT
        )

        assertTrue(
            "P03: ColdBoot with wrong provenance (NormalOsDispatch across reboot) must be rejected, got: $result",
            result is MissedTransitionResult.Rejected.PolicyRejected
        )
        val rejected = result as MissedTransitionResult.Rejected.PolicyRejected
        assertTrue(rejected.policyOutcome is OverduePolicyOutcome.UnknownProvenanceBlock)
    }

    // P04: Unknown reboot facts rejected
    @Test
    fun p04_unknownRebootFacts_rejected() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        val uncertainRebootFacts = OverdueQualifiedFacts(
            rebootStatus = QualifiedFact.Uncertain("Boot count unavailable"),
            clockRollbackStatus = QualifiedFact.Verified(false),
            cancellationStatus = QualifiedFact.Verified(false)
        )

        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + validOverdueOffset,
            facts = uncertainRebootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )

        assertTrue(
            "P04: Unknown reboot facts must fail closed, got: $result",
            result is MissedTransitionResult.Rejected.PolicyRejected
        )
        val rejected = result as MissedTransitionResult.Rejected.PolicyRejected
        assertTrue(rejected.policyOutcome is OverduePolicyOutcome.UnknownProvenanceBlock)
    }

    // P05: Unknown cancellation facts rejected
    @Test
    fun p05_unknownCancellationFacts_rejected() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        val uncertainCancelFacts = OverdueQualifiedFacts(
            rebootStatus = QualifiedFact.Verified(false),
            clockRollbackStatus = QualifiedFact.Verified(false),
            cancellationStatus = QualifiedFact.Uncertain("Cancellation state unverified")
        )

        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + validOverdueOffset,
            facts = uncertainCancelFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )

        assertTrue(
            "P05: Unknown cancellation facts must fail closed, got: $result",
            result is MissedTransitionResult.Rejected.PolicyRejected
        )
        val rejected = result as MissedTransitionResult.Rejected.PolicyRejected
        assertTrue(rejected.policyOutcome is OverduePolicyOutcome.StorageUncertainBlock)
    }

    // P06: Cancelled occurrence rejected
    @Test
    fun p06_cancelledOccurrence_rejected() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        val cancelledFacts = OverdueQualifiedFacts.of(
            rebootDetected = false,
            clockRollbackDetected = false,
            isCancelled = true
        )

        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + validOverdueOffset,
            facts = cancelledFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )

        assertTrue(
            "P06: Cancelled occurrence must be rejected, got: $result",
            result is MissedTransitionResult.Rejected.PolicyRejected
        )
        val rejected = result as MissedTransitionResult.Rejected.PolicyRejected
        assertTrue(rejected.policyOutcome is OverduePolicyOutcome.SupersededReject)
    }

    // P07: Clock rollback rejected
    @Test
    fun p07_clockRollback_rejected() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        val rollbackFacts = OverdueQualifiedFacts.of(
            rebootDetected = false,
            clockRollbackDetected = true,
            isCancelled = false
        )

        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + validOverdueOffset,
            facts = rollbackFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )

        assertTrue(
            "P07: Clock rollback must be rejected, got: $result",
            result is MissedTransitionResult.Rejected.PolicyRejected
        )
        val rejected = result as MissedTransitionResult.Rejected.PolicyRejected
        assertTrue(rejected.policyOutcome is OverduePolicyOutcome.UnknownClockBlock)
    }

    // P08: Ledger delivered occurrence rejected
    @Test
    fun p08_ledgerDeliveredOccurrence_rejected() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        // Record same occurrence in Delivery Ledger
        val deliveredRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 1L,
            triggerTime = triggerTime,
            deliveredAt = triggerTime + 1_000L,
            generation = 1,
            instanceToken = "token_1"
        )
        val appendResult = DirectBootDeliveryLedger.appendRecord(context, deliveredRecord)
        assertTrue(appendResult is DeliveryAppendResult.Appended)

        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )

        assertTrue(
            "P08: Occurrence already recorded in Delivery Ledger must NOT be recorded in Missed Store, got: $result",
            result is MissedTransitionResult.Rejected.AlreadyDeliveredInLedger
        )
        assertEquals(deliveredRecord.recordId, (result as MissedTransitionResult.Rejected.AlreadyDeliveredInLedger).deliveryRecordId)
        assertTrue(DirectBootMissedStore.inspectStorageState(context) is MissedStoreStorageState.Absent)
    }

    // P09: Unknown/corrupt ledger state rejected
    @Test
    fun p09_unknownLedgerState_rejected() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        // Case 1: Corrupt Delivery Ledger payload
        getDePrefs().edit()
            .putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, "{ corrupt_ledger_json")
            .commit()

        val corruptResult = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )
        assertTrue(
            "P09: Corrupt Delivery Ledger state must fail closed, got: $corruptResult",
            corruptResult is MissedTransitionResult.Rejected.LedgerStorageCorrupt
        )

        // Case 2: Unsupported schema version in Delivery Ledger
        val unsupportedLedger = JSONObject().apply {
            put("schemaVersion", 999)
            put("records", JSONArray())
        }
        getDePrefs().edit()
            .putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, unsupportedLedger.toString())
            .commit()

        val unsupportedResult = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )
        assertTrue(
            "P09: Unsupported Delivery Ledger schema must fail closed, got: $unsupportedResult",
            unsupportedResult is MissedTransitionResult.Rejected.UnsupportedSchema
        )
        assertEquals("Ledger", (unsupportedResult as MissedTransitionResult.Rejected.UnsupportedSchema).domain)
    }

    // P10: OVERDUE without valid provenance rejected
    @Test
    fun p10_overdueWithoutValidProvenance_rejected() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        for (invalidProvenance in listOf(
            OverdueTriggerProvenance.Unknown,
            OverdueTriggerProvenance.TimeMutation,
            OverdueTriggerProvenance.CredentialUnlock,
            OverdueTriggerProvenance.UnanchoredRecovery
        )) {
            val result = DirectBootMissedTransitionEngine.transitionMissed(
                context = context,
                occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
                missedAt = triggerTime + validOverdueOffset,
                facts = verifiedSameBootFacts,
                provenance = invalidProvenance
            )

            assertTrue(
                "P10: OVERDUE with provenance $invalidProvenance must be rejected, got: $result",
                result is MissedTransitionResult.Rejected.PolicyRejected
            )
        }
    }

    // P11: Valid OVERDUE remains accepted
    @Test
    fun p11_validOverdue_remainsAccepted() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        val result = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )

        assertTrue("P11: Valid OVERDUE must be Confirmed, got: $result", result is MissedTransitionResult.Confirmed)
        assertEquals(DirectBootMissedRecord.CLASSIFICATION_OVERDUE, (result as MissedTransitionResult.Confirmed).missedRecord.classification)
    }

    // P12: Forged classification cannot bypass Policy
    @Test
    fun p12_forgedClassification_cannotBypassPolicy() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        // Case 1: Legacy raw-classification call without Policy facts/provenance is sealed fail-closed
        @Suppress("DEPRECATION")
        val rawForgedResult = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + 5_000L,
            classification = DirectBootMissedRecord.CLASSIFICATION_COLD_BOOT
        )
        assertTrue(
            "P12: Forged raw COLD_BOOT classification without Policy evaluation must be rejected, got: $rawForgedResult",
            rawForgedResult is MissedTransitionResult.Rejected.MissingPolicyQualification
        )

        // Case 2: Passing expectedClassification = COLD_BOOT on same boot at +5,000ms (where Policy returns NormalDispatchCandidate)
        val sameBootForgedColdBoot = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + 5_000L,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_COLD_BOOT
        )
        assertTrue(
            "P12: Claiming COLD_BOOT when Policy evaluates NormalDispatchCandidate must be rejected, got: $sameBootForgedColdBoot",
            sameBootForgedColdBoot is MissedTransitionResult.Rejected.PolicyRejected
        )

        // Case 3: Passing expectedClassification = COLD_BOOT when Policy evaluates OverdueMissedCandidate ("OVERDUE")
        val mismatchedExpected = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_COLD_BOOT
        )
        assertTrue(
            "P12: Claiming COLD_BOOT when Policy evaluates OVERDUE must be rejected, got: $mismatchedExpected",
            mismatchedExpected is MissedTransitionResult.Rejected.PolicyClassificationMismatch
        )
    }

    // P13: Stale/replayed Policy result cannot authorize
    @Test
    fun p13_staleReplayedPolicyResult_cannotAuthorize() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        val candidate = DirectBootV2NormalIntentIdentity(
            alarmId = 1L,
            triggerTime = triggerTime,
            generation = 1,
            instanceToken = "token_1"
        )

        // Step 1: Evaluate Policy at t1 when state is clean -> produces OverdueMissedCandidate
        val t1Outcome = DirectBootOverduePolicyEngine.evaluate(
            candidate = candidate,
            queueState = DirectBootAlarmCache.inspectQueueStorageState(context),
            ledgerState = DirectBootDeliveryLedger.inspectStorageState(context),
            missedState = DirectBootMissedStore.toPolicyState(context),
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = triggerTime + validOverdueOffset
        )
        assertTrue(t1Outcome is OverduePolicyOutcome.OverdueMissedCandidate)

        // Step 2: Passing the pre-evaluated / caller-constructed OverduePolicyOutcome directly is rejected
        val directOutcomeResult = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + validOverdueOffset,
            claimedPolicyOutcome = t1Outcome
        )
        assertTrue(
            "P13: Caller-supplied OverduePolicyOutcome must be rejected, got: $directOutcomeResult",
            directOutcomeResult is MissedTransitionResult.Rejected.UnverifiedPolicyOutcome
        )

        // Step 3: Before transitionMissed runs, occurrence is delivered into Delivery Ledger!
        DirectBootDeliveryLedger.appendRecord(
            context,
            DirectBootDeliveryRecord.createNormal(
                alarmId = 1L,
                triggerTime = triggerTime,
                deliveredAt = triggerTime + 2_000L,
                generation = 1,
                instanceToken = "token_1"
            )
        )

        // Step 4: Attempting transition after state changed must re-evaluate under lock and reject
        val resultAfterLedgerUpdate = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )
        assertTrue(
            "P13: Stale Policy evaluation before Ledger delivery must not authorize transition, got: $resultAfterLedgerUpdate",
            resultAfterLedgerUpdate is MissedTransitionResult.Rejected.AlreadyDeliveredInLedger
        )
    }

    // P14: Duplicate retry remains idempotent
    @Test
    fun p14_duplicateRetry_remainsIdempotent() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        val first = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )
        assertTrue(first is MissedTransitionResult.Confirmed)

        val retry = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + validOverdueOffset + 10_000L,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )
        assertTrue("P14: Retry must return AlreadyApplied, got: $retry", retry is MissedTransitionResult.AlreadyApplied)
    }

    // P15: Queue and Missed unchanged on rejection
    @Test
    fun p15_queueAndMissedUnchanged_onRejection() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        val itemB = createNormalQueueItem(alarmId = 2L, triggerTime = triggerTime + 20_000L)
        populateQueue(listOf(itemA, itemB))

        val existingMissed = DirectBootMissedRecord.createNormal(
            alarmId = 50L,
            triggerTime = baseTime - 400_000L,
            missedAt = baseTime - 50_000L,
            generation = 1,
            instanceToken = "token_50"
        )
        populateMissedStore(listOf(existingMissed))

        val queueBefore = getDePrefs().getString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, null)
        val missedBefore = getDePrefs().getString(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD, null)

        // Rejected attempt 1: ColdBoot without reboot
        val r1 = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + 5_000L,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.ColdBoot,
            expectedClassification = DirectBootMissedRecord.CLASSIFICATION_COLD_BOOT
        )
        assertTrue("P15: Expected rejection on ColdBoot without reboot, got: $r1", r1 is MissedTransitionResult.Rejected)

        // Rejected attempt 2: Cancelled occurrence
        val r2 = DirectBootMissedTransitionEngine.transitionMissed(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + validOverdueOffset,
            facts = OverdueQualifiedFacts.of(rebootDetected = false, clockRollbackDetected = false, isCancelled = true),
            provenance = OverdueTriggerProvenance.NormalOsDispatch
        )
        assertTrue("P15: Expected rejection on cancelled occurrence, got: $r2", r2 is MissedTransitionResult.Rejected)

        assertEquals(queueBefore, getDePrefs().getString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, null))
        assertEquals(missedBefore, getDePrefs().getString(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD, null))
    }

    // P16: Commit false remains Uncertain
    @Test
    fun p16_commitFalse_remainsUncertain() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        val result = DirectBootMissedTransitionEngine.transitionMissedForTesting(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            editorCommitter = { _, _, _ -> false }
        )

        assertTrue("P16: Commit false must remain Uncertain.CommitFailed, got: $result", result is MissedTransitionResult.Uncertain.CommitFailed)
    }

    // P17: Commit exception remains Uncertain
    @Test
    fun p17_commitException_remainsUncertain() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        val result = DirectBootMissedTransitionEngine.transitionMissedForTesting(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            editorCommitter = { _, _, _ -> throw IOException("Disk write failure") }
        )

        assertTrue("P17: Commit exception must remain Uncertain.CommitException, got: $result", result is MissedTransitionResult.Uncertain.CommitException)
    }

    // P18: Post-commit exception preserves confirmed data (NOT Uncertain.CommitException)
    @Test
    fun p18_postCommitException_preservesConfirmedData() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        val itemB = createNormalQueueItem(alarmId = 2L, triggerTime = triggerTime + 20_000L)
        populateQueue(listOf(itemA, itemB))

        val result = DirectBootMissedTransitionEngine.transitionMissedForTesting(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            postCommitHook = { throw IllegalStateException("Post-commit non-cancellation error") }
        )

        assertFalse(
            "P18: Post-commit exception after confirmed commit must NOT be classified as Uncertain, got: $result",
            result is MissedTransitionResult.Uncertain
        )
        assertTrue(
            "P18: Expected PostCommitFailure, got: $result",
            result is MissedTransitionResult.PostCommitFailure
        )
        val postFailure = result as MissedTransitionResult.PostCommitFailure
        assertEquals(1L, postFailure.consumedItem.alarmId)
        assertEquals(DirectBootMissedRecord.CLASSIFICATION_OVERDUE, postFailure.missedRecord.classification)
        assertEquals(1, postFailure.remainingQueue.size)
        assertEquals(2L, postFailure.remainingQueue[0].alarmId)
        assertEquals(2L, postFailure.nextCandidateToArm?.alarmId)
        assertTrue(postFailure.cause is IllegalStateException)
        assertEquals("Post-commit non-cancellation error", postFailure.cause.message)

        // Verify storage was indeed committed before postCommitHook threw
        val queueState = DirectBootAlarmCache.inspectQueueStorageState(context) as QueueStorageState.ValidWithItems
        assertEquals(1, queueState.items.size)
        assertEquals(2L, queueState.items[0].alarmId)
        val missedState = DirectBootMissedStore.inspectStorageState(context) as MissedStoreStorageState.ValidWithRecords
        assertEquals(1, missedState.records.size)
        assertEquals(postFailure.missedRecord.recordId, missedState.records[0].recordId)
    }

    // P19: Post-commit cancellation preserves data
    @Test
    fun p19_postCommitCancellation_preservesData() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        val itemB = createNormalQueueItem(alarmId = 2L, triggerTime = triggerTime + 20_000L)
        populateQueue(listOf(itemA, itemB))

        val result = DirectBootMissedTransitionEngine.transitionMissedForTesting(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            postCommitHook = { throw CancellationException("Post-commit cancelled") }
        )

        assertTrue("P19: Expected CancellationAfterCommit, got: $result", result is MissedTransitionResult.CancellationAfterCommit)
        val cancelled = result as MissedTransitionResult.CancellationAfterCommit
        assertEquals(1L, cancelled.consumedItem.alarmId)
        assertEquals(1, cancelled.remainingQueue.size)
        assertEquals(2L, cancelled.nextCandidateToArm?.alarmId)
        assertEquals("Post-commit cancelled", cancelled.cause.message)
    }

    // P20: Policy evaluation and commit share lock
    @Test
    fun p20_policyEvaluationAndCommit_shareLock() {
        val triggerTime = baseTime + 10_000L
        val itemA = createNormalQueueItem(alarmId = 1L, triggerTime = triggerTime)
        populateQueue(listOf(itemA))

        var lockHeldAtPreCommit = false
        var lockHeldAtCommit = false

        val result = DirectBootMissedTransitionEngine.transitionMissedForTesting(
            context = context,
            occurrenceIdentity = normalIdentity(1L, triggerTime = triggerTime),
            missedAt = triggerTime + validOverdueOffset,
            facts = verifiedSameBootFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            preCommitHook = {
                lockHeldAtPreCommit = Thread.holdsLock(DirectBootStorageLock.lock)
            },
            editorCommitter = { prefs, q, m ->
                lockHeldAtCommit = Thread.holdsLock(DirectBootStorageLock.lock)
                prefs.edit()
                    .putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, q)
                    .putString(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD, m)
                    .commit()
            }
        )

        assertTrue(result is MissedTransitionResult.Confirmed)
        assertTrue("Lock must be held during pre-commit (after in-lock Policy evaluation)", lockHeldAtPreCommit)
        assertTrue("Lock must be held during commit", lockHeldAtCommit)
    }
}
