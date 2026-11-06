package com.sysadmindoc.alarmclock.domain.session

import com.squareup.moshi.Moshi
import com.sysadmindoc.alarmclock.data.local.entity.AlarmConfigurationSnapshot
import com.sysadmindoc.alarmclock.data.local.entity.AlarmSessionEntity

data class TransitionDecision(
    val newState: String,
    val snoozeCount: Int,
    val refireEpochMs: Long,
    val challengeUnlocked: Boolean,
    val updatedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val terminalReason: String?,
    val postCommitIntent: PostCommitIntent,
    val errorOutcome: SessionTransitionOutcome? = null
)

object PureSessionTransitionEvaluator {

    private val moshi = Moshi.Builder().build()
    private val snapshotAdapter = moshi.adapter(AlarmConfigurationSnapshot::class.java)

    fun evaluate(
        session: AlarmSessionEntity,
        event: AlarmSessionEvent
    ): TransitionDecision {
        // 1. Absorbing Terminal State Guard
        if (session.sessionState == AlarmSessionState.COMPLETED.name) {
            return TransitionDecision(
                newState = session.sessionState,
                snoozeCount = session.snoozeCount,
                refireEpochMs = session.refireEpochMs,
                challengeUnlocked = session.challengeUnlocked,
                updatedAtEpochMs = session.updatedAtEpochMs,
                completedAtEpochMs = session.completedAtEpochMs,
                terminalReason = session.terminalReason,
                postCommitIntent = PostCommitIntent.None,
                errorOutcome = SessionTransitionOutcome.TerminalImmutable(session.sessionId, session.terminalReason)
            )
        }

        val snapshot = try {
            snapshotAdapter.fromJson(session.configurationSnapshotJson)
        } catch (_: Exception) {
            null
        }

        val isQuick = session.originKind.equals("QUICK", ignoreCase = true) || snapshot?.kind == "QUICK"

        when (event) {
            is AlarmSessionEvent.Stop -> {
                val timestamp = event.timestampEpochMs
                val wakeCheckEnabled = !isQuick && (snapshot?.wakeConfirmEnabled == true)

                return if (wakeCheckEnabled) {
                    val delayMinutes = snapshot?.wakeConfirmDelayMinutes ?: 10
                    val wakeCheckDeadlineMs = timestamp + (delayMinutes * 60_000L)
                    TransitionDecision(
                        newState = AlarmSessionState.WAKE_CHECK_WAITING.name,
                        snoozeCount = session.snoozeCount,
                        refireEpochMs = wakeCheckDeadlineMs,
                        challengeUnlocked = session.challengeUnlocked,
                        updatedAtEpochMs = timestamp,
                        completedAtEpochMs = null,
                        terminalReason = null,
                        postCommitIntent = PostCommitIntent.ScheduleWakeCheck(session.sessionId, wakeCheckDeadlineMs)
                    )
                } else {
                    TransitionDecision(
                        newState = AlarmSessionState.COMPLETED.name,
                        snoozeCount = session.snoozeCount,
                        refireEpochMs = 0L,
                        challengeUnlocked = session.challengeUnlocked,
                        updatedAtEpochMs = timestamp,
                        completedAtEpochMs = timestamp,
                        terminalReason = "DISMISSED",
                        postCommitIntent = PostCommitIntent.SessionTerminated(session.sessionId, "DISMISSED")
                    )
                }
            }

            is AlarmSessionEvent.Snooze -> {
                if (session.sessionState == AlarmSessionState.SNOOZED.name) {
                    return TransitionDecision(
                        newState = session.sessionState,
                        snoozeCount = session.snoozeCount,
                        refireEpochMs = session.refireEpochMs,
                        challengeUnlocked = session.challengeUnlocked,
                        updatedAtEpochMs = session.updatedAtEpochMs,
                        completedAtEpochMs = null,
                        terminalReason = null,
                        postCommitIntent = PostCommitIntent.None,
                        errorOutcome = SessionTransitionOutcome.StaleEventRejected(session.sessionId, "EVENT_ALREADY_APPLIED")
                    )
                }

                if (isQuick || snapshot?.snoozeEnabled == false || session.sessionState != AlarmSessionState.FIRING.name) {
                    return TransitionDecision(
                        newState = session.sessionState,
                        snoozeCount = session.snoozeCount,
                        refireEpochMs = session.refireEpochMs,
                        challengeUnlocked = session.challengeUnlocked,
                        updatedAtEpochMs = session.updatedAtEpochMs,
                        completedAtEpochMs = null,
                        terminalReason = null,
                        postCommitIntent = PostCommitIntent.None,
                        errorOutcome = SessionTransitionOutcome.InvalidStateTransition(session.sessionState, "Snooze")
                    )
                }

                val maxSnoozes = snapshot?.maxSnoozeCount ?: 3
                if (maxSnoozes > 0 && session.snoozeCount >= maxSnoozes) {
                    return TransitionDecision(
                        newState = session.sessionState,
                        snoozeCount = session.snoozeCount,
                        refireEpochMs = session.refireEpochMs,
                        challengeUnlocked = session.challengeUnlocked,
                        updatedAtEpochMs = session.updatedAtEpochMs,
                        completedAtEpochMs = null,
                        terminalReason = null,
                        postCommitIntent = PostCommitIntent.None,
                        errorOutcome = SessionTransitionOutcome.SnoozeCapExceeded(session.sessionId, maxSnoozes)
                    )
                }

                val timestamp = event.timestampEpochMs
                val nextSnoozeCount = session.snoozeCount + 1
                val baseDuration = event.customMinutes ?: snapshot?.snoozeDurationMinutes ?: 10
                val effectiveDuration = if (snapshot?.progressiveSnoozeEnabled == true) {
                    val dec = snapshot.progressiveSnoozeDecreaseMinutes
                    val min = snapshot.progressiveSnoozeMinMinutes
                    (baseDuration - (nextSnoozeCount * dec)).coerceAtLeast(min)
                } else {
                    baseDuration
                }

                val refireMs = timestamp + (effectiveDuration * 60_000L)
                return TransitionDecision(
                    newState = AlarmSessionState.SNOOZED.name,
                    snoozeCount = nextSnoozeCount,
                    refireEpochMs = refireMs,
                    challengeUnlocked = session.challengeUnlocked,
                    updatedAtEpochMs = timestamp,
                    completedAtEpochMs = null,
                    terminalReason = null,
                    postCommitIntent = PostCommitIntent.ScheduleSnoozeRefire(session.sessionId, refireMs)
                )
            }

            is AlarmSessionEvent.SnoozeRefire -> {
                if (session.sessionState != AlarmSessionState.SNOOZED.name) {
                    return TransitionDecision(
                        newState = session.sessionState,
                        snoozeCount = session.snoozeCount,
                        refireEpochMs = session.refireEpochMs,
                        challengeUnlocked = session.challengeUnlocked,
                        updatedAtEpochMs = session.updatedAtEpochMs,
                        completedAtEpochMs = null,
                        terminalReason = null,
                        postCommitIntent = PostCommitIntent.None,
                        errorOutcome = SessionTransitionOutcome.InvalidStateTransition(session.sessionState, "SnoozeRefire")
                    )
                }

                val timestamp = event.timestampEpochMs
                // Timed Gate: reject premature refire before refireEpochMs
                if (session.refireEpochMs > 0L && timestamp < session.refireEpochMs) {
                    return TransitionDecision(
                        newState = session.sessionState,
                        snoozeCount = session.snoozeCount,
                        refireEpochMs = session.refireEpochMs,
                        challengeUnlocked = session.challengeUnlocked,
                        updatedAtEpochMs = session.updatedAtEpochMs,
                        completedAtEpochMs = null,
                        terminalReason = null,
                        postCommitIntent = PostCommitIntent.None,
                        errorOutcome = SessionTransitionOutcome.StaleEventRejected(session.sessionId, "PREMATURE_SNOOZE_REFIRE")
                    )
                }

                return TransitionDecision(
                    newState = AlarmSessionState.FIRING.name,
                    snoozeCount = session.snoozeCount,
                    refireEpochMs = 0L,
                    challengeUnlocked = session.challengeUnlocked,
                    updatedAtEpochMs = timestamp,
                    completedAtEpochMs = null,
                    terminalReason = null,
                    postCommitIntent = PostCommitIntent.None
                )
            }

            is AlarmSessionEvent.ActivateWakeCheck -> {
                if (session.sessionState != AlarmSessionState.WAKE_CHECK_WAITING.name) {
                    return TransitionDecision(
                        newState = session.sessionState,
                        snoozeCount = session.snoozeCount,
                        refireEpochMs = session.refireEpochMs,
                        challengeUnlocked = session.challengeUnlocked,
                        updatedAtEpochMs = session.updatedAtEpochMs,
                        completedAtEpochMs = null,
                        terminalReason = null,
                        postCommitIntent = PostCommitIntent.None,
                        errorOutcome = SessionTransitionOutcome.InvalidStateTransition(session.sessionState, "ActivateWakeCheck")
                    )
                }

                val timestamp = event.timestampEpochMs
                // Timed Gate: reject premature activation before checkAt (refireEpochMs)
                if (session.refireEpochMs > 0L && timestamp < session.refireEpochMs) {
                    return TransitionDecision(
                        newState = session.sessionState,
                        snoozeCount = session.snoozeCount,
                        refireEpochMs = session.refireEpochMs,
                        challengeUnlocked = session.challengeUnlocked,
                        updatedAtEpochMs = session.updatedAtEpochMs,
                        completedAtEpochMs = null,
                        terminalReason = null,
                        postCommitIntent = PostCommitIntent.None,
                        errorOutcome = SessionTransitionOutcome.StaleEventRejected(session.sessionId, "PREMATURE_WAKE_CHECK_ACTIVATION")
                    )
                }

                return TransitionDecision(
                    newState = AlarmSessionState.WAKE_CHECK_ACTIVE.name,
                    snoozeCount = session.snoozeCount,
                    refireEpochMs = session.refireEpochMs,
                    challengeUnlocked = session.challengeUnlocked,
                    updatedAtEpochMs = timestamp,
                    completedAtEpochMs = null,
                    terminalReason = null,
                    postCommitIntent = PostCommitIntent.None
                )
            }

            is AlarmSessionEvent.WakeCheckPassed -> {
                if (session.sessionState != AlarmSessionState.WAKE_CHECK_ACTIVE.name) {
                    return TransitionDecision(
                        newState = session.sessionState,
                        snoozeCount = session.snoozeCount,
                        refireEpochMs = session.refireEpochMs,
                        challengeUnlocked = session.challengeUnlocked,
                        updatedAtEpochMs = session.updatedAtEpochMs,
                        completedAtEpochMs = null,
                        terminalReason = null,
                        postCommitIntent = PostCommitIntent.None,
                        errorOutcome = SessionTransitionOutcome.InvalidStateTransition(session.sessionState, "WakeCheckPassed")
                    )
                }

                val timestamp = event.timestampEpochMs
                return TransitionDecision(
                    newState = AlarmSessionState.COMPLETED.name,
                    snoozeCount = session.snoozeCount,
                    refireEpochMs = 0L,
                    challengeUnlocked = session.challengeUnlocked,
                    updatedAtEpochMs = timestamp,
                    completedAtEpochMs = timestamp,
                    terminalReason = "DISMISSED",
                    postCommitIntent = PostCommitIntent.SessionTerminated(session.sessionId, "DISMISSED")
                )
            }

            is AlarmSessionEvent.WakeCheckTimeout -> {
                if (session.sessionState != AlarmSessionState.WAKE_CHECK_ACTIVE.name) {
                    return TransitionDecision(
                        newState = session.sessionState,
                        snoozeCount = session.snoozeCount,
                        refireEpochMs = session.refireEpochMs,
                        challengeUnlocked = session.challengeUnlocked,
                        updatedAtEpochMs = session.updatedAtEpochMs,
                        completedAtEpochMs = null,
                        terminalReason = null,
                        postCommitIntent = PostCommitIntent.None,
                        errorOutcome = SessionTransitionOutcome.InvalidStateTransition(session.sessionState, "WakeCheckTimeout")
                    )
                }

                val timestamp = event.timestampEpochMs
                // Timed Gate: 5-minute active window for WakeCheck
                val earliestTimeoutMs = session.updatedAtEpochMs + 300_000L
                if (timestamp < earliestTimeoutMs) {
                    return TransitionDecision(
                        newState = session.sessionState,
                        snoozeCount = session.snoozeCount,
                        refireEpochMs = session.refireEpochMs,
                        challengeUnlocked = session.challengeUnlocked,
                        updatedAtEpochMs = session.updatedAtEpochMs,
                        completedAtEpochMs = null,
                        terminalReason = null,
                        postCommitIntent = PostCommitIntent.None,
                        errorOutcome = SessionTransitionOutcome.StaleEventRejected(session.sessionId, "PREMATURE_WAKE_CHECK_TIMEOUT")
                    )
                }

                return TransitionDecision(
                    newState = AlarmSessionState.FIRING.name,
                    snoozeCount = session.snoozeCount,
                    refireEpochMs = 0L,
                    challengeUnlocked = session.challengeUnlocked, // Preserve challengeUnlocked!
                    updatedAtEpochMs = timestamp,
                    completedAtEpochMs = null,
                    terminalReason = null,
                    postCommitIntent = PostCommitIntent.ReTriggerAlarmSound(session.sessionId)
                )
            }

            is AlarmSessionEvent.AutoEndTimeout -> {
                if (session.sessionState != AlarmSessionState.FIRING.name) {
                    return TransitionDecision(
                        newState = session.sessionState,
                        snoozeCount = session.snoozeCount,
                        refireEpochMs = session.refireEpochMs,
                        challengeUnlocked = session.challengeUnlocked,
                        updatedAtEpochMs = session.updatedAtEpochMs,
                        completedAtEpochMs = null,
                        terminalReason = null,
                        postCommitIntent = PostCommitIntent.None,
                        errorOutcome = SessionTransitionOutcome.InvalidStateTransition(session.sessionState, "AutoEndTimeout")
                    )
                }

                if (snapshot == null) {
                    return TransitionDecision(
                        newState = session.sessionState,
                        snoozeCount = session.snoozeCount,
                        refireEpochMs = session.refireEpochMs,
                        challengeUnlocked = session.challengeUnlocked,
                        updatedAtEpochMs = session.updatedAtEpochMs,
                        completedAtEpochMs = null,
                        terminalReason = null,
                        postCommitIntent = PostCommitIntent.None,
                        errorOutcome = SessionTransitionOutcome.StaleEventRejected(session.sessionId, "MISSING_CONFIGURATION_SNAPSHOT")
                    )
                }

                // Fail-Closed Auto-End Persistence Boundary: Room Schema 37 lacks accumulated Foreground active-time counters
                return TransitionDecision(
                    newState = session.sessionState,
                    snoozeCount = session.snoozeCount,
                    refireEpochMs = session.refireEpochMs,
                    challengeUnlocked = session.challengeUnlocked,
                    updatedAtEpochMs = session.updatedAtEpochMs,
                    completedAtEpochMs = null,
                    terminalReason = null,
                    postCommitIntent = PostCommitIntent.None,
                    errorOutcome = SessionTransitionOutcome.StaleEventRejected(session.sessionId, "AUTO_END_UNVERIFIED_PERSISTENCE_BOUNDARY")
                )
            }

            is AlarmSessionEvent.AbsoluteTimeout -> {
                val timestamp = event.timestampEpochMs
                val reason = event.reason.ifBlank { "ABSOLUTE_TIMEOUT" }
                return TransitionDecision(
                    newState = AlarmSessionState.COMPLETED.name,
                    snoozeCount = session.snoozeCount,
                    refireEpochMs = 0L,
                    challengeUnlocked = session.challengeUnlocked,
                    updatedAtEpochMs = timestamp,
                    completedAtEpochMs = timestamp,
                    terminalReason = reason,
                    postCommitIntent = PostCommitIntent.SessionTerminated(session.sessionId, reason)
                )
            }

            is AlarmSessionEvent.Supersede -> {
                val timestamp = event.timestampEpochMs
                return TransitionDecision(
                    newState = AlarmSessionState.COMPLETED.name,
                    snoozeCount = session.snoozeCount,
                    refireEpochMs = 0L,
                    challengeUnlocked = session.challengeUnlocked,
                    updatedAtEpochMs = timestamp,
                    completedAtEpochMs = timestamp,
                    terminalReason = "SUPERSEDED",
                    postCommitIntent = PostCommitIntent.SessionTerminated(session.sessionId, "SUPERSEDED")
                )
            }

            is AlarmSessionEvent.Abort -> {
                val timestamp = event.timestampEpochMs
                return TransitionDecision(
                    newState = AlarmSessionState.COMPLETED.name,
                    snoozeCount = session.snoozeCount,
                    refireEpochMs = 0L,
                    challengeUnlocked = session.challengeUnlocked,
                    updatedAtEpochMs = timestamp,
                    completedAtEpochMs = timestamp,
                    terminalReason = "ABORTED",
                    postCommitIntent = PostCommitIntent.SessionTerminated(session.sessionId, "ABORTED")
                )
            }

            is AlarmSessionEvent.EmergencyDismiss -> {
                val timestamp = event.timestampEpochMs
                return TransitionDecision(
                    newState = AlarmSessionState.COMPLETED.name,
                    snoozeCount = session.snoozeCount,
                    refireEpochMs = 0L,
                    challengeUnlocked = session.challengeUnlocked,
                    updatedAtEpochMs = timestamp,
                    completedAtEpochMs = timestamp,
                    terminalReason = "EMERGENCY_DISMISSED",
                    postCommitIntent = PostCommitIntent.SessionTerminated(session.sessionId, "EMERGENCY_DISMISSED")
                )
            }

            is AlarmSessionEvent.AdmitSession -> {
                return TransitionDecision(
                    newState = session.sessionState,
                    snoozeCount = session.snoozeCount,
                    refireEpochMs = session.refireEpochMs,
                    challengeUnlocked = session.challengeUnlocked,
                    updatedAtEpochMs = session.updatedAtEpochMs,
                    completedAtEpochMs = null,
                    terminalReason = null,
                    postCommitIntent = PostCommitIntent.None,
                    errorOutcome = SessionTransitionOutcome.InvalidStateTransition(session.sessionState, "AdmitSession")
                )
            }
        }
    }
}
