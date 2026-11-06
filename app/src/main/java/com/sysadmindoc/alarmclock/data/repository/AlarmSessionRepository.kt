package com.sysadmindoc.alarmclock.data.repository

import com.sysadmindoc.alarmclock.data.local.AlarmSessionDao
import com.sysadmindoc.alarmclock.data.local.entity.AlarmAdmissionTombstoneEntity
import com.sysadmindoc.alarmclock.data.local.entity.AlarmSessionEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmSessionRepository @Inject constructor(
    private val alarmSessionDao: AlarmSessionDao
) {
    suspend fun admitSession(
        session: AlarmSessionEntity,
        tombstone: AlarmAdmissionTombstoneEntity
    ): Long {
        return alarmSessionDao.allocateSequenceAndInsertSession(session, tombstone)
    }

    suspend fun getSessionById(sessionId: String): AlarmSessionEntity? {
        return alarmSessionDao.getSessionById(sessionId)
    }

    suspend fun getTombstoneByAdmissionKey(admissionKey: String): AlarmAdmissionTombstoneEntity? {
        return alarmSessionDao.getTombstoneByAdmissionKey(admissionKey)
    }

    suspend fun getActiveSessions(): List<AlarmSessionEntity> {
        return alarmSessionDao.getActiveSessions()
    }

    suspend fun casUpdateSessionState(
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
    ): Boolean {
        val updatedRows = alarmSessionDao.casUpdateSessionState(
            sessionId = sessionId,
            expectedVersion = expectedVersion,
            expectedState = expectedState,
            expectedAdmissionKey = expectedAdmissionKey,
            expectedGeneration = expectedGeneration,
            expectedRevision = expectedRevision,
            newState = newState,
            snoozeCount = snoozeCount,
            refireEpochMs = refireEpochMs,
            challengeUnlocked = challengeUnlocked,
            updatedAtEpochMs = updatedAtEpochMs,
            completedAtEpochMs = completedAtEpochMs,
            terminalReason = terminalReason
        )
        return updatedRows > 0
    }
}
