package com.sysadmindoc.alarmclock.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.sysadmindoc.alarmclock.data.local.entity.AlarmAdmissionTombstoneEntity
import com.sysadmindoc.alarmclock.data.local.entity.AlarmSessionEntity
import com.sysadmindoc.alarmclock.data.local.entity.SessionSequenceGeneratorEntity

class SequenceOverflowException(message: String) : RuntimeException(message)

@Dao
abstract class AlarmSessionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertAdmissionTombstone(tombstone: AlarmAdmissionTombstoneEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSession(session: AlarmSessionEntity): Long

    @Query("SELECT lastAllocatedSequence FROM session_sequence_generator WHERE id = 1")
    abstract suspend fun getLastSequence(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun updateSequenceGenerator(generator: SessionSequenceGeneratorEntity)

    @Transaction
    open suspend fun allocateSequenceAndInsertSession(
        session: AlarmSessionEntity,
        tombstone: AlarmAdmissionTombstoneEntity
    ): Long {
        insertAdmissionTombstone(tombstone) // Throws SQLiteConstraintException on duplicate admissionKey
        val currentSeq = getLastSequence() ?: 0L
        if (currentSeq == Long.MAX_VALUE) {
            throw SequenceOverflowException("Session sequence counter overflow")
        }
        val nextSeq = currentSeq + 1L
        updateSequenceGenerator(SessionSequenceGeneratorEntity(1, nextSeq))
        val sequencedSession = session.copy(creationSequence = nextSeq)
        return insertSession(sequencedSession)
    }

    @Query("SELECT * FROM alarm_sessions WHERE sessionId = :sessionId")
    abstract suspend fun getSessionById(sessionId: String): AlarmSessionEntity?

    @Query("SELECT * FROM alarm_admission_tombstones WHERE admissionKey = :admissionKey")
    abstract suspend fun getTombstoneByAdmissionKey(admissionKey: String): AlarmAdmissionTombstoneEntity?

    @Query("SELECT * FROM alarm_sessions WHERE sessionState IN ('FIRING', 'SNOOZED', 'WAKE_CHECK_WAITING', 'WAKE_CHECK_ACTIVE') ORDER BY creationSequence ASC")
    abstract suspend fun getActiveSessions(): List<AlarmSessionEntity>

    @Query("SELECT * FROM alarm_sessions WHERE alarmId = :alarmId AND sessionState IN ('FIRING', 'SNOOZED', 'WAKE_CHECK_WAITING', 'WAKE_CHECK_ACTIVE') ORDER BY creationSequence ASC")
    abstract suspend fun getActiveSessionsForAlarm(alarmId: Long): List<AlarmSessionEntity>

    @Query("""
        UPDATE alarm_sessions
        SET sessionState = :newState,
            sessionVersion = sessionVersion + 1,
            snoozeCount = :snoozeCount,
            refireEpochMs = :refireEpochMs,
            challengeUnlocked = :challengeUnlocked,
            updatedAtEpochMs = :updatedAtEpochMs,
            completedAtEpochMs = :completedAtEpochMs,
            terminalReason = :terminalReason
        WHERE sessionId = :sessionId
          AND sessionVersion = :expectedVersion
          AND sessionState = :expectedState
          AND admissionKey = :expectedAdmissionKey
          AND generation = :expectedGeneration
          AND revision = :expectedRevision
          AND sessionState NOT IN ('COMPLETED')
    """)
    abstract suspend fun casUpdateSessionState(
        sessionId: String,
        expectedVersion: Long,
        expectedState: String,
        expectedAdmissionKey: String,
        expectedGeneration: Int,
        expectedRevision: Int,
        newState: String,
        snoozeCount: Int,
        refireEpochMs: Long,
        challengeUnlocked: Boolean,
        updatedAtEpochMs: Long,
        completedAtEpochMs: Long?,
        terminalReason: String?
    ): Int

    @Query("DELETE FROM alarm_sessions WHERE completedAtEpochMs IS NOT NULL AND completedAtEpochMs < :thresholdEpochMs")
    abstract suspend fun deleteExpiredTerminalSessions(thresholdEpochMs: Long): Int
}
