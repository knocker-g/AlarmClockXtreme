package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.domain.ScheduleAuthorityBootstrapAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DirectBootDeliveryTransitionEngineTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var reader: DirectBootNormalSnapshotReader
    private lateinit var coordinator: DirectBootNormalProjectionCoordinator
    private lateinit var projector: DirectBootNormalProjector

    private val baseTime = 1_700_000_000_000L

    private fun getDePrefs(): SharedPreferences = (if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext())
        .getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        getDePrefs().edit().clear().commit()

        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        reader = DirectBootNormalSnapshotReader(database = database, unlockGate = { true })
        coordinator = DirectBootNormalProjectionCoordinator(snapshotReader = reader)
        projector = DirectBootNormalProjector(
            context = context,
            database = database,
            coordinator = coordinator
        )

        runBlocking {
            database.scheduleAuthorityDao().insertMeta(
                ScheduleAuthorityMeta(id = 1, restoreEpoch = 1L, bootstrapState = "ENFORCED")
            )
        }
    }

    @After
    fun tearDown() {
        getDePrefs().edit().clear().commit()
        database.close()
    }

    private fun resetLedgerForTesting() {
        getDePrefs().edit()
            .remove(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD)
            .commit()
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

    private fun createQuickQueueItem(
        alarmId: Long,
        triggerTime: Long = baseTime + 15_000L
    ): DirectBootQueueItem {
        return DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_QUICK,
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = 0,
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            instanceToken = "",
            timeLabel = "7:15 AM",
            playDefaultSound = true,
            vibrationEnabled = true,
            updatedAt = baseTime
        )
    }

    private fun normalIdentity(
        alarmId: Long,
        triggerTime: Long = baseTime + 10_000L,
        generation: Int = 1,
        instanceToken: String = "token_$alarmId",
        provenance: String = "",
        restoreEpoch: Long = 1L
    ): FiredOccurrenceIdentity {
        return FiredOccurrenceIdentity(
            kind = DirectBootDeliveryRecord.KIND_NORMAL,
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = generation,
            instanceToken = instanceToken,
            restoreEpoch = restoreEpoch,
            provenance = provenance
        )
    }

    @Test
    fun scenario01_validNormalTransition_commitsAtomicallyAndReturnsConfirmed() {
        val q1 = createNormalQueueItem(alarmId = 1L, triggerTime = baseTime + 10_000L)
        val q2 = createNormalQueueItem(alarmId = 2L, triggerTime = baseTime + 20_000L)
        val q3 = createNormalQueueItem(alarmId = 3L, triggerTime = baseTime + 30_000L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1, q2, q3), now = baseTime)

        val identity = normalIdentity(alarmId = 1L, triggerTime = baseTime + 10_000L)
        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = identity,
            deliveredAt = baseTime + 10_050L
        )

        assertTrue("Expected Confirmed, was $result", result is DeliveryTransitionResult.Confirmed)
        val confirmed = result as DeliveryTransitionResult.Confirmed

        assertEquals(1L, confirmed.consumedItem.alarmId)
        assertEquals(baseTime + 10_000L, confirmed.consumedItem.triggerTime)
        assertEquals(1L, confirmed.deliveryRecord.alarmId)
        assertEquals(baseTime + 10_050L, confirmed.deliveryRecord.deliveredAt)
        assertEquals("delivery_NORMAL_1_gen1_token_1_trig${baseTime + 10_000L}", confirmed.deliveryRecord.recordId)

        assertEquals(2, confirmed.remainingQueue.size)
        assertEquals(2L, confirmed.remainingQueue[0].alarmId)
        assertEquals(3L, confirmed.remainingQueue[1].alarmId)
        assertEquals(2L, confirmed.nextCandidateToArm?.alarmId)

        // Verify on-disk storage consistency
        val onDiskQueue = DirectBootAlarmCache.readQueue(context, now = baseTime)
        assertEquals(2, onDiskQueue.size)
        assertEquals(2L, onDiskQueue[0].alarmId)
        assertEquals(3L, onDiskQueue[1].alarmId)

        val onDiskLedger = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue(onDiskLedger is LedgerStorageState.ValidWithRecords)
        val records = (onDiskLedger as LedgerStorageState.ValidWithRecords).records
        assertEquals(1, records.size)
        assertEquals(confirmed.deliveryRecord.recordId, records[0].recordId)
    }

    @Test
    fun scenario02_exactIdentityMismatch_rejectsAndPreservesQueue() {
        val q1 = createNormalQueueItem(alarmId = 1L, triggerTime = baseTime + 10_000L, instanceToken = "token_A")
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)

        // Firing event has different triggerTime
        val mismatchedTrigger = normalIdentity(
            alarmId = 1L,
            triggerTime = baseTime + 99_999L,
            instanceToken = "token_A"
        )
        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = mismatchedTrigger,
            deliveredAt = baseTime + 10_050L
        )

        assertTrue(result is DeliveryTransitionResult.Rejected.OccurrenceIdentityMismatch)
        val rejected = result as DeliveryTransitionResult.Rejected
        assertTrue(rejected.isGuaranteedNoWrite)
        assertFalse(rejected.deWriteAttempted)

        // Assert queue is completely untouched
        val onDiskQueue = DirectBootAlarmCache.readQueue(context, now = baseTime)
        assertEquals(1, onDiskQueue.size)
        assertEquals(baseTime + 10_000L, onDiskQueue[0].triggerTime)

        // Assert ledger is empty/absent
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(context) is LedgerStorageState.Absent)
    }

    @Test
    fun scenario03_newGenerationPreserved_whenStaleGenerationFires() {
        // Queue has rescheduled generation 2
        val q1Gen2 = createNormalQueueItem(
            alarmId = 1L,
            triggerTime = baseTime + 10_000L,
            generation = 2,
            instanceToken = "token_gen2"
        )
        DirectBootAlarmCache.writeQueue(context, listOf(q1Gen2), now = baseTime)

        // Firing intent arrives from older generation 1
        val staleGen1Identity = normalIdentity(
            alarmId = 1L,
            triggerTime = baseTime + 10_000L,
            generation = 1,
            instanceToken = "token_gen2"
        )
        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = staleGen1Identity,
            deliveredAt = baseTime + 10_050L
        )

        assertTrue(result is DeliveryTransitionResult.Rejected.StaleGenerationMismatch)
        val stale = result as DeliveryTransitionResult.Rejected.StaleGenerationMismatch
        assertEquals(2, stale.foundGeneration)
        assertEquals(1, stale.targetGeneration)
        assertTrue(stale.isGuaranteedNoWrite)

        // Queue must preserve generation 2
        val onDiskQueue = DirectBootAlarmCache.readQueue(context, now = baseTime)
        assertEquals(1, onDiskQueue.size)
        assertEquals(2, onDiskQueue[0].generation)
    }

    @Test
    fun scenario04_changedTokenPreserved_whenStaleTokenFires() {
        val q1NewToken = createNormalQueueItem(
            alarmId = 1L,
            triggerTime = baseTime + 10_000L,
            generation = 1,
            instanceToken = "token_NEW"
        )
        DirectBootAlarmCache.writeQueue(context, listOf(q1NewToken), now = baseTime)

        val staleTokenIdentity = normalIdentity(
            alarmId = 1L,
            triggerTime = baseTime + 10_000L,
            generation = 1,
            instanceToken = "token_OLD"
        )
        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = staleTokenIdentity,
            deliveredAt = baseTime + 10_050L
        )

        assertTrue(result is DeliveryTransitionResult.Rejected.OccurrenceIdentityMismatch)
        val onDiskQueue = DirectBootAlarmCache.readQueue(context, now = baseTime)
        assertEquals("token_NEW", onDiskQueue[0].instanceToken)
    }

    @Test
    fun scenario05_duplicateDeliveryIdentity_rejectsWhenAlreadyInLedger() {
        val q1 = createNormalQueueItem(alarmId = 1L, triggerTime = baseTime + 10_000L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)

        // Seed existing record in Ledger
        val existingRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 1L,
            triggerTime = baseTime + 10_000L,
            deliveredAt = baseTime + 10_000L,
            generation = 1,
            instanceToken = "token_1"
        )
        DirectBootDeliveryLedger.appendRecord(context, existingRecord)

        val identity = normalIdentity(alarmId = 1L, triggerTime = baseTime + 10_000L)
        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = identity,
            deliveredAt = baseTime + 10_050L
        )

        assertTrue(result is DeliveryTransitionResult.Rejected.DuplicateDeliveryAlreadyRecorded)
        val duplicate = result as DeliveryTransitionResult.Rejected.DuplicateDeliveryAlreadyRecorded
        assertEquals(existingRecord.recordId, duplicate.existing.recordId)
        assertTrue(duplicate.isGuaranteedNoWrite)

        // Queue item was NOT removed because duplicate in ledger was rejected
        val onDiskQueue = DirectBootAlarmCache.readQueue(context, now = baseTime)
        assertEquals(1, onDiskQueue.size)
    }

    @Test
    fun scenario06_queueAbsent_rejectsWithoutMutation() {
        DirectBootAlarmCache.clearQueue(context)
        val identity = normalIdentity(alarmId = 1L)

        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = identity
        )

        assertTrue(result is DeliveryTransitionResult.Rejected.QueueAbsent)
        assertTrue((result as DeliveryTransitionResult.Rejected).isGuaranteedNoWrite)
    }

    @Test
    fun scenario07_ledgerAbsent_createsNewLedgerWithRecord() {
        val q1 = createNormalQueueItem(alarmId = 1L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)
        resetLedgerForTesting() // ensure absent

        val identity = normalIdentity(alarmId = 1L)
        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = identity,
            deliveredAt = baseTime + 10_050L
        )

        assertTrue(result is DeliveryTransitionResult.Confirmed)
        val ledgerState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue(ledgerState is LedgerStorageState.ValidWithRecords)
        assertEquals(1, (ledgerState as LedgerStorageState.ValidWithRecords).records.size)
    }

    @Test
    fun scenario08_ledgerFullAt16_rejectsWithCapacityExhausted() {
        val q1 = createNormalQueueItem(alarmId = 99L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)

        // Fill ledger to 16 records
        for (i in 1L..16L) {
            val rec = DirectBootDeliveryRecord.createNormal(
                alarmId = i,
                triggerTime = baseTime + (i * 1000L),
                deliveredAt = baseTime + (i * 1000L),
                generation = 1,
                instanceToken = "token_$i"
            )
            DirectBootDeliveryLedger.appendRecord(context, rec)
        }
        assertEquals(16, (DirectBootDeliveryLedger.readUnacknowledgedRecords(context) as LedgerStorageState.ValidWithRecords).records.size)

        val identity = normalIdentity(alarmId = 99L)
        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = identity
        )

        assertTrue(result is DeliveryTransitionResult.Rejected.CapacityExhausted)
        val exhausted = result as DeliveryTransitionResult.Rejected.CapacityExhausted
        assertEquals(16, exhausted.currentCount)
        assertEquals(16, exhausted.maxCapacity)
        assertTrue(exhausted.isGuaranteedNoWrite)

        // Queue must NOT be consumed
        val onDiskQueue = DirectBootAlarmCache.readQueue(context, now = baseTime)
        assertEquals(1, onDiskQueue.size)
        assertEquals(99L, onDiskQueue[0].alarmId)
    }

    @Test
    fun scenario09_corruptQueue_rejectsAndPreservesRawBytes() {
        val corruptQueueJson = "{\"schemaVersion\": 2, \"items\": [corrupt"
        getDePrefs().edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, corruptQueueJson).commit()

        val identity = normalIdentity(alarmId = 1L)
        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = identity
        )

        assertTrue(result is DeliveryTransitionResult.Rejected.QueueStorageCorrupt)
        assertTrue((result as DeliveryTransitionResult.Rejected).isGuaranteedNoWrite)

        // Raw bytes preserved
        assertEquals(corruptQueueJson, DirectBootAlarmCache.getRawQueuePayload(context))
        // Ledger untouched
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(context) is LedgerStorageState.Absent)
    }

    @Test
    fun scenario10_corruptLedger_rejectsAndPreservesRawBytes() {
        val q1 = createNormalQueueItem(alarmId = 1L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)

        val corruptLedgerJson = "{\"schemaVersion\": 1, \"records\": [corrupt"
        getDePrefs().edit().putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, corruptLedgerJson).commit()

        val identity = normalIdentity(alarmId = 1L)
        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = identity
        )

        assertTrue(result is DeliveryTransitionResult.Rejected.LedgerStorageCorrupt)
        assertTrue((result as DeliveryTransitionResult.Rejected).isGuaranteedNoWrite)

        // Raw bytes preserved
        assertEquals(corruptLedgerJson, DirectBootDeliveryLedger.getRawPayload(context))
        // Queue untouched
        assertEquals(1, DirectBootAlarmCache.readQueue(context, now = baseTime).size)
    }

    @Test
    fun scenario11_unsupportedSchemaOnQueue_rejectsAndPreservesRawBytes() {
        val unsupportedQueue = "{\"schemaVersion\": 99, \"items\": []}"
        getDePrefs().edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, unsupportedQueue).commit()

        val identity = normalIdentity(alarmId = 1L)
        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = identity
        )

        assertTrue(result is DeliveryTransitionResult.Rejected.UnsupportedSchema)
        val unsupported = result as DeliveryTransitionResult.Rejected.UnsupportedSchema
        assertEquals("Queue", unsupported.domain)
        assertEquals(99, unsupported.found)
        assertEquals(DirectBootQueueItem.CURRENT_SCHEMA_VERSION, unsupported.expected)
        assertEquals(unsupportedQueue, DirectBootAlarmCache.getRawQueuePayload(context))
    }

    @Test
    fun scenario12_unsupportedSchemaOnLedger_rejectsAndPreservesRawBytes() {
        val q1 = createNormalQueueItem(alarmId = 1L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)

        val unsupportedLedger = "{\"schemaVersion\": 88, \"records\": []}"
        getDePrefs().edit().putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, unsupportedLedger).commit()

        val identity = normalIdentity(alarmId = 1L)
        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = identity
        )

        assertTrue(result is DeliveryTransitionResult.Rejected.UnsupportedSchema)
        val unsupported = result as DeliveryTransitionResult.Rejected.UnsupportedSchema
        assertEquals("Ledger", unsupported.domain)
        assertEquals(88, unsupported.found)
        assertEquals(DirectBootDeliveryLedger.CURRENT_SCHEMA_VERSION, unsupported.expected)
        assertEquals(unsupportedLedger, DirectBootDeliveryLedger.getRawPayload(context))
    }

    @Test
    fun scenario13_mixedNormalAndQuickQueue_preservesQuickEntries() {
        val normal1 = createNormalQueueItem(alarmId = 1L, triggerTime = baseTime + 10_000L)
        val quick2 = createQuickQueueItem(alarmId = 2L, triggerTime = baseTime + 20_000L)
        val normal3 = createNormalQueueItem(alarmId = 3L, triggerTime = baseTime + 30_000L)

        // Seed mixed queue via writeQueue
        DirectBootAlarmCache.writeQueue(context, listOf(normal1, quick2, normal3), now = baseTime)

        val identity = normalIdentity(alarmId = 1L, triggerTime = baseTime + 10_000L)
        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = identity,
            deliveredAt = baseTime + 10_050L
        )

        assertTrue(result is DeliveryTransitionResult.Confirmed)
        val confirmed = result as DeliveryTransitionResult.Confirmed

        // Quick entry must be preserved intact
        assertEquals(2, confirmed.remainingQueue.size)
        assertEquals(2L, confirmed.remainingQueue[0].alarmId)
        assertEquals(DirectBootQueueItem.KIND_QUICK, confirmed.remainingQueue[0].kind)
        assertEquals(3L, confirmed.remainingQueue[1].alarmId)
        assertEquals(DirectBootQueueItem.KIND_NORMAL, confirmed.remainingQueue[1].kind)

        val onDiskQueue = DirectBootAlarmCache.readQueue(context, now = baseTime)
        assertEquals(2, onDiskQueue.size)
        assertEquals(2L, onDiskQueue[0].alarmId)
        assertEquals(DirectBootQueueItem.KIND_QUICK, onDiskQueue[0].kind)
    }

    @Test
    fun scenario14_queueCapacityFive_remainsRespected() {
        val items = (1L..5L).map { id ->
            createNormalQueueItem(alarmId = id, triggerTime = baseTime + (id * 10_000L))
        }
        DirectBootAlarmCache.writeQueue(context, items, now = baseTime)

        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = normalIdentity(alarmId = 1L, triggerTime = baseTime + 10_000L),
            deliveredAt = baseTime + 10_050L
        )

        assertTrue(result is DeliveryTransitionResult.Confirmed)
        val confirmed = result as DeliveryTransitionResult.Confirmed
        assertEquals(4, confirmed.remainingQueue.size)
        assertEquals(2L, confirmed.nextCandidateToArm?.alarmId)
    }

    @Test
    fun scenario15_quickNotQualified_explicitlyRejected() {
        val quickIdentity = FiredOccurrenceIdentity(
            kind = DirectBootDeliveryRecord.KIND_QUICK,
            alarmId = 10L,
            triggerTime = baseTime + 10_000L,
            generation = 0,
            instanceToken = ""
        )

        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = quickIdentity
        )

        assertTrue(result is DeliveryTransitionResult.Rejected.QuickNotQualified)
        assertTrue((result as DeliveryTransitionResult.Rejected).isGuaranteedNoWrite)
    }

    @Test
    fun scenario16_commitFailure_returnsCommitFailed() {
        val q1 = createNormalQueueItem(alarmId = 1L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)

        val result = DirectBootDeliveryTransitionEngine.transitionNormalForTesting(
            context = context,
            firingIdentity = normalIdentity(alarmId = 1L),
            deliveredAt = baseTime + 10_050L,
            editorCommitter = { _, _, _ -> false }
        )

        assertTrue(result is DeliveryTransitionResult.Uncertain.CommitFailed)
        val failed = result as DeliveryTransitionResult.Uncertain.CommitFailed
        assertTrue(failed.deWriteAttempted)
        assertFalse(failed.isGuaranteedNoWrite)
        assertEquals("SharedPreferences.Editor.commit() returned false", failed.details)
    }

    @Test
    fun scenario17_commitException_returnsCommitException() {
        val q1 = createNormalQueueItem(alarmId = 1L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)

        val result = DirectBootDeliveryTransitionEngine.transitionNormalForTesting(
            context = context,
            firingIdentity = normalIdentity(alarmId = 1L),
            deliveredAt = baseTime + 10_050L,
            editorCommitter = { _, _, _ -> throw RuntimeException("Disk sync failure") }
        )

        assertTrue(result is DeliveryTransitionResult.Uncertain.CommitException)
        val err = result as DeliveryTransitionResult.Uncertain.CommitException
        assertTrue(err.deWriteAttempted)
        assertFalse(err.isGuaranteedNoWrite)
        assertTrue(err.details.contains("Disk sync failure"))
    }

    @Test
    fun scenario18_cancellationBeforeCommit_propagatesAndLeavesStorageUntouched() {
        val q1 = createNormalQueueItem(alarmId = 1L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)

        try {
            DirectBootDeliveryTransitionEngine.transitionNormalForTesting(
                context = context,
                firingIdentity = normalIdentity(alarmId = 1L),
                deliveredAt = baseTime + 10_050L,
                preCommitHook = {
                    throw CancellationException("Cancelled before commit")
                }
            )
            fail("Expected CancellationException to be thrown")
        } catch (e: CancellationException) {
            assertEquals("Cancelled before commit", e.message)
        }

        // Assert on-disk storage is untouched
        assertEquals(1, DirectBootAlarmCache.readQueue(context, now = baseTime).size)
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(context) is LedgerStorageState.Absent)
    }

    @Test
    fun scenario19_cancellationAfterCommit_returnsCancellationAfterCommit() {
        val q1 = createNormalQueueItem(alarmId = 1L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)

        val result = DirectBootDeliveryTransitionEngine.transitionNormalForTesting(
            context = context,
            firingIdentity = normalIdentity(alarmId = 1L),
            deliveredAt = baseTime + 10_050L,
            postCommitHook = {
                throw CancellationException("Cancelled after commit")
            }
        )

        assertTrue(result is DeliveryTransitionResult.CancellationAfterCommit)
        val after = result as DeliveryTransitionResult.CancellationAfterCommit
        assertEquals("Cancelled after commit", after.cancellation.message)

        // Storage was committed
        assertEquals(0, DirectBootAlarmCache.readQueue(context, now = baseTime).size)
        val ledgerState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue(ledgerState is LedgerStorageState.ValidWithRecords)
        assertEquals(1, (ledgerState as LedgerStorageState.ValidWithRecords).records.size)
    }

    @Test
    fun scenario20_concurrentNormalProjectorUpdate_serializesUnderStorageLock() = runBlocking(Dispatchers.IO) {
        val q1 = createNormalQueueItem(alarmId = 1L, triggerTime = baseTime + 10_000L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)

        // Seed DB for Projector
        val alarm2 = Alarm(
            id = 2L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            nextTriggerTime = baseTime + 20_000L,
            generation = 1,
            revision = 1
        )
        database.alarmDao().insert(alarm2)
        val auth2 = ScheduleAuthority(
            alarmId = 2L,
            instanceToken = "token_norm_2",
            definitionRevision = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(alarm2),
            scheduleRevision = 1,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = baseTime + 20_000L,
            reconcileState = "SETTLED",
            restoreEpoch = 1L,
            actionKey = null
        )
        database.scheduleAuthorityDao().insertAuthority(auth2)

        val transitionJob = async {
            DirectBootDeliveryTransitionEngine.transitionNormal(
                context = context,
                firingIdentity = normalIdentity(alarmId = 1L, triggerTime = baseTime + 10_000L),
                deliveredAt = baseTime + 10_050L
            )
        }
        val projectorJob = async {
            projector.project(now = baseTime)
        }

        val transitionRes = transitionJob.await()
        val projectRes = projectorJob.await()

        assertTrue("transitionRes was: $transitionRes", transitionRes is DeliveryTransitionResult.Confirmed)
        assertTrue("projectRes was: $projectRes", projectRes is DirectBootNormalProjectionResult.Success)

        // Verify storage is clean and valid
        val finalQueue = DirectBootAlarmCache.readQueue(context, now = baseTime)
        assertNotNull(finalQueue)
        val finalLedger = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue(finalLedger is LedgerStorageState.ValidWithRecords)
    }

    @Test
    fun scenario21_concurrentLedgerAppend_serializesUnderStorageLock() = runBlocking(Dispatchers.IO) {
        val q1 = createNormalQueueItem(alarmId = 1L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)

        val rec2 = DirectBootDeliveryRecord.createNormal(
            alarmId = 2L,
            triggerTime = baseTime + 20_000L,
            deliveredAt = baseTime + 20_000L,
            generation = 1,
            instanceToken = "token_2"
        )

        val jobs = listOf(
            async {
                DirectBootDeliveryTransitionEngine.transitionNormal(
                    context = context,
                    firingIdentity = normalIdentity(alarmId = 1L)
                )
            },
            async {
                DirectBootDeliveryLedger.appendRecord(context, rec2)
            }
        )

        val results = jobs.awaitAll()
        assertTrue(results[0] is DeliveryTransitionResult.Confirmed)
        assertTrue(results[1] is DeliveryAppendResult.Appended)

        val ledgerState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue(ledgerState is LedgerStorageState.ValidWithRecords)
        assertEquals(2, (ledgerState as LedgerStorageState.ValidWithRecords).records.size)
    }

    @Test
    fun scenario22_concurrentLedgerAck_serializesUnderStorageLock() = runBlocking(Dispatchers.IO) {
        val q1 = createNormalQueueItem(alarmId = 1L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)

        val rec2 = DirectBootDeliveryRecord.createNormal(
            alarmId = 2L,
            triggerTime = baseTime + 20_000L,
            deliveredAt = baseTime + 20_000L,
            generation = 1,
            instanceToken = "token_2"
        )
        DirectBootDeliveryLedger.appendRecord(context, rec2)

        val lockHeldObservations = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val activeInCriticalSection = java.util.concurrent.atomic.AtomicInteger(0)
        val maxConcurrentInLock = java.util.concurrent.atomic.AtomicInteger(0)

        DirectBootDeliveryLedger.readbackSeam = { prefs ->
            lockHeldObservations.add(Thread.holdsLock(DirectBootStorageLock.lock))
            val currentActive = activeInCriticalSection.incrementAndGet()
            maxConcurrentInLock.updateAndGet { prev -> maxOf(prev, currentActive) }
            try {
                prefs.getString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, null)
            } finally {
                activeInCriticalSection.decrementAndGet()
            }
        }

        val results = try {
            val jobs = listOf(
                async {
                    DirectBootDeliveryTransitionEngine.transitionNormal(
                        context = context,
                        firingIdentity = normalIdentity(alarmId = 1L)
                    )
                },
                async {
                    // Raw DE Delivery ACK without live CE takeover proof is sealed in P3-D-P2B
                    // (qualified ACK concurrency is verified in DirectBootCeHandoffCoordinatorTest A21/A22).
                    // It still acquires DirectBootStorageLock.lock, serializes against transitionNormal,
                    // and fails closed with AckNotQualified without deleting rec2 or losing q1's transition.
                    DirectBootDeliveryLedger.acknowledgeRecord(context, rec2.recordId)
                }
            )
            jobs.awaitAll()
        } finally {
            DirectBootDeliveryLedger.resetSeamsForTesting()
        }

        assertTrue("Expected DeliveryTransitionResult.Confirmed but got ${results[0]}", results[0] is DeliveryTransitionResult.Confirmed)
        val confirmed = results[0] as DeliveryTransitionResult.Confirmed
        assertEquals(1L, confirmed.deliveryRecord.alarmId)
        assertTrue(confirmed.remainingQueue.isEmpty())

        assertTrue("Expected DeliveryAckResult.AckNotQualified for raw ACK without CE proof, got ${results[1]}", results[1] is DeliveryAckResult.AckNotQualified)
        val ackResult = results[1] as DeliveryAckResult.AckNotQualified
        assertTrue(
            "AckNotQualified currentCount must reflect serialized Ledger snapshot (1 before transition commit or 2 after), got ${ackResult.currentCount}",
            ackResult.currentCount in setOf(1, 2)
        )

        assertTrue(
            "All concurrent Ledger inspections must hold canonical DirectBootStorageLock.lock",
            lockHeldObservations.size >= 2 && lockHeldObservations.all { it }
        )
        assertEquals(
            "Critical section under DirectBootStorageLock.lock must strictly serialize without overlap",
            1,
            maxConcurrentInLock.get()
        )

        // Queue head q1 was consumed cleanly
        assertEquals(0, DirectBootAlarmCache.readQueue(context, now = baseTime).size)

        // Neither the raw ACK target (rec2) nor the newly transitioned delivery (alarmId=1L) was deleted or lost
        val ledgerState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue(ledgerState is LedgerStorageState.ValidWithRecords)
        val records = (ledgerState as LedgerStorageState.ValidWithRecords).records
        assertEquals(2, records.size)
        assertEquals(rec2, records[0])
        assertEquals(2L, records[0].alarmId)
        assertEquals(confirmed.deliveryRecord, records[1])
        assertEquals(1L, records[1].alarmId)
    }

    @Test
    fun scenario23_repeatedTransitionIdempotency_rejectsSecondAttempt() {
        val q1 = createNormalQueueItem(alarmId = 1L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)

        val first = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = normalIdentity(alarmId = 1L),
            deliveredAt = baseTime + 10_050L
        )
        assertTrue(first is DeliveryTransitionResult.Confirmed)

        val second = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = normalIdentity(alarmId = 1L),
            deliveredAt = baseTime + 10_050L
        )
        assertTrue("Second transition must be rejected, was: $second",
            second is DeliveryTransitionResult.Rejected.DuplicateDeliveryAlreadyRecorded ||
                second is DeliveryTransitionResult.Rejected.OccurrenceNotFound
        )

        val ledgerState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertEquals(1, (ledgerState as LedgerStorageState.ValidWithRecords).records.size)
    }

    @Test
    fun scenario24_v1ScalarKeysUntouchedAcrossTransitions() {
        // Seed V1 keys
        val prefs = getDePrefs()
        prefs.edit()
            .putLong("alarm_id", 42L)
            .putLong("trigger_time", 9999L)
            .putString("label", "V1 Label")
            .putLong("fired_alarm_id", 77L)
            .putLong("fired_trigger_time", 8888L)
            .putLong("fired_at", 7777L)
            .commit()

        val q1 = createNormalQueueItem(alarmId = 1L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)

        DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = normalIdentity(alarmId = 1L),
            deliveredAt = baseTime + 10_050L
        )

        // Verify V1 keys are 100% untouched
        assertEquals(42L, prefs.getLong("alarm_id", -1L))
        assertEquals(9999L, prefs.getLong("trigger_time", -1L))
        assertEquals("V1 Label", prefs.getString("label", null))
        assertEquals(77L, prefs.getLong("fired_alarm_id", -1L))
        assertEquals(8888L, prefs.getLong("fired_trigger_time", -1L))
        assertEquals(7777L, prefs.getLong("fired_at", -1L))
    }

    @Test
    fun scenario25_singleEditorProof_verifiesSingleCommitInvocation() {
        val q1 = createNormalQueueItem(alarmId = 1L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)

        var committerInvocationCount = 0
        var capturedQueuePayload: String? = null
        var capturedLedgerPayload: String? = null

        val result = DirectBootDeliveryTransitionEngine.transitionNormalForTesting(
            context = context,
            firingIdentity = normalIdentity(alarmId = 1L),
            deliveredAt = baseTime + 10_050L,
            editorCommitter = { prefs, queuePayload, ledgerPayload ->
                committerInvocationCount++
                capturedQueuePayload = queuePayload
                capturedLedgerPayload = ledgerPayload
                // Execute standard single-editor commit
                prefs.edit()
                    .putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, queuePayload)
                    .putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, ledgerPayload)
                    .commit()
            }
        )

        assertTrue(result is DeliveryTransitionResult.Confirmed)
        assertEquals("Committer must be invoked exactly once", 1, committerInvocationCount)
        assertNotNull("Queue payload must be passed in single commit", capturedQueuePayload)
        assertNotNull("Ledger payload must be passed in single commit", capturedLedgerPayload)
        assertTrue(capturedQueuePayload!!.contains("\"items\":[]"))
        assertTrue(capturedLedgerPayload!!.contains("\"records\":["))
    }

    @Test
    fun scenario26_nonHeadOccurrenceInQueue_rejectsWithNotEligibleQueueHead() {
        val q1 = createNormalQueueItem(alarmId = 1L, triggerTime = baseTime + 10_000L)
        val q2 = createNormalQueueItem(alarmId = 2L, triggerTime = baseTime + 20_000L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1, q2), now = baseTime)

        // Caller attempts to consume q2 (which is at position 1, not head)
        val nonHeadIdentity = normalIdentity(alarmId = 2L, triggerTime = baseTime + 20_000L)
        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = nonHeadIdentity,
            deliveredAt = baseTime + 20_050L
        )

        assertTrue("Expected NotEligibleQueueHead, was $result", result is DeliveryTransitionResult.Rejected.NotEligibleQueueHead)
        val notHead = result as DeliveryTransitionResult.Rejected.NotEligibleQueueHead
        assertEquals(1L, notHead.headCandidate.alarmId)
        assertTrue(notHead.isGuaranteedNoWrite)
        assertFalse(notHead.deWriteAttempted)

        // Storage remains completely untouched
        val onDiskQueue = DirectBootAlarmCache.readQueue(context, now = baseTime)
        assertEquals(2, onDiskQueue.size)
        assertEquals(1L, onDiskQueue[0].alarmId)
        assertEquals(2L, onDiskQueue[1].alarmId)
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(context) is LedgerStorageState.Absent)
    }

    @Test
    fun scenario27_prematureTriggerDeliveredBeforeTriggerTime_rejectsWithPrematureTrigger() {
        val q1 = createNormalQueueItem(alarmId = 1L, triggerTime = baseTime + 10_000L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)

        // Delivered at 9_999L < 10_000L
        val headIdentity = normalIdentity(alarmId = 1L, triggerTime = baseTime + 10_000L)
        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = headIdentity,
            deliveredAt = baseTime + 9_999L
        )

        assertTrue("Expected PrematureTrigger, was $result", result is DeliveryTransitionResult.Rejected.PrematureTrigger)
        val premature = result as DeliveryTransitionResult.Rejected.PrematureTrigger
        assertEquals(baseTime + 9_999L, premature.deliveredAt)
        assertEquals(baseTime + 10_000L, premature.triggerTime)
        assertTrue(premature.isGuaranteedNoWrite)
        assertFalse(premature.deWriteAttempted)

        // Storage untouched
        assertEquals(1, DirectBootAlarmCache.readQueue(context, now = baseTime).size)
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(context) is LedgerStorageState.Absent)
    }

    @Test
    fun scenario28_productionApi_usesRealEditorAndCannotBeBypassed() {
        val q1 = createNormalQueueItem(alarmId = 1L, triggerTime = baseTime + 10_000L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)

        // Production entrypoint transitionNormal only takes (context, firingIdentity, deliveredAt)
        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = normalIdentity(alarmId = 1L, triggerTime = baseTime + 10_000L),
            deliveredAt = baseTime + 10_050L
        )

        assertTrue(result is DeliveryTransitionResult.Confirmed)
        // Verify real SharedPreferences was updated on disk
        assertEquals(0, DirectBootAlarmCache.readQueue(context, now = baseTime).size)
        val ledger = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        assertTrue(ledger is LedgerStorageState.ValidWithRecords)
        assertEquals(1, (ledger as LedgerStorageState.ValidWithRecords).records.size)
    }

    @Test
    fun scenario29_staleIdentityDifferentTokenAtHead_rejectsWithOccurrenceIdentityMismatch() {
        val q1 = createNormalQueueItem(alarmId = 1L, triggerTime = baseTime + 10_000L, instanceToken = "token_correct")
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)

        val mismatchedTokenIdentity = normalIdentity(
            alarmId = 1L,
            triggerTime = baseTime + 10_000L,
            instanceToken = "token_stale"
        )
        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = mismatchedTokenIdentity,
            deliveredAt = baseTime + 10_050L
        )

        assertTrue(result is DeliveryTransitionResult.Rejected.OccurrenceIdentityMismatch)
        assertTrue((result as DeliveryTransitionResult.Rejected).isGuaranteedNoWrite)
        assertEquals(1, DirectBootAlarmCache.readQueue(context, now = baseTime).size)
    }

    // =========================================================================
    // KNO-75 C7-A0-v3: Restore Epoch, Privacy, Isolation Hardening Tests
    // =========================================================================

    @Test
    fun a0_01_epochMismatch_transitionRejectsWithoutMutation() {
        val q1 = createNormalQueueItem(alarmId = 1L, triggerTime = baseTime + 10_000L, restoreEpoch = 3L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)

        val firingIdentity = normalIdentity(alarmId = 1L, triggerTime = baseTime + 10_000L, restoreEpoch = 2L)
        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = firingIdentity,
            deliveredAt = baseTime + 10_050L
        )

        assertTrue("Expected Rejected for epoch mismatch, got $result", result is DeliveryTransitionResult.Rejected)
        assertTrue((result as DeliveryTransitionResult.Rejected).isGuaranteedNoWrite)
        assertEquals(1, DirectBootAlarmCache.readQueue(context, now = baseTime).size)
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(context) is LedgerStorageState.Absent)
    }

    @Test
    fun a0_02_epochZero_firingIdentityRejectsWithoutMutation() {
        val q1 = createNormalQueueItem(alarmId = 1L, triggerTime = baseTime + 10_000L, restoreEpoch = 1L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)

        val zeroEpochIdentity = normalIdentity(alarmId = 1L, triggerTime = baseTime + 10_000L, restoreEpoch = 0L)
        assertFalse("isValidNormal MUST require restoreEpoch > 0", zeroEpochIdentity.isValidNormal())

        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = zeroEpochIdentity,
            deliveredAt = baseTime + 10_050L
        )

        assertTrue("Expected InvalidOccurrenceIdentity for restoreEpoch == 0, got $result", result is DeliveryTransitionResult.Rejected.InvalidOccurrenceIdentity)
        assertTrue((result as DeliveryTransitionResult.Rejected).isGuaranteedNoWrite)
        assertEquals(1, DirectBootAlarmCache.readQueue(context, now = baseTime).size)
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(context) is LedgerStorageState.Absent)
    }

    @Test
    fun a0_03_queueEpochZeroLegacySchema_rejectsWithoutDowngradingToV1() {
        val legacyQueueItem = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 1L,
            triggerTime = baseTime + 10_000L,
            generation = 1,
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            instanceToken = "token_1",
            timeLabel = "7:00 AM",
            playDefaultSound = true,
            vibrationEnabled = true,
            updatedAt = baseTime,
            restoreEpoch = 0L,
            schemaVersion = 2
        )
        DirectBootAlarmCache.writeQueue(context, listOf(legacyQueueItem), now = baseTime)

        val firingIdentity = normalIdentity(alarmId = 1L, triggerTime = baseTime + 10_000L, restoreEpoch = 1L)
        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = firingIdentity,
            deliveredAt = baseTime + 10_050L
        )

        assertTrue("Expected Rejected for queue restoreEpoch == 0 / legacy schema v2, got $result", result is DeliveryTransitionResult.Rejected)
        assertTrue((result as DeliveryTransitionResult.Rejected).isGuaranteedNoWrite)
        assertEquals(1, DirectBootAlarmCache.readQueue(context, now = baseTime).size)
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(context) is LedgerStorageState.Absent)
    }

    @Test
    fun a0_04_positiveEqualEpoch_confirmsAndRecordsEpoch() {
        val q1 = createNormalQueueItem(alarmId = 1L, triggerTime = baseTime + 10_000L, restoreEpoch = 5L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)

        val result = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = normalIdentity(alarmId = 1L, triggerTime = baseTime + 10_000L, restoreEpoch = 5L),
            deliveredAt = baseTime + 10_050L
        )

        assertTrue("Expected Confirmed for positive equal restoreEpoch=5, got $result", result is DeliveryTransitionResult.Confirmed)
        val confirmed = result as DeliveryTransitionResult.Confirmed
        assertEquals(5L, confirmed.deliveryRecord.restoreEpoch)
        assertEquals(DirectBootDeliveryRecord.CURRENT_SCHEMA_VERSION, confirmed.deliveryRecord.schemaVersion)
    }

    @Test
    fun a0_10_transitionEngineDiagnostics_redactRawInstanceToken() {
        org.robolectric.shadows.ShadowLog.clear()
        val secretToken = "secret_tok_a010_transition"
        val q1 = createNormalQueueItem(alarmId = 1L, triggerTime = baseTime + 10_000L, instanceToken = secretToken, restoreEpoch = 1L)
        DirectBootAlarmCache.writeQueue(context, listOf(q1), now = baseTime)

        val existingRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 1L,
            triggerTime = baseTime + 10_000L,
            deliveredAt = baseTime + 10_010L,
            generation = 1,
            instanceToken = secretToken,
            restoreEpoch = 1L
        )
        DirectBootDeliveryLedger.appendRecord(context, existingRecord)

        val dupResult = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = normalIdentity(alarmId = 1L, triggerTime = baseTime + 10_000L, instanceToken = secretToken, restoreEpoch = 1L),
            deliveredAt = baseTime + 10_050L
        )
        assertTrue(dupResult is DeliveryTransitionResult.Rejected.DuplicateDeliveryAlreadyRecorded)

        val logs = org.robolectric.shadows.ShadowLog.getLogsForTag("DirectBootDeliveryTransition")
        assertTrue(
            "Transition logs MUST NOT contain raw instanceToken",
            logs.none { it.msg.contains(secretToken) }
        )
    }
}
