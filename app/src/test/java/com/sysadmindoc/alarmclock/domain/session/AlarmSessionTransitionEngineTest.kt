package com.sysadmindoc.alarmclock.domain.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.AlarmSessionEntity
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.repository.AlarmSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AlarmSessionTransitionEngineTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var db: AlarmDatabase
    private lateinit var repository: AlarmSessionRepository
    private lateinit var engine: AlarmSessionTransitionEngine

    private val nowMs = 1793796000000L // Wed Nov 4 2026 06:00:00 AM EDT
    private val elapsedMs = 1000000L
    private val bootCount = 1L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath("test_session_transition_engine.db")
        if (dbFile.exists()) dbFile.delete()
        dbFile.parentFile?.mkdirs()

        db = Room.databaseBuilder(context, AlarmDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()

        repository = AlarmSessionRepository(db.alarmSessionDao())
        engine = AlarmSessionTransitionEngine(repository)
    }

    @After
    fun tearDown() {
        try { db.close() } catch (_: Exception) {}
        if (dbFile.exists()) dbFile.delete()
    }

    private suspend fun admitNormalSession(
        alarmId: Long = 100L,
        wakeConfirm: Boolean = false,
        maxSnoozes: Int = 3
    ): AlarmSessionEntity {
        val alarm = Alarm(
            id = alarmId,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            kind = Alarm.KIND_NORMAL,
            snoozeDurationMinutes = 10,
            maxSnoozeCount = maxSnoozes,
            wakeConfirmEnabled = wakeConfirm,
            wakeConfirmDelayMinutes = 10
        )
        val settings = AppSettings(autoSilenceMinutes = 15)

        val outcome = engine.admitSession(
            AlarmSessionEvent.AdmitSession(
                alarm = alarm,
                settings = settings,
                effectiveNormalAutoSilenceMinutes = 15,
                fireId = "fire_$alarmId",
                occurrenceKey = "occ_$alarmId",
                canonicalBaseTriggerMs = nowMs,
                scheduledTargetEpochMs = nowMs,
                nowEpochMs = nowMs,
                elapsedRealtimeMs = elapsedMs,
                bootCount = bootCount
            )
        )
        assertTrue(outcome is SessionTransitionOutcome.Success)
        return (outcome as SessionTransitionOutcome.Success).session
    }

    // T01: committed transition increments version once
    @Test
    fun T01_committedTransitionIncrementsVersionOnce() = runBlocking {
        val session = admitNormalSession()
        assertEquals(1L, session.sessionVersion)
        assertEquals(AlarmSessionState.FIRING.name, session.sessionState)

        val outcome = engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.Snooze(nowMs + 1000L),
            expectedVersion = session.sessionVersion,
            expectedState = session.sessionState,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L,
            currentBootCount = bootCount
        )

        assertTrue(outcome is SessionTransitionOutcome.Success)
        val updated = (outcome as SessionTransitionOutcome.Success).session
        assertEquals(2L, updated.sessionVersion)
        assertEquals(AlarmSessionState.SNOOZED.name, updated.sessionState)
        assertEquals(1, updated.snoozeCount)
    }

    // T02: duplicate event produces no second mutation
    @Test
    fun T02_duplicateEventProducesNoSecondMutation() = runBlocking {
        val session = admitNormalSession()
        val snoozeOutcome = engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.Snooze(nowMs + 1000L),
            expectedVersion = session.sessionVersion,
            expectedState = session.sessionState,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L,
            currentBootCount = bootCount
        )
        assertTrue(snoozeOutcome is SessionTransitionOutcome.Success)

        // Resend Snooze event with stale expected version 1
        val duplicateOutcome = engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.Snooze(nowMs + 2000L),
            expectedVersion = 1L,
            expectedState = AlarmSessionState.FIRING.name,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + 2000L,
            currentElapsedRealtimeMs = elapsedMs + 2000L,
            currentBootCount = bootCount
        )

        assertTrue(duplicateOutcome is SessionTransitionOutcome.StaleEventRejected)
    }

    // T03: stale expected version rejected
    @Test
    fun T03_staleExpectedVersionRejected() = runBlocking {
        val session = admitNormalSession()

        val staleOutcome = engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.Stop(nowMs + 1000L),
            expectedVersion = 99L, // Mismatched version
            expectedState = session.sessionState,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L,
            currentBootCount = bootCount
        )

        assertTrue(staleOutcome is SessionTransitionOutcome.ConflictRejected)
    }

    // T04: old occurrence/generation rejected
    @Test
    fun T04_oldOccurrenceGenerationRejected() = runBlocking {
        val session = admitNormalSession()

        val generationOutcome = engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.Stop(nowMs + 1000L),
            expectedVersion = session.sessionVersion,
            expectedState = session.sessionState,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation + 1, // Mismatched generation
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L,
            currentBootCount = bootCount
        )

        assertTrue(generationOutcome is SessionTransitionOutcome.ConflictRejected)
    }

    // T05 (Reworked for fail-closed AutoEnd boundary): Concurrent Stop vs Unverified AutoEnd
    // Original T05 (eligible AutoEnd race) is DEFERRED until authoritative active-time accounting exists.
    @Test
    fun T05_concurrentStopVsUnverifiedAutoEnd_StopWinsAndAutoEndFailsClosed() = runBlocking {
        // --- 1. Concurrent execution: either Stop or AutoEnd evaluates first ---
        val session = admitNormalSession()

        val stopDeferred = async(Dispatchers.IO) {
            engine.transitionSession(
                sessionId = session.sessionId,
                event = AlarmSessionEvent.Stop(nowMs + 1000L),
                expectedVersion = session.sessionVersion,
                expectedState = session.sessionState,
                expectedAdmissionKey = session.admissionKey,
                expectedGeneration = session.generation,
                expectedRevision = session.revision,
                currentWallClockMs = nowMs + 1000L,
                currentElapsedRealtimeMs = elapsedMs + 1000L,
                currentBootCount = bootCount
            )
        }

        val autoEndDeferred = async(Dispatchers.IO) {
            engine.transitionSession(
                sessionId = session.sessionId,
                event = AlarmSessionEvent.AutoEndTimeout(nowMs + (15 * 60_000L)),
                expectedVersion = session.sessionVersion,
                expectedState = session.sessionState,
                expectedAdmissionKey = session.admissionKey,
                expectedGeneration = session.generation,
                expectedRevision = session.revision,
                currentWallClockMs = nowMs + (15 * 60_000L),
                currentElapsedRealtimeMs = elapsedMs + (15 * 60_000L),
                currentBootCount = bootCount
            )
        }

        val results = listOf(stopDeferred, autoEndDeferred).awaitAll()
        val stopResult = results.firstOrNull { it is SessionTransitionOutcome.Success }
        assertNotNull("Stop must succeed once in concurrent execution", stopResult)
        val successSession = (stopResult as SessionTransitionOutcome.Success).session
        assertEquals("COMPLETED", successSession.sessionState)
        assertEquals("DISMISSED", successSession.terminalReason)

        val autoEndResult = results.single { it !== stopResult }
        // Depending on race order:
        // Order A (AutoEnd precedes Stop): AutoEnd fails closed with StaleEventRejected("AUTO_END_UNVERIFIED_PERSISTENCE_BOUNDARY")
        // Order B (Stop commits first): AutoEnd observes completed session and returns TerminalImmutable("DISMISSED")
        when (autoEndResult) {
            is SessionTransitionOutcome.StaleEventRejected -> {
                assertEquals("AUTO_END_UNVERIFIED_PERSISTENCE_BOUNDARY", autoEndResult.reason)
            }
            is SessionTransitionOutcome.TerminalImmutable -> {
                assertEquals("DISMISSED", autoEndResult.terminalReason)
            }
            else -> {
                org.junit.Assert.fail("Unexpected outcome for unverified AutoEnd: $autoEndResult")
            }
        }

        val persisted = repository.getSessionById(session.sessionId)
        assertNotNull("Persisted session must exist", persisted)
        assertEquals("COMPLETED", persisted!!.sessionState)
        assertEquals("DISMISSED", persisted.terminalReason)
        assertEquals(session.sessionVersion + 1L, persisted.sessionVersion)

        // --- 2. Deterministic Interleaving 1: AutoEnd strictly precedes Stop ---
        val session1 = admitNormalSession(alarmId = 101L)
        val autoEndFirst = engine.transitionSession(
            sessionId = session1.sessionId,
            event = AlarmSessionEvent.AutoEndTimeout(nowMs + (15 * 60_000L)),
            expectedVersion = session1.sessionVersion,
            expectedState = session1.sessionState,
            expectedAdmissionKey = session1.admissionKey,
            expectedGeneration = session1.generation,
            expectedRevision = session1.revision,
            currentWallClockMs = nowMs + (15 * 60_000L),
            currentElapsedRealtimeMs = elapsedMs + (15 * 60_000L),
            currentBootCount = bootCount
        )
        assertTrue("AutoEnd must fail closed when evaluated before Stop", autoEndFirst is SessionTransitionOutcome.StaleEventRejected)
        assertEquals("AUTO_END_UNVERIFIED_PERSISTENCE_BOUNDARY", (autoEndFirst as SessionTransitionOutcome.StaleEventRejected).reason)

        // Ensure AutoEnd did not independently terminate or mutate session
        val sessionAfterAutoEnd = repository.getSessionById(session1.sessionId)
        assertNotNull(sessionAfterAutoEnd)
        assertEquals("FIRING", sessionAfterAutoEnd!!.sessionState)
        assertEquals(session1.sessionVersion, sessionAfterAutoEnd.sessionVersion)

        val stopAfterAutoEnd = engine.transitionSession(
            sessionId = session1.sessionId,
            event = AlarmSessionEvent.Stop(nowMs + 1000L),
            expectedVersion = session1.sessionVersion,
            expectedState = session1.sessionState,
            expectedAdmissionKey = session1.admissionKey,
            expectedGeneration = session1.generation,
            expectedRevision = session1.revision,
            currentWallClockMs = nowMs + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L,
            currentBootCount = bootCount
        )
        assertTrue("Stop must succeed after unverified AutoEnd rejection", stopAfterAutoEnd is SessionTransitionOutcome.Success)
        val persisted1 = repository.getSessionById(session1.sessionId)
        assertNotNull(persisted1)
        assertEquals("COMPLETED", persisted1!!.sessionState)
        assertEquals("DISMISSED", persisted1.terminalReason)
        assertEquals(session1.sessionVersion + 1L, persisted1.sessionVersion)

        // --- 3. Deterministic Interleaving 2: Stop strictly precedes AutoEnd ---
        val session2 = admitNormalSession(alarmId = 102L)
        val stopFirst = engine.transitionSession(
            sessionId = session2.sessionId,
            event = AlarmSessionEvent.Stop(nowMs + 1000L),
            expectedVersion = session2.sessionVersion,
            expectedState = session2.sessionState,
            expectedAdmissionKey = session2.admissionKey,
            expectedGeneration = session2.generation,
            expectedRevision = session2.revision,
            currentWallClockMs = nowMs + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L,
            currentBootCount = bootCount
        )
        assertTrue("Stop must succeed first", stopFirst is SessionTransitionOutcome.Success)

        val autoEndAfterStop = engine.transitionSession(
            sessionId = session2.sessionId,
            event = AlarmSessionEvent.AutoEndTimeout(nowMs + (15 * 60_000L)),
            expectedVersion = session2.sessionVersion,
            expectedState = session2.sessionState,
            expectedAdmissionKey = session2.admissionKey,
            expectedGeneration = session2.generation,
            expectedRevision = session2.revision,
            currentWallClockMs = nowMs + (15 * 60_000L),
            currentElapsedRealtimeMs = elapsedMs + (15 * 60_000L),
            currentBootCount = bootCount
        )
        assertTrue("AutoEnd after completed Stop must return TerminalImmutable", autoEndAfterStop is SessionTransitionOutcome.TerminalImmutable)
        assertEquals("DISMISSED", (autoEndAfterStop as SessionTransitionOutcome.TerminalImmutable).terminalReason)

        val persisted2 = repository.getSessionById(session2.sessionId)
        assertNotNull(persisted2)
        assertEquals("COMPLETED", persisted2!!.sessionState)
        assertEquals("DISMISSED", persisted2.terminalReason)
        assertEquals(session2.sessionVersion + 1L, persisted2.sessionVersion)
    }

    // T06: COMPLETED absorbing and immutable
    @Test
    fun T06_completedAbsorbingAndImmutable() = runBlocking {
        val session = admitNormalSession()
        val stopOutcome = engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.Stop(nowMs + 1000L),
            expectedVersion = session.sessionVersion,
            expectedState = session.sessionState,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L,
            currentBootCount = bootCount
        )
        assertTrue(stopOutcome is SessionTransitionOutcome.Success)

        val immutableOutcome = engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.Snooze(nowMs + 2000L),
            expectedVersion = 2L,
            expectedState = AlarmSessionState.COMPLETED.name,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + 2000L,
            currentElapsedRealtimeMs = elapsedMs + 2000L,
            currentBootCount = bootCount
        )

        assertTrue(immutableOutcome is SessionTransitionOutcome.TerminalImmutable)
    }

    // T07: stale SnoozeRefire after Stop rejected
    @Test
    fun T07_staleSnoozeRefireAfterStopRejected() = runBlocking {
        val session = admitNormalSession()
        engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.Stop(nowMs + 1000L),
            expectedVersion = session.sessionVersion,
            expectedState = session.sessionState,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L,
            currentBootCount = bootCount
        )

        val refireOutcome = engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.SnoozeRefire(nowMs + 2000L),
            expectedVersion = 2L,
            expectedState = AlarmSessionState.COMPLETED.name,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + 2000L,
            currentElapsedRealtimeMs = elapsedMs + 2000L,
            currentBootCount = bootCount
        )

        assertTrue(refireOutcome is SessionTransitionOutcome.TerminalImmutable)
    }

    // T08: repository write failure preserves durable state
    @Test
    fun T08_repositoryWriteFailurePreservesDurableState() = runBlocking {
        val session = admitNormalSession()
        val failingRepo = io.mockk.mockk<AlarmSessionRepository>(relaxed = true)
        io.mockk.coEvery { failingRepo.getSessionById(session.sessionId) } returns session
        io.mockk.coEvery { failingRepo.casUpdateSessionState(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("DB_WRITE_FAILURE")

        val failingEngine = AlarmSessionTransitionEngine(failingRepo)

        val writeFailedOutcome = failingEngine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.Stop(nowMs + 1000L),
            expectedVersion = session.sessionVersion,
            expectedState = session.sessionState,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L,
            currentBootCount = bootCount
        )

        assertTrue(writeFailedOutcome is SessionTransitionOutcome.RepositoryWriteFailed)
    }

    // T09: WakeUp vs timeout first-commit-wins
    @Test
    fun T09_wakeUpVsTimeoutFirstCommitWins() = runBlocking {
        val session = admitNormalSession(wakeConfirm = true)
        // Transition to WAKE_CHECK_WAITING
        engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.Stop(nowMs + 1000L),
            expectedVersion = session.sessionVersion,
            expectedState = session.sessionState,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L,
            currentBootCount = bootCount
        )

        val checkAt = nowMs + 1000L + (10 * 60_000L)
        // Activate Wake Check at checkAt
        engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.ActivateWakeCheck(checkAt),
            expectedVersion = 2L,
            expectedState = AlarmSessionState.WAKE_CHECK_WAITING.name,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = checkAt,
            currentElapsedRealtimeMs = elapsedMs + (10 * 60_000L),
            currentBootCount = bootCount
        )

        val activeTime = checkAt + 300_000L // 5 min active window
        val passDeferred = async(Dispatchers.IO) {
            engine.transitionSession(
                sessionId = session.sessionId,
                event = AlarmSessionEvent.WakeCheckPassed(activeTime),
                expectedVersion = 3L,
                expectedState = AlarmSessionState.WAKE_CHECK_ACTIVE.name,
                expectedAdmissionKey = session.admissionKey,
                expectedGeneration = session.generation,
                expectedRevision = session.revision,
                currentWallClockMs = activeTime,
                currentElapsedRealtimeMs = elapsedMs + (10 * 60_000L) + 300_000L,
                currentBootCount = bootCount
            )
        }

        val timeoutDeferred = async(Dispatchers.IO) {
            engine.transitionSession(
                sessionId = session.sessionId,
                event = AlarmSessionEvent.WakeCheckTimeout(activeTime),
                expectedVersion = 3L,
                expectedState = AlarmSessionState.WAKE_CHECK_ACTIVE.name,
                expectedAdmissionKey = session.admissionKey,
                expectedGeneration = session.generation,
                expectedRevision = session.revision,
                currentWallClockMs = activeTime,
                currentElapsedRealtimeMs = elapsedMs + (10 * 60_000L) + 300_000L,
                currentBootCount = bootCount
            )
        }

        val results = listOf(passDeferred, timeoutDeferred).awaitAll()
        val successCount = results.count { it is SessionTransitionOutcome.Success }
        assertEquals(1, successCount)
    }

    // T10: logical Background WAITING->ACTIVE: no UI/audio execution intent
    @Test
    fun T10_backgroundWakeCheckEmitsNoUiAudioIntent() = runBlocking {
        val session = admitNormalSession(wakeConfirm = true)
        val stopOutcome = engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.Stop(nowMs + 1000L),
            expectedVersion = session.sessionVersion,
            expectedState = session.sessionState,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L,
            currentBootCount = bootCount
        )

        assertTrue(stopOutcome is SessionTransitionOutcome.Success)
        val intent = (stopOutcome as SessionTransitionOutcome.Success).postCommitIntent
        assertTrue(intent is PostCommitIntent.ScheduleWakeCheck)
    }

    // T11: Quick Snooze/Challenge/WakeCheck rejected
    @Test
    fun T11_quickSnoozeChallengeWakeCheckRejected() = runBlocking {
        val quickAlarm = Alarm(
            id = 202L,
            kind = "QUICK",
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            quickTriggerEpochMs = nowMs + 300000L
        )
        val settings = AppSettings(autoSilenceMinutes = 15)

        val outcome = engine.admitSession(
            AlarmSessionEvent.AdmitSession(
                alarm = quickAlarm,
                settings = settings,
                fireId = "fire_quick",
                occurrenceKey = "occ_quick",
                canonicalBaseTriggerMs = nowMs,
                scheduledTargetEpochMs = nowMs,
                nowEpochMs = nowMs,
                elapsedRealtimeMs = elapsedMs,
                bootCount = bootCount
            )
        )
        assertTrue(outcome is SessionTransitionOutcome.Success)
        val quickSession = (outcome as SessionTransitionOutcome.Success).session

        val snoozeAttempt = engine.transitionSession(
            sessionId = quickSession.sessionId,
            event = AlarmSessionEvent.Snooze(nowMs + 1000L),
            expectedVersion = quickSession.sessionVersion,
            expectedState = quickSession.sessionState,
            expectedAdmissionKey = quickSession.admissionKey,
            expectedGeneration = quickSession.generation,
            expectedRevision = quickSession.revision,
            currentWallClockMs = nowMs + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L,
            currentBootCount = bootCount
        )

        assertTrue(snoozeAttempt is SessionTransitionOutcome.InvalidStateTransition)
    }

    // T12: 5h monotonic/boot/rollback fail-closed
    @Test
    fun T12_fiveHourMonotonicBootRollbackFailClosed() = runBlocking {
        val session = admitNormalSession()

        // Boot count mismatch
        val expiredBootOutcome = engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.Stop(nowMs + 1000L),
            expectedVersion = session.sessionVersion,
            expectedState = session.sessionState,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L,
            currentBootCount = bootCount + 1L // Reboot!
        )

        assertTrue(expiredBootOutcome is SessionTransitionOutcome.Success)
        val expiredSession = (expiredBootOutcome as SessionTransitionOutcome.Success).session
        assertEquals(AlarmSessionState.COMPLETED.name, expiredSession.sessionState)
        assertEquals("ABSOLUTE_TIMEOUT", expiredSession.terminalReason)
    }

    // T13: AutoEnd cannot complete WAITING
    @Test
    fun T13_autoEndCannotCompleteWaiting() = runBlocking {
        val session = admitNormalSession(wakeConfirm = true)
        engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.Stop(nowMs + 1000L),
            expectedVersion = session.sessionVersion,
            expectedState = session.sessionState,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L,
            currentBootCount = bootCount
        )

        val autoEndInWaiting = engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.AutoEndTimeout(nowMs + (15 * 60_000L)),
            expectedVersion = 2L,
            expectedState = AlarmSessionState.WAKE_CHECK_WAITING.name,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + (15 * 60_000L),
            currentElapsedRealtimeMs = elapsedMs + (15 * 60_000L),
            currentBootCount = bootCount
        )

        assertTrue(autoEndInWaiting is SessionTransitionOutcome.InvalidStateTransition)
    }

    // T14: Snooze cap does not complete Session
    @Test
    fun T14_snoozeCapDoesNotCompleteSession() = runBlocking {
        val session = admitNormalSession(maxSnoozes = 1)

        // First snooze succeeds
        engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.Snooze(nowMs + 1000L),
            expectedVersion = session.sessionVersion,
            expectedState = session.sessionState,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L,
            currentBootCount = bootCount
        )

        val refireTime = nowMs + 1000L + (10 * 60_000L)
        // Refire to FIRING
        engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.SnoozeRefire(refireTime),
            expectedVersion = 2L,
            expectedState = AlarmSessionState.SNOOZED.name,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = refireTime,
            currentElapsedRealtimeMs = elapsedMs + 1000L + (10 * 60_000L),
            currentBootCount = bootCount
        )

        // Second snooze attempted at cap (maxSnoozes = 1)
        val capOutcome = engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.Snooze(refireTime + 1000L),
            expectedVersion = 3L,
            expectedState = AlarmSessionState.FIRING.name,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = refireTime + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L + (10 * 60_000L) + 1000L,
            currentBootCount = bootCount
        )

        assertTrue(capOutcome is SessionTransitionOutcome.SnoozeCapExceeded)
        val reRead = repository.getSessionById(session.sessionId)!!
        assertEquals(AlarmSessionState.FIRING.name, reRead.sessionState) // Session remains FIRING!
    }

    // T15: separate active alarms remain independent
    @Test
    fun T15_separateActiveAlarmsRemainIndependent() = runBlocking {
        val sessionA = admitNormalSession(alarmId = 101L)
        val sessionB = admitNormalSession(alarmId = 202L)

        engine.transitionSession(
            sessionId = sessionA.sessionId,
            event = AlarmSessionEvent.Stop(nowMs + 1000L),
            expectedVersion = sessionA.sessionVersion,
            expectedState = sessionA.sessionState,
            expectedAdmissionKey = sessionA.admissionKey,
            expectedGeneration = sessionA.generation,
            expectedRevision = sessionA.revision,
            currentWallClockMs = nowMs + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L,
            currentBootCount = bootCount
        )

        val freshB = repository.getSessionById(sessionB.sessionId)!!
        assertEquals(AlarmSessionState.FIRING.name, freshB.sessionState)
        assertEquals(1L, freshB.sessionVersion)
    }

    // T16: Snapshot semantics immutable after admission
    @Test
    fun T16_snapshotSemanticsImmutableAfterAdmission() = runBlocking {
        val session = admitNormalSession()
        val initialSnapshotJson = session.configurationSnapshotJson

        engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.Snooze(nowMs + 1000L),
            expectedVersion = session.sessionVersion,
            expectedState = session.sessionState,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L,
            currentBootCount = bootCount
        )

        val freshSession = repository.getSessionById(session.sessionId)!!
        assertEquals(initialSnapshotJson, freshSession.configurationSnapshotJson)
    }

    // --- ADDITIONAL FOCUSED REGRESSION TESTS FOR DEFECT REMEDIES ---

    @Test
    fun normalAutoEnd15MinAndAbsoluteTimeout5Hours() = runBlocking {
        val alarm = Alarm(id = 501L, kind = Alarm.KIND_NORMAL)
        val settings = AppSettings(autoSilenceMinutes = 15)

        val outcome = engine.admitSession(
            AlarmSessionEvent.AdmitSession(
                alarm = alarm,
                settings = settings,
                effectiveNormalAutoSilenceMinutes = 15,
                fireId = "fire_501",
                occurrenceKey = "occ_501",
                canonicalBaseTriggerMs = nowMs,
                scheduledTargetEpochMs = nowMs,
                nowEpochMs = nowMs,
                elapsedRealtimeMs = elapsedMs,
                bootCount = bootCount
            )
        )
        assertTrue(outcome is SessionTransitionOutcome.Success)
        val session = (outcome as SessionTransitionOutcome.Success).session
        assertEquals(nowMs + 18_000_000L, session.absoluteDeadlineEpochMs)
    }

    @Test
    fun quickAutoEnd10MinAndAbsoluteTimeout5Hours() = runBlocking {
        val quickAlarm = Alarm(id = 502L, kind = "QUICK", quickTriggerEpochMs = nowMs + 1000L)
        val settings = AppSettings(autoSilenceMinutes = 10)

        val outcome = engine.admitSession(
            AlarmSessionEvent.AdmitSession(
                alarm = quickAlarm,
                settings = settings,
                fireId = "fire_502",
                occurrenceKey = "occ_502",
                canonicalBaseTriggerMs = nowMs,
                scheduledTargetEpochMs = nowMs,
                nowEpochMs = nowMs,
                elapsedRealtimeMs = elapsedMs,
                bootCount = bootCount
            )
        )
        assertTrue(outcome is SessionTransitionOutcome.Success)
        val session = (outcome as SessionTransitionOutcome.Success).session
        assertEquals(nowMs + 18_000_000L, session.absoluteDeadlineEpochMs)
    }

    @Test
    fun autoEndFailsClosedWithoutAuthoritativePersistenceProof() = runBlocking {
        val session = admitNormalSession()

        // Unverified AutoEndTimeout fails closed as StaleEventRejected
        val autoEndOutcome = engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.AutoEndTimeout(nowMs + (15 * 60_000L)),
            expectedVersion = session.sessionVersion,
            expectedState = session.sessionState,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + (15 * 60_000L),
            currentElapsedRealtimeMs = elapsedMs + (15 * 60_000L),
            currentBootCount = bootCount
        )

        assertTrue(autoEndOutcome is SessionTransitionOutcome.StaleEventRejected)
        val reRead = repository.getSessionById(session.sessionId)!!
        assertEquals(AlarmSessionState.FIRING.name, reRead.sessionState) // Session remains FIRING!
    }

    @Test
    fun prematureSnoozeRefireRejectedBeforeRefireEpochMs() = runBlocking {
        val session = admitNormalSession()
        engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.Snooze(nowMs + 1000L),
            expectedVersion = session.sessionVersion,
            expectedState = session.sessionState,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L,
            currentBootCount = bootCount
        )

        // Refire attempted at 2 minutes when refireEpochMs is 10 minutes
        val prematureRefire = engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.SnoozeRefire(nowMs + (2 * 60_000L)),
            expectedVersion = 2L,
            expectedState = AlarmSessionState.SNOOZED.name,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + (2 * 60_000L),
            currentElapsedRealtimeMs = elapsedMs + (2 * 60_000L),
            currentBootCount = bootCount
        )

        assertTrue(prematureRefire is SessionTransitionOutcome.StaleEventRejected)
    }

    @Test
    fun wakeCheckTimeoutRefiresAndPreservesChallengeUnlocked() = runBlocking {
        val session = admitNormalSession(wakeConfirm = true)

        // 1. Transition to WAKE_CHECK_WAITING
        engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.Stop(nowMs + 1000L),
            expectedVersion = session.sessionVersion,
            expectedState = session.sessionState,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L,
            currentBootCount = bootCount
        )

        val checkAt = nowMs + 1000L + (10 * 60_000L)
        // 2. Activate WakeCheck at checkAt
        engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.ActivateWakeCheck(checkAt),
            expectedVersion = 2L,
            expectedState = AlarmSessionState.WAKE_CHECK_WAITING.name,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = checkAt,
            currentElapsedRealtimeMs = elapsedMs + (10 * 60_000L),
            currentBootCount = bootCount
        )

        val timeoutTime = checkAt + 300_000L // 5 min active window
        // 3. WakeCheck Timeout
        val timeoutOutcome = engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.WakeCheckTimeout(timeoutTime),
            expectedVersion = 3L,
            expectedState = AlarmSessionState.WAKE_CHECK_ACTIVE.name,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = timeoutTime,
            currentElapsedRealtimeMs = elapsedMs + (10 * 60_000L) + 300_000L,
            currentBootCount = bootCount
        )

        assertTrue(timeoutOutcome is SessionTransitionOutcome.Success)
        val refiredSession = (timeoutOutcome as SessionTransitionOutcome.Success).session
        assertEquals(AlarmSessionState.FIRING.name, refiredSession.sessionState)
        assertFalse(refiredSession.challengeUnlocked)
    }

    @Test
    fun expiredTimeoutStaleEnvelopeCannotOverwriteNewerGeneration() = runBlocking {
        val session = admitNormalSession()

        // Stale caller envelope with expectedGeneration = 99
        val expiredOutcome = engine.transitionSession(
            sessionId = session.sessionId,
            event = AlarmSessionEvent.Stop(nowMs + 1000L),
            expectedVersion = session.sessionVersion,
            expectedState = session.sessionState,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = 99, // Mismatched caller generation!
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + 18_000_001L, // 5 hours expired
            currentElapsedRealtimeMs = elapsedMs + 18_000_001L,
            currentBootCount = bootCount
        )

        assertTrue(expiredOutcome is SessionTransitionOutcome.ConflictRejected)
        val reRead = repository.getSessionById(session.sessionId)!!
        assertEquals(AlarmSessionState.FIRING.name, reRead.sessionState) // CAS 0 rows, DB state untouched!
    }
}
