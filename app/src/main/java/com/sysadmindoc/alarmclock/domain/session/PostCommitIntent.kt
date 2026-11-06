package com.sysadmindoc.alarmclock.domain.session

/**
 * Inert descriptors of post-commit side-effect intents returned after durable Room CAS commit.
 * Inactive core does not execute side effects.
 */
sealed class PostCommitIntent {
    object None : PostCommitIntent()
    data class ScheduleSnoozeRefire(val sessionId: String, val refireEpochMs: Long) : PostCommitIntent()
    data class ScheduleWakeCheck(val sessionId: String, val wakeCheckDeadlineMs: Long) : PostCommitIntent()
    data class ReTriggerAlarmSound(val sessionId: String) : PostCommitIntent()
    data class SessionTerminated(val sessionId: String, val terminalReason: String) : PostCommitIntent()
}
