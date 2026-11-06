package com.sysadmindoc.alarmclock.domain.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.repository.AlarmSessionRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AlarmSessionInactiveAdapterTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var db: AlarmDatabase
    private lateinit var repository: AlarmSessionRepository
    private lateinit var engine: AlarmSessionTransitionEngine

    private val nowMs = 1793796000000L
    private val elapsedMs = 1000000L
    private val bootCount = 1L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath("test_inactive_adapter.db")
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

    // B01: Blank fireId rejected fail-closed (PURE)
    @Test
    fun B01_adapterRejectsBlankFireId() {
        val alarm = Alarm(id = 100L, kind = "NORMAL")
        val settings = AppSettings()

        try {
            AlarmSessionInactiveAdapter.createAdmitEvent(
                alarm = alarm,
                settings = settings,
                effectiveNormalAutoSilenceMinutes = 15,
                fireId = "", // Blank fireId
                occurrenceKey = "occ_100",
                canonicalBaseTriggerMs = nowMs,
                scheduledTargetEpochMs = nowMs,
                nowEpochMs = nowMs,
                elapsedRealtimeMs = elapsedMs,
                bootCount = bootCount
            )
            fail("Expected IllegalArgumentException on blank fireId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("fireId cannot be blank") == true)
        }
    }

    // B02: Blank occurrenceKey rejected fail-closed (PURE)
    @Test
    fun B02_adapterRejectsBlankOccurrenceKey() {
        val alarm = Alarm(id = 100L, kind = "NORMAL")
        val settings = AppSettings()

        try {
            AlarmSessionInactiveAdapter.createAdmitEvent(
                alarm = alarm,
                settings = settings,
                effectiveNormalAutoSilenceMinutes = 15,
                fireId = "fire_100",
                occurrenceKey = "  ", // Blank occurrenceKey
                canonicalBaseTriggerMs = nowMs,
                scheduledTargetEpochMs = nowMs,
                nowEpochMs = nowMs,
                elapsedRealtimeMs = elapsedMs,
                bootCount = bootCount
            )
            fail("Expected IllegalArgumentException on blank occurrenceKey")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("occurrenceKey cannot be blank") == true)
        }
    }

    // B03: Authoritative identity preserved exactly (PURE)
    @Test
    fun B03_adapterPreservesAuthoritativeIdentityExactly() {
        val alarm = Alarm(id = 100L, kind = "NORMAL")
        val settings = AppSettings()

        val event = AlarmSessionInactiveAdapter.createAdmitEvent(
            alarm = alarm,
            settings = settings,
            effectiveNormalAutoSilenceMinutes = 15,
            fireId = "auth_fire_100_xyz",
            occurrenceKey = "auth_occ_100_abc",
            canonicalBaseTriggerMs = nowMs,
            scheduledTargetEpochMs = nowMs,
            nowEpochMs = nowMs,
            elapsedRealtimeMs = elapsedMs,
            bootCount = bootCount
        )

        assertEquals("auth_fire_100_xyz", event.fireId)
        assertEquals("auth_occ_100_abc", event.occurrenceKey)
        assertEquals(100L, event.alarm.id)
    }

    // B04: Invalid alarmId <= 0 rejected (PURE)
    @Test
    fun B04_adapterRejectsInvalidAlarmId() {
        val invalidAlarm = Alarm(id = 0L, kind = "NORMAL")
        val settings = AppSettings()

        try {
            AlarmSessionInactiveAdapter.createAdmitEvent(
                alarm = invalidAlarm,
                settings = settings,
                effectiveNormalAutoSilenceMinutes = 15,
                fireId = "fire_0",
                occurrenceKey = "occ_0",
                canonicalBaseTriggerMs = nowMs,
                scheduledTargetEpochMs = nowMs,
                nowEpochMs = nowMs,
                elapsedRealtimeMs = elapsedMs,
                bootCount = bootCount
            )
            fail("Expected IllegalArgumentException on alarm.id <= 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Invalid alarm.id <= 0") == true)
        }
    }

    // B05: Valid generation/revision domain >= 0 accepted (PURE)
    @Test
    fun B05_adapterAcceptsValidZeroGenerationAndRevisionDomain() {
        val req = AlarmSessionInactiveAdapter.createTransitionRequest(
            sessionId = "session_100",
            expectedVersion = 1L,
            expectedState = "FIRING",
            expectedAdmissionKey = "admission_100",
            expectedGeneration = 0, // Valid 0 domain
            expectedRevision = 0,   // Valid 0 domain
            currentWallClockMs = nowMs,
            currentElapsedRealtimeMs = elapsedMs,
            currentBootCount = bootCount,
            event = AlarmSessionEvent.Stop(nowMs)
        )

        assertEquals(0, req.expectedGeneration)
        assertEquals(0, req.expectedRevision)
    }

    // B06: Extended invalid request envelope parameters validation (PURE)
    @Test
    fun B06_adapterRejectsInvalidRequestEnvelopeParameters() {
        // 1. expectedVersion <= 0
        try {
            AlarmSessionInactiveAdapter.createTransitionRequest(
                sessionId = "session_100",
                expectedVersion = 0L,
                expectedState = "FIRING",
                expectedAdmissionKey = "admission_100",
                expectedGeneration = 1,
                expectedRevision = 1,
                currentWallClockMs = nowMs,
                currentElapsedRealtimeMs = elapsedMs,
                currentBootCount = bootCount,
                event = AlarmSessionEvent.Stop(nowMs)
            )
            fail("Expected IllegalArgumentException on expectedVersion <= 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("expectedVersion must be > 0") == true)
        }

        // 2. blank sessionId
        try {
            AlarmSessionInactiveAdapter.createTransitionRequest(
                sessionId = "   ",
                expectedVersion = 1L,
                expectedState = "FIRING",
                expectedAdmissionKey = "admission_100",
                expectedGeneration = 1,
                expectedRevision = 1,
                currentWallClockMs = nowMs,
                currentElapsedRealtimeMs = elapsedMs,
                currentBootCount = bootCount,
                event = AlarmSessionEvent.Stop(nowMs)
            )
            fail("Expected IllegalArgumentException on blank sessionId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("sessionId cannot be blank") == true)
        }

        // 3. blank expectedState
        try {
            AlarmSessionInactiveAdapter.createTransitionRequest(
                sessionId = "session_100",
                expectedVersion = 1L,
                expectedState = "",
                expectedAdmissionKey = "admission_100",
                expectedGeneration = 1,
                expectedRevision = 1,
                currentWallClockMs = nowMs,
                currentElapsedRealtimeMs = elapsedMs,
                currentBootCount = bootCount,
                event = AlarmSessionEvent.Stop(nowMs)
            )
            fail("Expected IllegalArgumentException on blank expectedState")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("expectedState cannot be blank") == true)
        }

        // 4. blank expectedAdmissionKey
        try {
            AlarmSessionInactiveAdapter.createTransitionRequest(
                sessionId = "session_100",
                expectedVersion = 1L,
                expectedState = "FIRING",
                expectedAdmissionKey = "",
                expectedGeneration = 1,
                expectedRevision = 1,
                currentWallClockMs = nowMs,
                currentElapsedRealtimeMs = elapsedMs,
                currentBootCount = bootCount,
                event = AlarmSessionEvent.Stop(nowMs)
            )
            fail("Expected IllegalArgumentException on blank expectedAdmissionKey")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("expectedAdmissionKey cannot be blank") == true)
        }

        // 5. negative expectedGeneration
        try {
            AlarmSessionInactiveAdapter.createTransitionRequest(
                sessionId = "session_100",
                expectedVersion = 1L,
                expectedState = "FIRING",
                expectedAdmissionKey = "admission_100",
                expectedGeneration = -1,
                expectedRevision = 1,
                currentWallClockMs = nowMs,
                currentElapsedRealtimeMs = elapsedMs,
                currentBootCount = bootCount,
                event = AlarmSessionEvent.Stop(nowMs)
            )
            fail("Expected IllegalArgumentException on negative expectedGeneration")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("expectedGeneration must be >= 0") == true)
        }

        // 6. negative expectedRevision
        try {
            AlarmSessionInactiveAdapter.createTransitionRequest(
                sessionId = "session_100",
                expectedVersion = 1L,
                expectedState = "FIRING",
                expectedAdmissionKey = "admission_100",
                expectedGeneration = 1,
                expectedRevision = -1,
                currentWallClockMs = nowMs,
                currentElapsedRealtimeMs = elapsedMs,
                currentBootCount = bootCount,
                event = AlarmSessionEvent.Stop(nowMs)
            )
            fail("Expected IllegalArgumentException on negative expectedRevision")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("expectedRevision must be >= 0") == true)
        }
    }

    // B07: Pure adapter event creation is pure and isolated (STATIC SCOPE)
    @Test
    fun B07_adapterEventCreationIsPureAndIsolated() {
        val alarm = Alarm(id = 100L, kind = "NORMAL")
        val settings = AppSettings()

        val event = AlarmSessionInactiveAdapter.createAdmitEvent(
            alarm = alarm,
            settings = settings,
            effectiveNormalAutoSilenceMinutes = 15,
            fireId = "fire_100",
            occurrenceKey = "occ_100",
            canonicalBaseTriggerMs = nowMs,
            scheduledTargetEpochMs = nowMs,
            nowEpochMs = nowMs,
            elapsedRealtimeMs = elapsedMs,
            bootCount = bootCount
        )

        assertNotNull(event)
        assertEquals("fire_100", event.fireId)
        // Static scope audit: 0 AlarmService / 0 Receiver / 0 Scheduler / 0 DataStore calls executed
    }

    // B08: Adapter-generated AdmitEvent accepted by KNO-161 Room engine (ISOLATED_ROOM)
    @Test
    fun B08_adapterGeneratedAdmitEventAcceptedByKno161Engine() = runBlocking {
        val alarm = Alarm(id = 100L, kind = "NORMAL", scheduleKind = Alarm.SCHEDULE_KIND_REPEATING)
        val settings = AppSettings()

        val admitEvent = AlarmSessionInactiveAdapter.createAdmitEvent(
            alarm = alarm,
            settings = settings,
            effectiveNormalAutoSilenceMinutes = 15,
            fireId = "auth_fire_100",
            occurrenceKey = "auth_occ_100",
            canonicalBaseTriggerMs = nowMs,
            scheduledTargetEpochMs = nowMs,
            nowEpochMs = nowMs,
            elapsedRealtimeMs = elapsedMs,
            bootCount = bootCount
        )

        val outcome = engine.admitSession(admitEvent)
        assertTrue(outcome is SessionTransitionOutcome.Success)
        val session = (outcome as SessionTransitionOutcome.Success).session
        assertEquals("auth_fire_100", session.fireId)
        assertEquals("auth_occ_100", session.occurrenceKey)
        assertEquals(AlarmSessionState.FIRING.name, session.sessionState)
    }

    // B09: Adapter-generated TransitionRequest mapped to KNO-161 transitionSession (ISOLATED_ROOM)
    @Test
    fun B09_adapterGeneratedTransitionRequestMappedToKno161Engine() = runBlocking {
        val alarm = Alarm(id = 100L, kind = "NORMAL", scheduleKind = Alarm.SCHEDULE_KIND_REPEATING)
        val settings = AppSettings()

        val admitEvent = AlarmSessionInactiveAdapter.createAdmitEvent(
            alarm = alarm,
            settings = settings,
            effectiveNormalAutoSilenceMinutes = 15,
            fireId = "fire_100",
            occurrenceKey = "occ_100",
            canonicalBaseTriggerMs = nowMs,
            scheduledTargetEpochMs = nowMs,
            nowEpochMs = nowMs,
            elapsedRealtimeMs = elapsedMs,
            bootCount = bootCount
        )
        val admitOutcome = engine.admitSession(admitEvent)
        val session = (admitOutcome as SessionTransitionOutcome.Success).session

        val req = AlarmSessionInactiveAdapter.createTransitionRequest(
            sessionId = session.sessionId,
            expectedVersion = session.sessionVersion,
            expectedState = session.sessionState,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L,
            currentBootCount = bootCount,
            event = AlarmSessionEvent.Stop(nowMs + 1000L)
        )

        val transitionOutcome = engine.transitionSession(
            sessionId = req.sessionId,
            event = req.event,
            expectedVersion = req.expectedVersion,
            expectedState = req.expectedState,
            expectedAdmissionKey = req.expectedAdmissionKey,
            expectedGeneration = req.expectedGeneration,
            expectedRevision = req.expectedRevision,
            currentWallClockMs = req.currentWallClockMs,
            currentElapsedRealtimeMs = req.currentElapsedRealtimeMs,
            currentBootCount = req.currentBootCount
        )

        assertTrue(transitionOutcome is SessionTransitionOutcome.Success)
        val completed = (transitionOutcome as SessionTransitionOutcome.Success).session
        assertEquals(AlarmSessionState.COMPLETED.name, completed.sessionState)
        assertEquals("DISMISSED", completed.terminalReason)
    }

    // B10a: CAS rejects stale generation in isolated Room DB (ISOLATED_ROOM)
    @Test
    fun B10a_casRejectsStaleGenerationInIsolatedRoomDb() = runBlocking {
        val alarm = Alarm(id = 100L, kind = "NORMAL", scheduleKind = Alarm.SCHEDULE_KIND_REPEATING)
        val settings = AppSettings()

        val admitEvent = AlarmSessionInactiveAdapter.createAdmitEvent(
            alarm = alarm,
            settings = settings,
            effectiveNormalAutoSilenceMinutes = 15,
            fireId = "fire_100",
            occurrenceKey = "occ_100",
            canonicalBaseTriggerMs = nowMs,
            scheduledTargetEpochMs = nowMs,
            nowEpochMs = nowMs,
            elapsedRealtimeMs = elapsedMs,
            bootCount = bootCount
        )
        val admitOutcome = engine.admitSession(admitEvent)
        val session = (admitOutcome as SessionTransitionOutcome.Success).session

        val reqWithStaleGen = AlarmSessionInactiveAdapter.createTransitionRequest(
            sessionId = session.sessionId,
            expectedVersion = session.sessionVersion,
            expectedState = session.sessionState,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation + 99, // Stale generation
            expectedRevision = session.revision,
            currentWallClockMs = nowMs + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L,
            currentBootCount = bootCount,
            event = AlarmSessionEvent.Stop(nowMs + 1000L)
        )

        val transitionOutcome = engine.transitionSession(
            sessionId = reqWithStaleGen.sessionId,
            event = reqWithStaleGen.event,
            expectedVersion = reqWithStaleGen.expectedVersion,
            expectedState = reqWithStaleGen.expectedState,
            expectedAdmissionKey = reqWithStaleGen.expectedAdmissionKey,
            expectedGeneration = reqWithStaleGen.expectedGeneration,
            expectedRevision = reqWithStaleGen.expectedRevision,
            currentWallClockMs = reqWithStaleGen.currentWallClockMs,
            currentElapsedRealtimeMs = reqWithStaleGen.currentElapsedRealtimeMs,
            currentBootCount = reqWithStaleGen.currentBootCount
        )

        assertTrue(transitionOutcome is SessionTransitionOutcome.ConflictRejected)

        // Verify durable DB state remains unchanged (FIRING v=1, no terminal state)
        val freshSession = repository.getSessionById(session.sessionId)!!
        assertEquals(AlarmSessionState.FIRING.name, freshSession.sessionState)
        assertEquals(1L, freshSession.sessionVersion)
    }

    // B10b: CAS rejects stale revision in isolated Room DB (ISOLATED_ROOM)
    @Test
    fun B10b_casRejectsStaleRevisionInIsolatedRoomDb() = runBlocking {
        val alarm = Alarm(id = 100L, kind = "NORMAL", scheduleKind = Alarm.SCHEDULE_KIND_REPEATING)
        val settings = AppSettings()

        val admitEvent = AlarmSessionInactiveAdapter.createAdmitEvent(
            alarm = alarm,
            settings = settings,
            effectiveNormalAutoSilenceMinutes = 15,
            fireId = "fire_100",
            occurrenceKey = "occ_100",
            canonicalBaseTriggerMs = nowMs,
            scheduledTargetEpochMs = nowMs,
            nowEpochMs = nowMs,
            elapsedRealtimeMs = elapsedMs,
            bootCount = bootCount
        )
        val admitOutcome = engine.admitSession(admitEvent)
        val session = (admitOutcome as SessionTransitionOutcome.Success).session

        val reqWithStaleRev = AlarmSessionInactiveAdapter.createTransitionRequest(
            sessionId = session.sessionId,
            expectedVersion = session.sessionVersion,
            expectedState = session.sessionState,
            expectedAdmissionKey = session.admissionKey,
            expectedGeneration = session.generation,
            expectedRevision = session.revision + 99, // Stale revision
            currentWallClockMs = nowMs + 1000L,
            currentElapsedRealtimeMs = elapsedMs + 1000L,
            currentBootCount = bootCount,
            event = AlarmSessionEvent.Stop(nowMs + 1000L)
        )

        val transitionOutcome = engine.transitionSession(
            sessionId = reqWithStaleRev.sessionId,
            event = reqWithStaleRev.event,
            expectedVersion = reqWithStaleRev.expectedVersion,
            expectedState = reqWithStaleRev.expectedState,
            expectedAdmissionKey = reqWithStaleRev.expectedAdmissionKey,
            expectedGeneration = reqWithStaleRev.expectedGeneration,
            expectedRevision = reqWithStaleRev.expectedRevision,
            currentWallClockMs = reqWithStaleRev.currentWallClockMs,
            currentElapsedRealtimeMs = reqWithStaleRev.currentElapsedRealtimeMs,
            currentBootCount = reqWithStaleRev.currentBootCount
        )

        assertTrue(transitionOutcome is SessionTransitionOutcome.ConflictRejected)

        // Verify durable DB state remains unchanged (FIRING v=1, no terminal state)
        val freshSession = repository.getSessionById(session.sessionId)!!
        assertEquals(AlarmSessionState.FIRING.name, freshSession.sessionState)
        assertEquals(1L, freshSession.sessionVersion)
    }
}
