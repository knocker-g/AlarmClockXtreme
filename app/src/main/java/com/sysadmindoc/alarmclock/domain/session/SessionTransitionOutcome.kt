package com.sysadmindoc.alarmclock.domain.session

import com.sysadmindoc.alarmclock.data.local.entity.AlarmSessionEntity

/**
 * Outcomes returned by KNO-161 Serialized Session Transition Engine.
 */
sealed class SessionTransitionOutcome {
    data class Success(
        val session: AlarmSessionEntity,
        val postCommitIntent: PostCommitIntent
    ) : SessionTransitionOutcome()

    data class DuplicateAdmissionRejected(val admissionKey: String) : SessionTransitionOutcome()
    data class ConflictRejected(val expectedVersion: Long, val actualVersion: Long) : SessionTransitionOutcome()
    data class TerminalImmutable(val sessionId: String, val terminalReason: String?) : SessionTransitionOutcome()
    data class StaleEventRejected(val sessionId: String, val reason: String) : SessionTransitionOutcome()
    data class InvalidStateTransition(val currentState: String, val requestedEvent: String) : SessionTransitionOutcome()
    data class SnoozeCapExceeded(val sessionId: String, val maxSnoozeCount: Int) : SessionTransitionOutcome()
    data class DeadlineExpired(val sessionId: String, val reason: String) : SessionTransitionOutcome()
    data class SessionNotFound(val sessionId: String) : SessionTransitionOutcome()
    data class RepositoryWriteFailed(val sessionId: String, val throwable: Throwable) : SessionTransitionOutcome()
}
