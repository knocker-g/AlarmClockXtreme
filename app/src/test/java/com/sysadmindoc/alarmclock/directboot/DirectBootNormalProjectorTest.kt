package com.sysadmindoc.alarmclock.directboot

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.domain.CoverageReasonCode
import com.sysadmindoc.alarmclock.domain.CoverageResult
import com.sysadmindoc.alarmclock.domain.ScheduleAuthorityBootstrapAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
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

/**
 * Deterministic focused test suite for KNO-75 B1C5 Inactive DirectBootNormalProjector.
 *
 * Covers:
 * 1. Valid Normal projection
 * 2. ReadyEmpty clears Normal entries
 * 3. Quick-only DE queue preserved
 * 4. Mixed Normal + Quick queue merged and sorted
 * 5. Capacity 5 with Normal/Quick competition (nearest 5 retained, lower excluded)
 * 6. Same logical ID replacement (generation bump)
 * 7. Stale fingerprint detection (trigger changed during planning)
 * 8. Same-generation authority change detection
 * 9. Disable or delete during planning detection
 * 10. Restore epoch change detection
 * 11. Corrupt payload in storage rejected fail-closed, raw bytes preserved
 * 12. Unsupported schema in storage rejected fail-closed, raw bytes preserved
 * 13. Persistence failure handling
 * 14. Cancellation before commit leaves DE untouched
 * 15. Cancellation after confirmed write classifies CancellationAfterWrite
 * 16. Ambiguous outcome after confirmed write with transaction failure
 * 17. Concurrent Room mutation serialized under transaction
 * 18. Concurrent queue update thread-safety
 * 19. Repeated projection idempotency
 * 20. Blocked Coordinator outcomes produce zero DE mutations
 * 21. V1 scalar keys preserved intact across projection
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DirectBootNormalProjectorTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var reader: DirectBootNormalSnapshotReader
    private lateinit var coordinator: DirectBootNormalProjectionCoordinator
    private lateinit var projector: DirectBootNormalProjector

    private val baseTime = 1_000_000L

    private fun getDePrefs() = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DirectBootAlarmCache.clear(context)
        DirectBootAlarmCache.clearQueue(context)

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
        DirectBootAlarmCache.clear(context)
        DirectBootAlarmCache.clearQueue(context)
        database.close()
    }

    private suspend fun seedAlarmAndAuthority(
        alarmId: Long,
        triggerTime: Long,
        isEnabled: Boolean = true,
        generation: Int = 1,
        revision: Int = 1,
        scheduleRevision: Long = 1L,
        reconcileState: String = "SETTLED"
    ): Pair<Alarm, ScheduleAuthority> {
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = isEnabled,
            kind = Alarm.KIND_NORMAL,
            nextTriggerTime = triggerTime,
            generation = generation,
            revision = revision
        )
        database.alarmDao().insert(alarm)

        val authority = ScheduleAuthority(
            alarmId = alarmId,
            instanceToken = "token_norm_$alarmId",
            definitionRevision = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(alarm),
            scheduleRevision = scheduleRevision,
            lifecycle = "LIVE",
            desiredOperation = if (isEnabled) "SET" else "CANCEL",
            desiredTriggerMillis = if (isEnabled) triggerTime else null,
            reconcileState = reconcileState,
            restoreEpoch = 1L,
            actionKey = null
        )
        database.scheduleAuthorityDao().insertAuthority(authority)
        return Pair(alarm, authority)
    }

    private fun quickCandidate(
        alarmId: Long,
        triggerTime: Long
    ) = DirectBootQueueItem(
        kind = DirectBootQueueItem.KIND_QUICK,
        alarmId = alarmId,
        triggerTime = triggerTime,
        generation = 1,
        instanceToken = "",
        timeLabel = "Quick",
        playDefaultSound = true,
        vibrationEnabled = true,
        timezonePolicy = Alarm.TIMEZONE_POLICY_LOCAL,
        fixedTimezoneId = "",
        schemaVersion = DirectBootQueueItem.CURRENT_SCHEMA_VERSION
    )

    @Test
    fun scenario01_validNormalProjection_persistsToDeQueueAndReturnsSuccess() = runBlocking {
        seedAlarmAndAuthority(alarmId = 1L, triggerTime = baseTime + 1000L)

        val result = projector.project(now = baseTime)

        assertTrue("Expected Success, was: $result", result is DirectBootNormalProjectionResult.Success)
        val success = result as DirectBootNormalProjectionResult.Success
        assertEquals(1, success.enqueuedNormalCandidates.size)
        assertEquals(1L, success.enqueuedNormalCandidates[0].alarmId)
        assertEquals(1, success.completeQueue.size)

        val storedQueue = DirectBootAlarmCache.readQueue(context, now = baseTime)
        assertEquals(1, storedQueue.size)
        assertEquals(1L, storedQueue[0].alarmId)
        assertEquals(DirectBootQueueItem.KIND_NORMAL, storedQueue[0].kind)
    }

    @Test
    fun scenario02_readyEmpty_clearsNormalEntriesFromQueueAndReturnsSuccess() = runBlocking {
        // First project an active normal alarm
        seedAlarmAndAuthority(alarmId = 1L, triggerTime = baseTime + 1000L)
        val initialResult = projector.project(now = baseTime)
        assertTrue(initialResult is DirectBootNormalProjectionResult.Success)
        assertEquals(1, DirectBootAlarmCache.readQueue(context, now = baseTime).size)

        // Now advance time past the trigger time so that the alarm is in the past
        val pastResult = projector.project(now = baseTime + 2000L)
        assertTrue("Expected Success, was: $pastResult", pastResult is DirectBootNormalProjectionResult.Success)
        val success = pastResult as DirectBootNormalProjectionResult.Success
        assertTrue(success.enqueuedNormalCandidates.isEmpty())
        assertTrue(success.completeQueue.isEmpty())

        val storedState = DirectBootAlarmCache.inspectQueueStorageState(context)
        assertEquals(QueueStorageClassification.VALID_EMPTY, storedState.classification)
    }

    @Test
    fun scenario03_quickOnlyQueue_isPreservedWhenNormalRunsReadyEmpty() = runBlocking {
        // Enqueue a Quick item into the DE queue
        val quick = quickCandidate(alarmId = 999L, triggerTime = baseTime + 5000L)
        val enqueueResult = DirectBootAlarmCache.enqueueCandidate(context, quick, now = baseTime)
        assertTrue(enqueueResult is QueueEnqueueResult.Enqueued)

        // Database has zero active Normal alarms -> Coordinator returns ReadyEmpty
        val projectResult = projector.project(now = baseTime)
        assertTrue("Expected Success, was: $projectResult", projectResult is DirectBootNormalProjectionResult.Success)
        val success = projectResult as DirectBootNormalProjectionResult.Success
        assertTrue(success.enqueuedNormalCandidates.isEmpty())
        assertEquals(1, success.completeQueue.size)
        assertEquals(999L, success.completeQueue[0].alarmId)
        assertEquals(DirectBootQueueItem.KIND_QUICK, success.completeQueue[0].kind)

        // Verify storage still contains the Quick candidate
        val storedQueue = DirectBootAlarmCache.readQueue(context, now = baseTime)
        assertEquals(1, storedQueue.size)
        assertEquals(999L, storedQueue[0].alarmId)
    }

    @Test
    fun scenario04_mixedNormalAndQuickQueue_mergedAndSortedDeterministically() = runBlocking {
        // DE Queue initially has Quick item at +3000ms
        val quick = quickCandidate(alarmId = 200L, triggerTime = baseTime + 3000L)
        DirectBootAlarmCache.enqueueCandidate(context, quick, now = baseTime)

        // Seed Normal alarms at +1000ms and +5000ms
        seedAlarmAndAuthority(alarmId = 100L, triggerTime = baseTime + 1000L)
        seedAlarmAndAuthority(alarmId = 300L, triggerTime = baseTime + 5000L)

        val result = projector.project(now = baseTime)
        assertTrue(result is DirectBootNormalProjectionResult.Success)
        val success = result as DirectBootNormalProjectionResult.Success

        assertEquals(2, success.enqueuedNormalCandidates.size)
        assertEquals(3, success.completeQueue.size)
        assertEquals(listOf(100L, 200L, 300L), success.completeQueue.map { it.alarmId })
        assertEquals(
            listOf(DirectBootQueueItem.KIND_NORMAL, DirectBootQueueItem.KIND_QUICK, DirectBootQueueItem.KIND_NORMAL),
            success.completeQueue.map { it.kind }
        )
    }

    @Test
    fun scenario05_capacity5_withNormalAndQuickCompetition_retainsNearestFiveAndTruncates() = runBlocking {
        // Enqueue 3 Quick items at +2000, +4000, +6000
        DirectBootAlarmCache.enqueueCandidate(context, quickCandidate(20L, baseTime + 2000L), now = baseTime)
        DirectBootAlarmCache.enqueueCandidate(context, quickCandidate(40L, baseTime + 4000L), now = baseTime)
        DirectBootAlarmCache.enqueueCandidate(context, quickCandidate(60L, baseTime + 6000L), now = baseTime)

        // Seed 4 Normal alarms at +1000, +3000, +5000, +7000
        seedAlarmAndAuthority(alarmId = 10L, triggerTime = baseTime + 1000L)
        seedAlarmAndAuthority(alarmId = 30L, triggerTime = baseTime + 3000L)
        seedAlarmAndAuthority(alarmId = 50L, triggerTime = baseTime + 5000L)
        seedAlarmAndAuthority(alarmId = 70L, triggerTime = baseTime + 7000L)

        val result = projector.project(now = baseTime)
        assertTrue(result is DirectBootNormalProjectionResult.Success)
        val success = result as DirectBootNormalProjectionResult.Success

        // Top 5: [10(N), 20(Q), 30(N), 40(Q), 50(N)]
        // Dropped: 60(Q), 70(N)
        assertEquals(5, success.completeQueue.size)
        assertEquals(listOf(10L, 20L, 30L, 40L, 50L), success.completeQueue.map { it.alarmId })

        // Retained normal candidates must only include 10, 30, 50 (70 is dropped and NOT claimed)
        assertEquals(listOf(10L, 30L, 50L), success.enqueuedNormalCandidates.map { it.alarmId })
        assertFalse(success.enqueuedNormalCandidates.any { it.alarmId == 70L })
    }

    @Test
    fun scenario06_sameLogicalIdReplacement_updatesExistingGeneration() = runBlocking {
        // Seed Normal alarm at gen 1
        val (alarm, authority) = seedAlarmAndAuthority(alarmId = 1L, triggerTime = baseTime + 1000L, generation = 1)
        val r1 = projector.project(now = baseTime)
        assertTrue(r1 is DirectBootNormalProjectionResult.Success)
        val q1 = DirectBootAlarmCache.readQueue(context, now = baseTime)
        assertEquals(1, q1[0].generation)

        // Now update the alarm in database to gen 2 and new trigger time
        val updatedAlarm = alarm.copy(
            hour = 8,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = baseTime + 2000L,
            generation = 2,
            revision = 2
        )
        database.alarmDao().update(updatedAlarm)
        database.scheduleAuthorityDao().insertAuthority(
            authority.copy(
                definitionRevision = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(updatedAlarm),
                scheduleRevision = 2L,
                desiredTriggerMillis = baseTime + 2000L
            )
        )

        val r2 = projector.project(now = baseTime)
        assertTrue(r2 is DirectBootNormalProjectionResult.Success)
        val q2 = DirectBootAlarmCache.readQueue(context, now = baseTime)
        assertEquals(1, q2.size)
        assertEquals(2, q2[0].generation)
        assertEquals(baseTime + 2000L, q2[0].triggerTime)
    }

    @Test
    fun scenario07_staleFingerprint_rejectsProjectionWithoutModifyingDe() = runBlocking {
        val (alarm, _) = seedAlarmAndAuthority(alarmId = 1L, triggerTime = baseTime + 1000L)

        // Coordinator coordinates from Snapshot A
        val coordResult = coordinator.coordinate(now = baseTime)
        assertTrue(coordResult is DirectBootNormalCoordinationResult.ReadyWithCandidates)

        // External concurrent mutation occurs before projector enters transaction:
        database.alarmDao().update(alarm.copy(nextTriggerTime = baseTime + 9999L))

        // Projector attempts commit with stale coordination result
        val result = projector.project(coordinationResult = coordResult, now = baseTime)
        assertTrue("Expected FingerprintMismatch, was: $result", result is DirectBootNormalProjectionResult.FingerprintMismatch)
        val mismatch = result as DirectBootNormalProjectionResult.FingerprintMismatch
        assertFalse(mismatch.expectedFingerprint == mismatch.actualFingerprint)

        // DE Queue remains untouched
        val storedQueue = DirectBootAlarmCache.readQueue(context, now = baseTime)
        assertTrue(storedQueue.isEmpty())
    }

    @Test
    fun scenario08_sameGenerationAuthorityChange_rejectsProjectionWithoutModifyingDe() = runBlocking {
        val (_, authority) = seedAlarmAndAuthority(alarmId = 1L, triggerTime = baseTime + 1000L, scheduleRevision = 1L)
        val coordResult = coordinator.coordinate(now = baseTime)
        assertTrue(coordResult is DirectBootNormalCoordinationResult.ReadyWithCandidates)

        // Authority revision bumped in database without changing generation
        database.scheduleAuthorityDao().insertAuthority(
            authority.copy(scheduleRevision = 2L)
        )

        val result = projector.project(coordinationResult = coordResult, now = baseTime)
        assertTrue(result is DirectBootNormalProjectionResult.FingerprintMismatch)
        assertTrue(DirectBootAlarmCache.readQueue(context, now = baseTime).isEmpty())
    }

    @Test
    fun scenario09_disableOrDeleteDuringPlanning_rejectsProjection() = runBlocking {
        seedAlarmAndAuthority(alarmId = 1L, triggerTime = baseTime + 1000L)
        val coordResult = coordinator.coordinate(now = baseTime)
        assertTrue(coordResult is DirectBootNormalCoordinationResult.ReadyWithCandidates)

        // Alarm is deleted
        database.alarmDao().deleteById(1L)

        val result = projector.project(coordinationResult = coordResult, now = baseTime)
        assertTrue(result is DirectBootNormalProjectionResult.FingerprintMismatch)
        assertTrue(DirectBootAlarmCache.readQueue(context, now = baseTime).isEmpty())
    }

    @Test
    fun scenario10_restoreEpochChange_rejectsProjection() = runBlocking {
        seedAlarmAndAuthority(alarmId = 1L, triggerTime = baseTime + 1000L)
        val coordResult = coordinator.coordinate(now = baseTime)
        assertTrue(coordResult is DirectBootNormalCoordinationResult.ReadyWithCandidates)

        // Restore epoch bumped in meta table
        database.scheduleAuthorityDao().insertMeta(
            ScheduleAuthorityMeta(id = 1, restoreEpoch = 2L, bootstrapState = "ENFORCED")
        )

        val result = projector.project(coordinationResult = coordResult, now = baseTime)
        assertTrue(result is DirectBootNormalProjectionResult.FingerprintMismatch)
        assertTrue(DirectBootAlarmCache.readQueue(context, now = baseTime).isEmpty())
    }

    @Test
    fun scenario11_corruptPayload_rejectsWriteAndPreservesRawBytes() = runBlocking {
        seedAlarmAndAuthority(alarmId = 1L, triggerTime = baseTime + 1000L)

        // Inject corrupt JSON into DE SharedPreferences
        val corruptPayload = "{\"schemaVersion\": 2, \"items\": [corrupt_json"
        val prefs = getDePrefs()
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, corruptPayload).commit()

        val result = projector.project(now = baseTime)
        assertTrue("Expected DeWriteRejected, was: $result", result is DirectBootNormalProjectionResult.DeWriteRejected)
        val rejected = result as DirectBootNormalProjectionResult.DeWriteRejected
        assertTrue(rejected.reason is DeRejectionReason.StorageCorrupt)
        assertTrue("Rejection before write guarantees no-write", rejected.isGuaranteedNoWrite)
        assertFalse("DE write was not attempted", rejected.deWriteAttempted)

        // Verify raw bytes are preserved exactly
        val rawAfter = DirectBootAlarmCache.getRawQueuePayload(context)
        assertEquals(corruptPayload, rawAfter)
    }

    @Test
    fun scenario12_unsupportedSchema_rejectsWriteAndPreservesRawBytes() = runBlocking {
        seedAlarmAndAuthority(alarmId = 1L, triggerTime = baseTime + 1000L)

        // Inject unsupported schema payload
        val unsupportedPayload = "{\"schemaVersion\": 99, \"items\": []}"
        val prefs = getDePrefs()
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, unsupportedPayload).commit()

        val result = projector.project(now = baseTime)
        assertTrue(result is DirectBootNormalProjectionResult.DeWriteRejected)
        val rejected = result as DirectBootNormalProjectionResult.DeWriteRejected
        assertTrue(rejected.reason is DeRejectionReason.UnsupportedSchema)
        assertTrue(rejected.isGuaranteedNoWrite)
        assertFalse(rejected.deWriteAttempted)
        val unsupported = rejected.reason as DeRejectionReason.UnsupportedSchema
        assertEquals(99, unsupported.foundVersion)
        assertEquals(DirectBootQueueItem.CURRENT_SCHEMA_VERSION, unsupported.expectedVersion)

        // Verify raw bytes preserved
        assertEquals(unsupportedPayload, DirectBootAlarmCache.getRawQueuePayload(context))
    }

    @Test
    fun scenario13_persistenceFailure_returnsDeCommitFailed() = runBlocking {
        seedAlarmAndAuthority(alarmId = 1L, triggerTime = baseTime + 1000L)

        val failingProjector = DirectBootNormalProjector(
            context = context,
            database = database,
            coordinator = coordinator,
            queueUpdater = { _, _, _ ->
                NormalQueueUpdateResult.PersistenceFailed("Disk I/O error during commit")
            }
        )

        val result = failingProjector.project(now = baseTime)
        assertTrue("Expected DeCommitFailed, was: $result", result is DirectBootNormalProjectionResult.DeCommitFailed)
        val failed = result as DirectBootNormalProjectionResult.DeCommitFailed
        assertEquals("Disk I/O error during commit", failed.details)
        assertTrue("Persistence was attempted", failed.deWriteAttempted)
        assertFalse("Cannot guarantee no-write when commit failed", failed.isGuaranteedNoWrite)
    }

    @Test
    fun scenario14_cancellationBeforeCommit_propagatesCancellationAndLeavesDeUntouched() = runBlocking {
        seedAlarmAndAuthority(alarmId = 1L, triggerTime = baseTime + 1000L)

        val cancelledProjector = DirectBootNormalProjector(
            context = context,
            database = database,
            coordinator = coordinator,
            preWriteHook = {
                throw CancellationException("Cancelled before commit")
            }
        )

        try {
            cancelledProjector.project(now = baseTime)
            fail("Expected CancellationException to be thrown")
        } catch (e: CancellationException) {
            assertEquals("Cancelled before commit", e.message)
        }

        assertTrue(DirectBootAlarmCache.readQueue(context, now = baseTime).isEmpty())
    }

    @Test
    fun scenario15_cancellationAfterWrite_returnsCancellationAfterWrite() = runBlocking {
        seedAlarmAndAuthority(alarmId = 1L, triggerTime = baseTime + 1000L)

        val cancelledAfterProjector = DirectBootNormalProjector(
            context = context,
            database = database,
            coordinator = coordinator,
            postWriteHook = {
                // Simulate cancellation arriving immediately after DE commit confirms
                throw CancellationException("Cancelled after write confirmed")
            }
        )

        val result = cancelledAfterProjector.project(now = baseTime)
        assertTrue("Expected CancellationAfterWrite, was: $result", result is DirectBootNormalProjectionResult.CancellationAfterWrite)
        val after = result as DirectBootNormalProjectionResult.CancellationAfterWrite
        assertEquals("Cancelled after write confirmed", after.cancellation.message)

        // DE Queue DOES contain the persisted alarm because write was confirmed
        val storedQueue = DirectBootAlarmCache.readQueue(context, now = baseTime)
        assertEquals(1, storedQueue.size)
        assertEquals(1L, storedQueue[0].alarmId)
    }

    @Test
    fun scenario16_ambiguousOutcome_whenExceptionOccursAfterDeWrite() = runBlocking {
        seedAlarmAndAuthority(alarmId = 1L, triggerTime = baseTime + 1000L)

        val ambiguousProjector = DirectBootNormalProjector(
            context = context,
            database = database,
            coordinator = coordinator,
            postWriteHook = {
                throw RuntimeException("Simulated SQLite error on commit")
            }
        )

        val result = ambiguousProjector.project(now = baseTime)
        assertTrue("Expected AmbiguousOutcome, was: $result", result is DirectBootNormalProjectionResult.AmbiguousOutcome)
        val ambiguous = result as DirectBootNormalProjectionResult.AmbiguousOutcome
        assertTrue(ambiguous.deWriteConfirmed)
        assertTrue(ambiguous.details.contains("Simulated SQLite error on commit"))
    }

    @Test
    fun scenario17_concurrentRoomMutation_serializesUnderTransaction() = runBlocking(Dispatchers.IO) {
        seedAlarmAndAuthority(alarmId = 1L, triggerTime = baseTime + 1000L)

        // Multiple concurrent projections
        val jobs = (1..5).map {
            async {
                projector.project(now = baseTime)
            }
        }
        val results = jobs.awaitAll()
        assertTrue(results.all { it is DirectBootNormalProjectionResult.Success })

        val storedQueue = DirectBootAlarmCache.readQueue(context, now = baseTime)
        assertEquals(1, storedQueue.size)
        assertEquals(1L, storedQueue[0].alarmId)
    }

    @Test
    fun scenario18_concurrentQueueUpdate_isThreadSafe() = runBlocking(Dispatchers.IO) {
        // Concurrently invoke updateNormalQueue from multiple threads
        val jobs = (1..10).map { i ->
            async {
                val candidate = DirectBootQueueItem(
                    kind = DirectBootQueueItem.KIND_NORMAL,
                    alarmId = i.toLong(),
                    triggerTime = baseTime + (i * 100L),
                    generation = 1,
                    instanceToken = "token_conc_$i",
                    timeLabel = "Time $i",
                    playDefaultSound = true,
                    vibrationEnabled = true,
                    timezonePolicy = Alarm.TIMEZONE_POLICY_LOCAL,
                    fixedTimezoneId = "",
                    schemaVersion = DirectBootQueueItem.CURRENT_SCHEMA_VERSION
                )
                DirectBootAlarmCache.updateNormalQueue(context, listOf(candidate), now = baseTime)
            }
        }
        val results = jobs.awaitAll()
        assertTrue(results.all { it is NormalQueueUpdateResult.Success })

        val storedState = DirectBootAlarmCache.inspectQueueStorageState(context)
        assertTrue(storedState is QueueStorageState.ValidWithItems)
        val items = (storedState as QueueStorageState.ValidWithItems).items
        assertTrue(items.size in 1..5)
    }

    @Test
    fun scenario19_repeatedProjection_isIdempotent() = runBlocking {
        seedAlarmAndAuthority(alarmId = 1L, triggerTime = baseTime + 1000L)

        val r1 = projector.project(now = baseTime)
        val r2 = projector.project(now = baseTime)

        assertTrue(r1 is DirectBootNormalProjectionResult.Success)
        assertTrue(r2 is DirectBootNormalProjectionResult.Success)

        val s1 = r1 as DirectBootNormalProjectionResult.Success
        val s2 = r2 as DirectBootNormalProjectionResult.Success

        assertEquals(s1.completeQueue, s2.completeQueue)
        assertEquals(s1.fingerprint, s2.fingerprint)
    }

    @Test
    fun scenario20_blockedCoordinatorResults_produceZeroDeMutations() = runBlocking {
        val blockedCases: List<DirectBootNormalCoordinationResult> = listOf(
            DirectBootNormalCoordinationResult.CeLocked,
            DirectBootNormalCoordinationResult.CeReadFailure("Sanitized read error"),
            DirectBootNormalCoordinationResult.BlockedUnresolvedAuthority(
                alarmId = 1L,
                reconcileState = "PENDING",
                reason = "Waiting"
            ),
            DirectBootNormalCoordinationResult.BlockedInvalidCoverage(
                result = CoverageResult.INVALID,
                reasonCode = CoverageReasonCode.UNCOVERED_ALARM,
                details = "Coverage missing"
            ),
            DirectBootNormalCoordinationResult.BlockedInvalidEpochOrIdentity("Epoch mismatch"),
            DirectBootNormalCoordinationResult.BlockedInconsistentSnapshot("Inconsistent")
        )

        for (blocked in blockedCases) {
            val result = projector.project(coordinationResult = blocked, now = baseTime)
            assertTrue("Expected CoordinatorBlocked, was: $result", result is DirectBootNormalProjectionResult.CoordinatorBlocked)
            val blockedResult = result as DirectBootNormalProjectionResult.CoordinatorBlocked
            assertEquals(blocked, blockedResult.coordinationResult)

            // DE Queue remains unwritten/empty
            val storedQueue = DirectBootAlarmCache.readQueue(context, now = baseTime)
            assertTrue("DE Queue must remain empty for blocked outcome $blocked", storedQueue.isEmpty())
        }
    }

    @Test
    fun scenario21_v1Keys_remainUnchangedAcrossProjections() = runBlocking {
        // Set V1 scalar keys in DE storage
        val prefs = getDePrefs()
        prefs.edit()
            .putLong("alarm_id", 42L)
            .putLong("trigger_time", baseTime + 9999L)
            .putString("label", "V1 Alarm Label")
            .putString("time_label", "9:00 AM")
            .putBoolean("play_default_sound", true)
            .putBoolean("vibration_enabled", true)
            .commit()

        seedAlarmAndAuthority(alarmId = 1L, triggerTime = baseTime + 1000L)

        val result = projector.project(now = baseTime)
        assertTrue(result is DirectBootNormalProjectionResult.Success)

        // Read back V1 scalar keys
        assertEquals(42L, prefs.getLong("alarm_id", -1L))
        assertEquals(baseTime + 9999L, prefs.getLong("trigger_time", -1L))
        assertEquals("V1 Alarm Label", prefs.getString("label", null))
        assertEquals("9:00 AM", prefs.getString("time_label", null))
        assertTrue(prefs.getBoolean("play_default_sound", false))
        assertTrue(prefs.getBoolean("vibration_enabled", false))
    }

    @Test
    fun scenario22_realConcurrentCeMutation_isBlockedWhileProjectorHoldsRoomTransaction() = runBlocking(Dispatchers.IO) {
        val alarmId = 1L
        val (alarm, authority) = seedAlarmAndAuthority(alarmId = alarmId, triggerTime = baseTime + 1000L)

        val events = CopyOnWriteArrayList<String>()
        val projectorVerified = CompletableDeferred<Unit>()
        val mutationAttemptStarted = CompletableDeferred<Unit>()
        val releaseProjectorTransaction = CompletableDeferred<Unit>()

        val concurrentProjector = DirectBootNormalProjector(
            context = context,
            database = database,
            coordinator = coordinator,
            preWriteHook = {
                events.add("PROJECTOR_VERIFIED_FINGERPRINT")
                projectorVerified.complete(Unit)
                withTimeout(2000L) { mutationAttemptStarted.await() }
            },
            queueUpdater = { ctx, items, now ->
                events.add("DE_QUEUE_UPDATE_INVOKED")
                val updateResult = DirectBootAlarmCache.updateNormalQueue(ctx, items, now)
                events.add("DE_QUEUE_UPDATE_COMPLETED")
                assertFalse("Concurrent CE mutation must NOT have committed when DE update completes", events.contains("MUTATION_COMMITTED"))
                updateResult
            },
            postWriteHook = {
                events.add("PROJECTOR_HOLDING_TRANSACTION_POST_WRITE")
                withTimeout(2000L) { releaseProjectorTransaction.await() }
                events.add("PROJECTOR_RELEASING_TRANSACTION")
            }
        )

        val projectorJob = launch(Dispatchers.IO) {
            val result = concurrentProjector.project(now = baseTime)
            assertTrue("Projector should succeed, was: $result", result is DirectBootNormalProjectionResult.Success)
            events.add("PROJECTOR_FINISHED")
        }

        withTimeout(2000L) { projectorVerified.await() }

        val mutationJob = launch(Dispatchers.IO) {
            events.add("MUTATION_CALLING_UPDATE")
            mutationAttemptStarted.complete(Unit)
            database.withTransaction {
                database.alarmDao().update(alarm.copy(isEnabled = false))
                database.scheduleAuthorityDao().insertAuthority(
                    authority.copy(desiredOperation = "CANCEL", desiredTriggerMillis = null)
                )
            }
            events.add("MUTATION_COMMITTED")
        }

        withTimeout(2000L) { mutationAttemptStarted.await() }

        // Bounded delay to verify mutation is blocked while Room transaction is held
        delay(150L)
        assertFalse("Mutation job must NOT complete while Projector holds Room transaction", mutationJob.isCompleted)
        assertFalse("MUTATION_COMMITTED event must NOT have occurred yet", events.contains("MUTATION_COMMITTED"))

        releaseProjectorTransaction.complete(Unit)

        withTimeout(2000L) { projectorJob.join() }
        events.add("AFTER_PROJECTOR_JOIN")

        withTimeout(2000L) { mutationJob.join() }
        assertTrue("Mutation job must complete after Projector releases transaction", mutationJob.isCompleted)
        assertTrue("MUTATION_COMMITTED event must have occurred", events.contains("MUTATION_COMMITTED"))

        val idxDeUpdate = events.indexOf("DE_QUEUE_UPDATE_COMPLETED")
        val idxProjectorRelease = events.indexOf("PROJECTOR_RELEASING_TRANSACTION")
        val idxMutationCommitted = events.indexOf("MUTATION_COMMITTED")
        assertTrue("DE update must occur before mutation commits", idxDeUpdate < idxMutationCommitted)
        assertTrue("Projector must release transaction before mutation commits", idxProjectorRelease < idxMutationCommitted)

        val disabledAlarm = database.alarmDao().getById(alarmId)
        assertNotNull(disabledAlarm)
        assertFalse("Alarm must be disabled after mutation commits", disabledAlarm!!.isEnabled)

        // Subsequent fresh projection reconciles new canonical CE state
        val freshResult = projector.project(now = baseTime)
        assertTrue("Fresh projection should succeed, was: $freshResult", freshResult is DirectBootNormalProjectionResult.Success)
        val freshSuccess = freshResult as DirectBootNormalProjectionResult.Success
        assertTrue("Disabled alarm results in empty canonical Normal queue", freshSuccess.enqueuedNormalCandidates.isEmpty())
        assertTrue("Stored queue must be empty after reconciliation", DirectBootAlarmCache.readQueue(context, now = baseTime).isEmpty())
    }

    @Test
    fun scenario23_forgedCandidate_rejectedUnderProtectedBoundary() = runBlocking {
        seedAlarmAndAuthority(alarmId = 1L, triggerTime = baseTime + 1000L)

        val coordResult = coordinator.coordinate(now = baseTime)
        assertTrue(coordResult is DirectBootNormalCoordinationResult.ReadyWithCandidates)

        val forgedCandidate = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 999L,
            triggerTime = baseTime + 1000L,
            generation = 1,
            instanceToken = "token_forged",
            timeLabel = "Forged",
            playDefaultSound = true,
            vibrationEnabled = true,
            timezonePolicy = Alarm.TIMEZONE_POLICY_LOCAL,
            fixedTimezoneId = "",
            schemaVersion = DirectBootQueueItem.CURRENT_SCHEMA_VERSION
        )

        val forgedResult = (coordResult as DirectBootNormalCoordinationResult.ReadyWithCandidates).copy(
            candidates = listOf(forgedCandidate)
        )

        val result = projector.project(coordinationResult = forgedResult, now = baseTime)
        assertTrue("Expected DeWriteRejected, was: $result", result is DirectBootNormalProjectionResult.DeWriteRejected)
        val rejected = result as DirectBootNormalProjectionResult.DeWriteRejected
        assertTrue(rejected.reason is DeRejectionReason.ProvenanceViolation)
        assertTrue("Rejection before write guarantees no-write", rejected.isGuaranteedNoWrite)
        assertFalse("Write was not attempted", rejected.deWriteAttempted)

        // DE Queue remains untouched
        assertTrue(DirectBootAlarmCache.readQueue(context, now = baseTime).isEmpty())
    }

    @Test
    fun scenario24_mutatedCandidate_rejectedUnderProtectedBoundary() = runBlocking {
        seedAlarmAndAuthority(alarmId = 1L, triggerTime = baseTime + 1000L)

        val coordResult = coordinator.coordinate(now = baseTime)
        assertTrue(coordResult is DirectBootNormalCoordinationResult.ReadyWithCandidates)
        val validCandidate = (coordResult as DirectBootNormalCoordinationResult.ReadyWithCandidates).candidates.first()

        // Caller mutates trigger time on the candidate
        val mutatedCandidate = validCandidate.copy(triggerTime = baseTime + 5555L)
        val mutatedResult = coordResult.copy(candidates = listOf(mutatedCandidate))

        val result = projector.project(coordinationResult = mutatedResult, now = baseTime)
        assertTrue("Expected DeWriteRejected, was: $result", result is DirectBootNormalProjectionResult.DeWriteRejected)
        val rejected = result as DirectBootNormalProjectionResult.DeWriteRejected
        assertTrue(rejected.reason is DeRejectionReason.ProvenanceViolation)
        assertTrue(rejected.isGuaranteedNoWrite)
        assertFalse(rejected.deWriteAttempted)

        assertTrue(DirectBootAlarmCache.readQueue(context, now = baseTime).isEmpty())
    }

    @Test
    fun scenario25_expiredCandidate_rejectedUnderProtectedBoundary() = runBlocking {
        seedAlarmAndAuthority(alarmId = 1L, triggerTime = baseTime + 1000L)

        val coordResult = coordinator.coordinate(now = baseTime)
        assertTrue(coordResult is DirectBootNormalCoordinationResult.ReadyWithCandidates)
        val validCandidate = (coordResult as DirectBootNormalCoordinationResult.ReadyWithCandidates).candidates.first()

        // Passed candidate with trigger in past relative to evaluated now
        val expiredCandidate = validCandidate.copy(triggerTime = baseTime - 1000L)
        val expiredResult = coordResult.copy(candidates = listOf(expiredCandidate))

        val result = projector.project(coordinationResult = expiredResult, now = baseTime)
        assertTrue("Expected DeWriteRejected, was: $result", result is DirectBootNormalProjectionResult.DeWriteRejected)
        val rejected = result as DirectBootNormalProjectionResult.DeWriteRejected
        assertTrue(rejected.reason is DeRejectionReason.ProvenanceViolation)
        assertTrue(rejected.isGuaranteedNoWrite)
        assertFalse(rejected.deWriteAttempted)

        assertTrue(DirectBootAlarmCache.readQueue(context, now = baseTime).isEmpty())
    }

    @Test
    fun scenario26_preWriteException_returnsPreWriteFailureWithGuaranteedNoWrite() = runBlocking {
        seedAlarmAndAuthority(alarmId = 1L, triggerTime = baseTime + 1000L)

        val failingProjector = DirectBootNormalProjector(
            context = context,
            database = database,
            coordinator = coordinator,
            preWriteHook = {
                throw RuntimeException("Simulated SQLite failure before DE write")
            }
        )

        val result = failingProjector.project(now = baseTime)
        assertTrue("Expected PreWriteFailure, was: $result", result is DirectBootNormalProjectionResult.PreWriteFailure)
        val failure = result as DirectBootNormalProjectionResult.PreWriteFailure
        assertTrue("Pre-write failure guarantees no-write", failure.isGuaranteedNoWrite)
        assertFalse("Write was not attempted", failure.deWriteAttempted)
        assertTrue(failure.details.contains("Simulated SQLite failure before DE write"))

        assertTrue("DE queue must remain untouched", DirectBootAlarmCache.readQueue(context, now = baseTime).isEmpty())
    }
}
