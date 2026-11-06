package com.sysadmindoc.alarmclock.domain.evaluator

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.AlarmAdmissionTombstoneEntity
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.repository.AlarmSessionRepository
import com.sysadmindoc.alarmclock.domain.session.AlarmSessionEvent
import com.sysadmindoc.alarmclock.domain.session.AlarmSessionState
import com.sysadmindoc.alarmclock.domain.session.AlarmSessionTransitionEngine
import com.sysadmindoc.alarmclock.domain.session.SessionTransitionOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HandoffIdentityContractTest {

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
        dbFile = context.getDatabasePath("test_handoff_identity_contract.db")
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

    // A. Two issued occurrences can have distinct issueTokens even for equivalent alarm/trigger info
    @Test
    fun H1_twoIssuedOccurrencesCanHaveDistinctIssueTokensForSameAlarm() {
        val alarmId = 100L
        val scheduledAt = nowMs
        val token1 = "issue_${alarmId}_gen_1_${scheduledAt}_${UUID.randomUUID()}"
        val token2 = "issue_${alarmId}_gen_1_${scheduledAt}_${UUID.randomUUID()}"

        assertNotEquals(token1, token2)

        val occurrence1 = IssuedOccurrence(
            issueToken = token1,
            alarmId = alarmId,
            scheduledAt = scheduledAt,
            fireId = "fire_1",
            generationId = 1L,
            status = IssuedOccurrence.STATUS_ISSUED
        )
        val occurrence2 = IssuedOccurrence(
            issueToken = token2,
            alarmId = alarmId,
            scheduledAt = scheduledAt,
            fireId = "fire_2",
            generationId = 1L,
            status = IssuedOccurrence.STATUS_ISSUED
        )

        assertNotEquals(occurrence1.issueToken, occurrence2.issueToken)
        assertEquals(occurrence1.alarmId, occurrence2.alarmId)
        assertEquals(occurrence1.scheduledAt, occurrence2.scheduledAt)
    }

    // B. An issueToken is NOT automatically identical to canonical occurrenceKey
    @Test
    fun H1_issueTokenIsNotIdenticalToCanonicalOccurrenceKey() {
        val alarmId = 100L
        val scheduledAt = nowMs
        val issueToken = "issue_${alarmId}_gen_1_${scheduledAt}_${UUID.randomUUID()}"
        val occurrenceKey = "${alarmId}_${scheduledAt}"

        assertNotEquals(issueToken, occurrenceKey)
        assertTrue(issueToken.startsWith("issue_"))
        assertFalse(occurrenceKey.startsWith("issue_"))
    }

    // C. Existing Session admission deduplication is based on admissionKey
    @Test
    fun H1_sessionAdmissionDeduplicationIsBasedOnAdmissionKey() = runBlocking {
        val alarmId = 100L
        val occurrenceKey = "occ_100_test"
        val admissionKey = "admission_${alarmId}_${occurrenceKey}"

        val tombstone1 = AlarmAdmissionTombstoneEntity(
            admissionKey = admissionKey,
            alarmId = alarmId,
            sessionId = "session_1",
            occurrenceKey = occurrenceKey,
            createdAtEpochMs = nowMs,
            horizonEpochMs = nowMs + 18_000_000L
        )

        val tombstone2 = AlarmAdmissionTombstoneEntity(
            admissionKey = admissionKey, // Same admissionKey!
            alarmId = alarmId,
            sessionId = "session_2",
            occurrenceKey = occurrenceKey,
            createdAtEpochMs = nowMs,
            horizonEpochMs = nowMs + 18_000_000L
        )

        // First insertion succeeds
        val rowId1 = db.alarmSessionDao().insertAdmissionTombstone(tombstone1)
        assertTrue(rowId1 > 0L)

        // Second insertion with same admissionKey throws SQLiteConstraintException
        try {
            db.alarmSessionDao().insertAdmissionTombstone(tombstone2)
            fail("Expected SQLiteConstraintException on duplicate admissionKey")
        } catch (e: SQLiteConstraintException) {
            assertNotNull(e.message)
        }
    }

    // D. Repeating Session admission with the same canonical occurrenceKey cannot create two successful Sessions
    @Test
    fun H1_repeatingSessionAdmissionWithSameOccurrenceKeyCannotCreateTwoSessions() = runBlocking {
        val alarm = Alarm(id = 100L, kind = "NORMAL", scheduleKind = Alarm.SCHEDULE_KIND_REPEATING)
        val settings = AppSettings()

        val admitEvent1 = AlarmSessionEvent.AdmitSession(
            alarm = alarm,
            settings = settings,
            effectiveNormalAutoSilenceMinutes = 15,
            fireId = "fire_100_a",
            occurrenceKey = "occ_100_canonical",
            canonicalBaseTriggerMs = nowMs,
            scheduledTargetEpochMs = nowMs,
            nowEpochMs = nowMs,
            elapsedRealtimeMs = elapsedMs,
            bootCount = bootCount
        )

        val outcome1 = engine.admitSession(admitEvent1)
        assertTrue(outcome1 is SessionTransitionOutcome.Success)

        // Second admission attempt with identical occurrenceKey
        val admitEvent2 = AlarmSessionEvent.AdmitSession(
            alarm = alarm,
            settings = settings,
            effectiveNormalAutoSilenceMinutes = 15,
            fireId = "fire_100_b",
            occurrenceKey = "occ_100_canonical", // Same occurrenceKey!
            canonicalBaseTriggerMs = nowMs,
            scheduledTargetEpochMs = nowMs,
            nowEpochMs = nowMs + 100L,
            elapsedRealtimeMs = elapsedMs + 100L,
            bootCount = bootCount
        )

        val outcome2 = engine.admitSession(admitEvent2)
        assertTrue(outcome2 is SessionTransitionOutcome.DuplicateAdmissionRejected)
        val dupKey = (outcome2 as SessionTransitionOutcome.DuplicateAdmissionRejected).admissionKey
        assertEquals("admission_100_occ_100_canonical", dupKey)
    }

    // E. A different generation or canonical occurrence identity is not silently merged
    @Test
    fun H1_differentGenerationOrOccurrenceIdentityIsNotMerged() = runBlocking {
        val alarmGen1 = Alarm(id = 100L, generation = 1, kind = "NORMAL")
        val alarmGen2 = Alarm(id = 100L, generation = 2, kind = "NORMAL")
        val settings = AppSettings()

        val admitEvent1 = AlarmSessionEvent.AdmitSession(
            alarm = alarmGen1,
            settings = settings,
            effectiveNormalAutoSilenceMinutes = 15,
            fireId = "fire_gen1",
            occurrenceKey = "occ_100_gen1",
            canonicalBaseTriggerMs = nowMs,
            scheduledTargetEpochMs = nowMs,
            nowEpochMs = nowMs,
            elapsedRealtimeMs = elapsedMs,
            bootCount = bootCount
        )

        val admitEvent2 = AlarmSessionEvent.AdmitSession(
            alarm = alarmGen2,
            settings = settings,
            effectiveNormalAutoSilenceMinutes = 15,
            fireId = "fire_gen2",
            occurrenceKey = "occ_100_gen2", // Distinct occurrenceKey for gen2
            canonicalBaseTriggerMs = nowMs + 86400000L,
            scheduledTargetEpochMs = nowMs + 86400000L,
            nowEpochMs = nowMs + 86400000L,
            elapsedRealtimeMs = elapsedMs + 86400000L,
            bootCount = bootCount
        )

        val outcome1 = engine.admitSession(admitEvent1)
        val outcome2 = engine.admitSession(admitEvent2)

        assertTrue(outcome1 is SessionTransitionOutcome.Success)
        assertTrue(outcome2 is SessionTransitionOutcome.Success)

        val session1 = (outcome1 as SessionTransitionOutcome.Success).session
        val session2 = (outcome2 as SessionTransitionOutcome.Success).session

        assertNotEquals(session1.sessionId, session2.sessionId)
        assertEquals(1, session1.generation)
        assertEquals(2, session2.generation)
    }

    // F. Session admission retains canonicalBaseTriggerMs and scheduledTargetEpochMs distinctly
    @Test
    fun H1_sessionAdmissionRetainsCanonicalBaseTriggerMsAndScheduledTargetEpochMs() = runBlocking {
        val alarm = Alarm(id = 100L, kind = "NORMAL")
        val settings = AppSettings()

        val baseTriggerMs = nowMs - 5000L // Daylight savings / DST offset test
        val targetEpochMs = nowMs

        val admitEvent = AlarmSessionEvent.AdmitSession(
            alarm = alarm,
            settings = settings,
            effectiveNormalAutoSilenceMinutes = 15,
            fireId = "fire_distinct_timestamps",
            occurrenceKey = "occ_distinct_timestamps",
            canonicalBaseTriggerMs = baseTriggerMs,
            scheduledTargetEpochMs = targetEpochMs,
            nowEpochMs = nowMs,
            elapsedRealtimeMs = elapsedMs,
            bootCount = bootCount
        )

        val outcome = engine.admitSession(admitEvent)
        assertTrue(outcome is SessionTransitionOutcome.Success)
        val session = (outcome as SessionTransitionOutcome.Success).session

        assertEquals(baseTriggerMs, session.canonicalBaseTriggerMs)
        assertEquals(targetEpochMs, session.scheduledTargetEpochMs)
        assertNotEquals(session.canonicalBaseTriggerMs, session.scheduledTargetEpochMs)
    }
}
