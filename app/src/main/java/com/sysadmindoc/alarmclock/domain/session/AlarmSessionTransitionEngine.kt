package com.sysadmindoc.alarmclock.domain.session

import com.sysadmindoc.alarmclock.data.local.entity.AlarmSessionEntity
import com.sysadmindoc.alarmclock.data.repository.AlarmSessionRepository
import com.sysadmindoc.alarmclock.domain.DeadlineEvaluationResult
import com.sysadmindoc.alarmclock.domain.SessionDeadlineEvaluator

/**
 * Production-quality, inactive canonical Serialized Session Transition Core (KNO-161).
 * Single canonical writer of active alarm session state via 6-fence Room CAS.
 */
class AlarmSessionTransitionEngine(
    private val repository: AlarmSessionRepository,
    private val deadlineEvaluator: SessionDeadlineEvaluator = SessionDeadlineEvaluator,
    private val admissionFactory: NormalAlarmAdmissionFactory = NormalAlarmAdmissionFactory()
) {

    suspend fun admitSession(event: AlarmSessionEvent.AdmitSession): SessionTransitionOutcome {
        val prepared = admissionFactory.prepareAdmission(event)
        val sessionEntity = prepared.session
        val tombstoneEntity = prepared.tombstone
        val sessionId = sessionEntity.sessionId
        val admissionKey = tombstoneEntity.admissionKey

        return try {
            repository.admitSession(sessionEntity, tombstoneEntity)
            val insertedSession = repository.getSessionById(sessionId) ?: sessionEntity
            SessionTransitionOutcome.Success(insertedSession, PostCommitIntent.None)
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            SessionTransitionOutcome.DuplicateAdmissionRejected(admissionKey)
        } catch (e: Exception) {
            SessionTransitionOutcome.RepositoryWriteFailed(sessionId, e)
        }
    }

    suspend fun transitionSession(
        sessionId: String,
        event: AlarmSessionEvent,
        expectedVersion: Long,
        expectedState: String,
        expectedAdmissionKey: String,
        expectedGeneration: Int,
        expectedRevision: Int,
        currentWallClockMs: Long,
        currentElapsedRealtimeMs: Long,
        currentBootCount: Long
    ): SessionTransitionOutcome {
        // 1. Read canonical session from DB
        val session = try {
            repository.getSessionById(sessionId)
                ?: return SessionTransitionOutcome.SessionNotFound(sessionId)
        } catch (e: Exception) {
            return SessionTransitionOutcome.RepositoryWriteFailed(sessionId, e)
        }

        // 2. Terminal State Immutability Guard
        if (session.sessionState == AlarmSessionState.COMPLETED.name) {
            return SessionTransitionOutcome.TerminalImmutable(sessionId, session.terminalReason)
        }

        // 3. Deadline / Monotonic 5h / BootCount Fail-Closed Check
        val deadlineResult = deadlineEvaluator.evaluate(
            session = session,
            currentWallClockMs = currentWallClockMs,
            currentElapsedRealtimeMs = currentElapsedRealtimeMs,
            currentBootCount = currentBootCount
        )

        if (deadlineResult is DeadlineEvaluationResult.ExpiredTimeout) {
            val decision = PureSessionTransitionEvaluator.evaluate(
                session,
                AlarmSessionEvent.AbsoluteTimeout(currentWallClockMs, "ABSOLUTE_TIMEOUT")
            )
            val casSuccess = try {
                repository.casUpdateSessionState(
                    sessionId = sessionId,
                    expectedVersion = expectedVersion, // Caller expected envelope fencing
                    expectedState = expectedState,
                    expectedAdmissionKey = expectedAdmissionKey,
                    expectedGeneration = expectedGeneration,
                    expectedRevision = expectedRevision,
                    newState = decision.newState,
                    snoozeCount = decision.snoozeCount,
                    refireEpochMs = decision.refireEpochMs,
                    challengeUnlocked = decision.challengeUnlocked,
                    updatedAtEpochMs = decision.updatedAtEpochMs,
                    completedAtEpochMs = decision.completedAtEpochMs,
                    terminalReason = decision.terminalReason
                )
            } catch (e: Exception) {
                return SessionTransitionOutcome.RepositoryWriteFailed(sessionId, e)
            }

            return if (casSuccess) {
                val updated = session.copy(
                    sessionState = decision.newState,
                    sessionVersion = expectedVersion + 1,
                    updatedAtEpochMs = decision.updatedAtEpochMs,
                    completedAtEpochMs = decision.completedAtEpochMs,
                    terminalReason = decision.terminalReason
                )
                SessionTransitionOutcome.Success(updated, decision.postCommitIntent)
            } else {
                classifyCasFailure(sessionId, expectedVersion, expectedState, event)
            }
        }

        // 4. Pure Transition Decision Evaluation
        val decision = PureSessionTransitionEvaluator.evaluate(session, event)
        if (decision.errorOutcome != null) {
            return decision.errorOutcome
        }

        // 5. Execute 6-fence Guarded CAS Update via Repository
        val casSuccess = try {
            repository.casUpdateSessionState(
                sessionId = sessionId,
                expectedVersion = expectedVersion,
                expectedState = expectedState,
                expectedAdmissionKey = expectedAdmissionKey,
                expectedGeneration = expectedGeneration,
                expectedRevision = expectedRevision,
                newState = decision.newState,
                snoozeCount = decision.snoozeCount,
                refireEpochMs = decision.refireEpochMs,
                challengeUnlocked = decision.challengeUnlocked,
                updatedAtEpochMs = decision.updatedAtEpochMs,
                completedAtEpochMs = decision.completedAtEpochMs,
                terminalReason = decision.terminalReason
            )
        } catch (e: Exception) {
            return SessionTransitionOutcome.RepositoryWriteFailed(sessionId, e)
        }

        if (casSuccess) {
            val updatedSession = session.copy(
                sessionState = decision.newState,
                sessionVersion = expectedVersion + 1,
                snoozeCount = decision.snoozeCount,
                refireEpochMs = decision.refireEpochMs,
                challengeUnlocked = decision.challengeUnlocked,
                updatedAtEpochMs = decision.updatedAtEpochMs,
                completedAtEpochMs = decision.completedAtEpochMs,
                terminalReason = decision.terminalReason
            )
            return SessionTransitionOutcome.Success(updatedSession, decision.postCommitIntent)
        }

        // 6. On CAS zero-row result: Re-read canonical Session from DB and classify
        return classifyCasFailure(sessionId, expectedVersion, expectedState, event)
    }

    private suspend fun classifyCasFailure(
        sessionId: String,
        expectedVersion: Long,
        expectedState: String,
        event: AlarmSessionEvent
    ): SessionTransitionOutcome {
        val freshSession = try {
            repository.getSessionById(sessionId)
                ?: return SessionTransitionOutcome.SessionNotFound(sessionId)
        } catch (e: Exception) {
            return SessionTransitionOutcome.RepositoryWriteFailed(sessionId, e)
        }

        if (freshSession.sessionState == AlarmSessionState.COMPLETED.name) {
            return SessionTransitionOutcome.TerminalImmutable(sessionId, freshSession.terminalReason)
        }

        if (freshSession.sessionVersion > expectedVersion) {
            if (isEventAlreadyApplied(freshSession, event, expectedState)) {
                return SessionTransitionOutcome.StaleEventRejected(sessionId, "EVENT_ALREADY_APPLIED")
            }
            return SessionTransitionOutcome.ConflictRejected(expectedVersion, freshSession.sessionVersion)
        }

        return SessionTransitionOutcome.ConflictRejected(expectedVersion, freshSession.sessionVersion)
    }

    private fun isEventAlreadyApplied(
        session: AlarmSessionEntity,
        event: AlarmSessionEvent,
        priorState: String
    ): Boolean {
        return when (event) {
            is AlarmSessionEvent.Snooze -> session.sessionState == AlarmSessionState.SNOOZED.name
            is AlarmSessionEvent.Stop -> session.sessionState == AlarmSessionState.COMPLETED.name || session.sessionState == AlarmSessionState.WAKE_CHECK_WAITING.name
            is AlarmSessionEvent.SnoozeRefire -> session.sessionState == AlarmSessionState.FIRING.name && priorState == AlarmSessionState.SNOOZED.name
            is AlarmSessionEvent.ActivateWakeCheck -> session.sessionState == AlarmSessionState.WAKE_CHECK_ACTIVE.name
            else -> false
        }
    }
}
