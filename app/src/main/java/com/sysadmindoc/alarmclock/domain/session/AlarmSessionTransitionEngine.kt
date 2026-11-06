package com.sysadmindoc.alarmclock.domain.session

import com.squareup.moshi.Moshi
import com.sysadmindoc.alarmclock.data.local.entity.AlarmAdmissionTombstoneEntity
import com.sysadmindoc.alarmclock.data.local.entity.AlarmConfigurationSnapshot
import com.sysadmindoc.alarmclock.data.local.entity.AlarmSessionEntity
import com.sysadmindoc.alarmclock.data.repository.AlarmSessionRepository
import com.sysadmindoc.alarmclock.domain.DeadlineEvaluationResult
import com.sysadmindoc.alarmclock.domain.SessionDeadlineEvaluator
import java.util.UUID

/**
 * Production-quality, inactive canonical Serialized Session Transition Core (KNO-161).
 * Single canonical writer of active alarm session state via 6-fence Room CAS.
 */
class AlarmSessionTransitionEngine(
    private val repository: AlarmSessionRepository,
    private val deadlineEvaluator: SessionDeadlineEvaluator = SessionDeadlineEvaluator
) {

    private val moshi = Moshi.Builder().build()
    private val snapshotAdapter = moshi.adapter(AlarmConfigurationSnapshot::class.java)

    suspend fun admitSession(event: AlarmSessionEvent.AdmitSession): SessionTransitionOutcome {
        val snapshot = AlarmConfigurationSnapshot.from(
            alarm = event.alarm,
            settings = event.settings,
            effectiveNormalAutoSilenceMinutes = event.effectiveNormalAutoSilenceMinutes
        )
        val snapshotJson = snapshotAdapter.toJson(snapshot)

        val admissionKey = "admission_${event.alarm.id}_${event.occurrenceKey}"
        val sessionId = "session_${event.alarm.id}_${event.occurrenceKey}_${UUID.randomUUID()}"

        // Absolute Timeout is exactly 5 hours (18,000,000 ms) from Session initial start (createdAtEpochMs)
        val absoluteDeadlineMs = Math.addExact(event.nowEpochMs, 18_000_000L)

        val sessionEntity = AlarmSessionEntity(
            sessionId = sessionId,
            admissionKey = admissionKey,
            alarmId = event.alarm.id,
            originKind = snapshot.kind,
            originScheduleKind = event.alarm.scheduleKind,
            generation = event.alarm.generation,
            revision = event.alarm.revision,
            fireId = event.fireId,
            occurrenceKey = event.occurrenceKey,
            canonicalBaseTriggerMs = event.canonicalBaseTriggerMs,
            scheduledTargetEpochMs = event.scheduledTargetEpochMs,
            sessionState = AlarmSessionState.FIRING.name,
            sessionVersion = 1L,
            creationSequence = 0L,
            snoozeCount = 0,
            refireEpochMs = 0L,
            challengeUnlocked = false,
            createdAtEpochMs = event.nowEpochMs,
            createdElapsedRealtimeMs = event.elapsedRealtimeMs,
            bootCount = event.bootCount,
            updatedAtEpochMs = event.nowEpochMs,
            absoluteDeadlineEpochMs = absoluteDeadlineMs,
            configurationSnapshotJson = snapshotJson,
            snapshotVersion = 1,
            terminalReason = null,
            completedAtEpochMs = null
        )

        val tombstoneEntity = AlarmAdmissionTombstoneEntity(
            admissionKey = admissionKey,
            alarmId = event.alarm.id,
            sessionId = sessionId,
            occurrenceKey = event.occurrenceKey,
            createdAtEpochMs = event.nowEpochMs,
            horizonEpochMs = absoluteDeadlineMs
        )

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
