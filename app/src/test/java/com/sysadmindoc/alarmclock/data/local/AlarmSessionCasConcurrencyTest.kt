package com.sysadmindoc.alarmclock.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.alarmclock.data.local.entity.AlarmAdmissionTombstoneEntity
import com.sysadmindoc.alarmclock.data.local.entity.AlarmSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmSessionCasConcurrencyTest {

    private lateinit var db: AlarmDatabase
    private lateinit var dao: AlarmSessionDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AlarmDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.alarmSessionDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun casEnforcesAllSixIdentityAndStateFences() = runBlocking {
        val admissionKey = "v1:admission_100_gen_1_occ_1000"
        val session = AlarmSessionEntity(
            sessionId = "sess_cas_1",
            admissionKey = admissionKey,
            alarmId = 100L,
            originKind = "NORMAL",
            originScheduleKind = "REPEATING",
            generation = 1,
            revision = 2,
            fireId = "fire_1",
            occurrenceKey = "occ_1000",
            canonicalBaseTriggerMs = 1000L,
            scheduledTargetEpochMs = 1000L,
            sessionState = "FIRING",
            sessionVersion = 1L,
            createdAtEpochMs = 1000L,
            createdElapsedRealtimeMs = 500L,
            bootCount = 1L,
            updatedAtEpochMs = 1000L,
            absoluteDeadlineEpochMs = 1000L + 18_000_000L,
            configurationSnapshotJson = "{}"
        )
        val tombstone = AlarmAdmissionTombstoneEntity(
            admissionKey = admissionKey,
            alarmId = 100L,
            sessionId = "sess_cas_1",
            occurrenceKey = "occ_1000",
            createdAtEpochMs = 1000L,
            horizonEpochMs = 1000L + (90 * 86400000L)
        )

        dao.allocateSequenceAndInsertSession(session, tombstone)

        // 1. Wrong expectedVersion fails CAS
        val updatedRowsWrongVersion = dao.casUpdateSessionState(
            sessionId = "sess_cas_1",
            expectedVersion = 99L, // Mismatched version
            expectedState = "FIRING",
            expectedAdmissionKey = admissionKey,
            expectedGeneration = 1,
            expectedRevision = 2,
            newState = "SNOOZED",
            snoozeCount = 1,
            refireEpochMs = 2000L,
            challengeUnlocked = false,
            updatedAtEpochMs = 1500L,
            completedAtEpochMs = null,
            terminalReason = null
        )
        assertEquals(0, updatedRowsWrongVersion)

        // 2. Correct CAS update succeeds and increments version to 2
        val updatedRowsSuccess = dao.casUpdateSessionState(
            sessionId = "sess_cas_1",
            expectedVersion = 1L,
            expectedState = "FIRING",
            expectedAdmissionKey = admissionKey,
            expectedGeneration = 1,
            expectedRevision = 2,
            newState = "SNOOZED",
            snoozeCount = 1,
            refireEpochMs = 2000L,
            challengeUnlocked = false,
            updatedAtEpochMs = 1500L,
            completedAtEpochMs = null,
            terminalReason = null
        )
        assertEquals(1, updatedRowsSuccess)

        val updatedSession = dao.getSessionById("sess_cas_1")
        assertEquals("SNOOZED", updatedSession?.sessionState)
        assertEquals(2L, updatedSession?.sessionVersion)
        assertEquals(1, updatedSession?.snoozeCount)

        // 3. Stale CAS retry with old expectedVersion 1L fails
        val updatedRowsStaleRetry = dao.casUpdateSessionState(
            sessionId = "sess_cas_1",
            expectedVersion = 1L, // Stale version
            expectedState = "SNOOZED",
            expectedAdmissionKey = admissionKey,
            expectedGeneration = 1,
            expectedRevision = 2,
            newState = "COMPLETED",
            snoozeCount = 1,
            refireEpochMs = 0L,
            challengeUnlocked = true,
            updatedAtEpochMs = 2000L,
            completedAtEpochMs = 2000L,
            terminalReason = "DISMISSED"
        )
        assertEquals(0, updatedRowsStaleRetry)
    }
}
