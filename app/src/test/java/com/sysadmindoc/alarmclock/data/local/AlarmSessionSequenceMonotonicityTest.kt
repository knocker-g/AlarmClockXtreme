package com.sysadmindoc.alarmclock.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.alarmclock.data.local.entity.AlarmAdmissionTombstoneEntity
import com.sysadmindoc.alarmclock.data.local.entity.AlarmSessionEntity
import com.sysadmindoc.alarmclock.data.local.entity.SessionSequenceGeneratorEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmSessionSequenceMonotonicityTest {

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
    fun sessionSequenceAllocatesStrictlyMonotonicSequences() = runBlocking {
        for (i in 1..5) {
            val admissionKey = "v1:admission_100_gen_1_occ_$i"
            val session = AlarmSessionEntity(
                sessionId = "sess_seq_$i",
                admissionKey = admissionKey,
                alarmId = 100L,
                originKind = "NORMAL",
                originScheduleKind = "REPEATING",
                generation = 1,
                revision = 1,
                fireId = "fire_$i",
                occurrenceKey = "occ_$i",
                canonicalBaseTriggerMs = i * 1000L,
                scheduledTargetEpochMs = i * 1000L,
                sessionState = "FIRING",
                createdAtEpochMs = i * 1000L,
                createdElapsedRealtimeMs = i * 500L,
                bootCount = 1L,
                updatedAtEpochMs = i * 1000L,
                absoluteDeadlineEpochMs = i * 1000L + 18_000_000L,
                configurationSnapshotJson = "{}"
            )
            val tombstone = AlarmAdmissionTombstoneEntity(
                admissionKey = admissionKey,
                alarmId = 100L,
                sessionId = "sess_seq_$i",
                occurrenceKey = "occ_$i",
                createdAtEpochMs = i * 1000L,
                horizonEpochMs = i * 1000L + (90 * 86400000L)
            )

            dao.allocateSequenceAndInsertSession(session, tombstone)
            val insertedSession = dao.getSessionById("sess_seq_$i")
            assertEquals(i.toLong(), insertedSession?.creationSequence)
        }

        val lastSeq = dao.getLastSequence()
        assertEquals(5L, lastSeq)
    }

    @Test
    fun sequenceOverflowThrowsExceptionSafely() = runBlocking {
        // Preset sequence generator to Long.MAX_VALUE
        dao.updateSequenceGenerator(SessionSequenceGeneratorEntity(1, Long.MAX_VALUE))

        val admissionKey = "v1:admission_overflow"
        val session = AlarmSessionEntity(
            sessionId = "sess_overflow",
            admissionKey = admissionKey,
            alarmId = 100L,
            originKind = "NORMAL",
            originScheduleKind = "ONE_TIME",
            generation = 1,
            revision = 1,
            fireId = "fire_overflow",
            occurrenceKey = "occ_overflow",
            canonicalBaseTriggerMs = 1000L,
            scheduledTargetEpochMs = 1000L,
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
            sessionId = "sess_overflow",
            occurrenceKey = "occ_overflow",
            createdAtEpochMs = 1000L,
            horizonEpochMs = 1000L + (90 * 86400000L)
        )

        try {
            dao.allocateSequenceAndInsertSession(session, tombstone)
            fail("Expected SequenceOverflowException when sequence reaches Long.MAX_VALUE")
        } catch (e: SequenceOverflowException) {
            // Success: Sequence overflow safely caught
        }
    }
}
