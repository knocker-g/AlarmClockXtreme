package com.sysadmindoc.alarmclock.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.alarmclock.data.local.entity.AlarmAdmissionTombstoneEntity
import com.sysadmindoc.alarmclock.data.local.entity.AlarmSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmSessionTombstoneDeduplicationTest {

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
    fun atomicSessionAdmissionCreatesPermanentTombstoneAndRejectsDuplicates() = runBlocking {
        val admissionKey = "v1:admission_100_gen_1_occ_1793872200000"
        val session = AlarmSessionEntity(
            sessionId = "sess_1",
            admissionKey = admissionKey,
            alarmId = 100L,
            originKind = "NORMAL",
            originScheduleKind = "REPEATING",
            generation = 1,
            revision = 1,
            fireId = "fire_1",
            occurrenceKey = "occ_1793872200000",
            canonicalBaseTriggerMs = 1793872200000L,
            scheduledTargetEpochMs = 1793872200000L,
            sessionState = "FIRING",
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
            sessionId = "sess_1",
            occurrenceKey = "occ_1793872200000",
            createdAtEpochMs = 1000L,
            horizonEpochMs = 1793872200000L + (90 * 86400000L)
        )

        // Admitting first session succeeds
        dao.allocateSequenceAndInsertSession(session, tombstone)

        val fetchedTombstone = dao.getTombstoneByAdmissionKey(admissionKey)
        assertNotNull(fetchedTombstone)
        assertEquals("sess_1", fetchedTombstone?.sessionId)

        // Duplicate admission attempt with same admissionKey throws SQLiteConstraintException
        val duplicateSession = session.copy(sessionId = "sess_2")
        val duplicateTombstone = tombstone.copy(sessionId = "sess_2")

        try {
            dao.allocateSequenceAndInsertSession(duplicateSession, duplicateTombstone)
            fail("Expected SQLiteConstraintException on duplicate admissionKey")
        } catch (e: SQLiteConstraintException) {
            // Success: Exception caught
        }

        // Verify active sessions contains ONLY the original session
        val activeSessions = dao.getActiveSessions()
        assertEquals(1, activeSessions.size)
        assertEquals("sess_1", activeSessions[0].sessionId)
    }
}
