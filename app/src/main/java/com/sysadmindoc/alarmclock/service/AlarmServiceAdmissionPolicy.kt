package com.sysadmindoc.alarmclock.service

internal enum class StartAdmissionDecision {
    ADMIT_NEW,
    DUPLICATE_IDEMPOTENT,
    REJECT_STALE,
    REJECT_CONFLICTING,
    REJECT_INVALID
}

internal enum class ActionAdmissionDecision {
    ADMIT_EXACT_MATCH,
    ADMIT_RESTART,
    REJECT_STALE,
    REJECT_RESTART_NO_EVIDENCE,
    REJECT_INCOMPLETE
}

internal object AlarmServiceAdmissionPolicy {

    fun classifyStart(
        current: AlarmGeneration?,
        incoming: AlarmGeneration?
    ): StartAdmissionDecision {
        if (incoming == null || !incoming.isValid) {
            return StartAdmissionDecision.REJECT_INVALID
        }
        if (current == null) {
            return StartAdmissionDecision.ADMIT_NEW
        }
        if (incoming.matches(current)) {
            return StartAdmissionDecision.DUPLICATE_IDEMPOTENT
        }
        if (incoming.alarmId == current.alarmId) {
            return if (incoming.scheduledAt < current.scheduledAt) {
                StartAdmissionDecision.REJECT_STALE
            } else if (incoming.scheduledAt == current.scheduledAt && incoming.fireId != current.fireId) {
                StartAdmissionDecision.REJECT_CONFLICTING
            } else {
                StartAdmissionDecision.ADMIT_NEW
            }
        }
        return StartAdmissionDecision.ADMIT_NEW
    }

    fun classifyAction(
        current: AlarmGeneration?,
        incoming: AlarmGeneration?,
        isRestart: Boolean,
        activeEvidence: AlarmService.Companion.ActiveAlarmSnapshot?
    ): ActionAdmissionDecision {
        if (incoming == null || !incoming.isValid) {
            return ActionAdmissionDecision.REJECT_INCOMPLETE
        }
        if (current != null) {
            return if (incoming.matches(current)) {
                ActionAdmissionDecision.ADMIT_EXACT_MATCH
            } else {
                ActionAdmissionDecision.REJECT_STALE
            }
        }
        if (isRestart) {
            val matches = activeEvidence != null &&
                activeEvidence.alarmId == incoming.alarmId &&
                activeEvidence.scheduledAt == incoming.scheduledAt &&
                activeEvidence.fireId == incoming.fireId
            return if (matches) {
                ActionAdmissionDecision.ADMIT_RESTART
            } else {
                ActionAdmissionDecision.REJECT_RESTART_NO_EVIDENCE
            }
        }
        return ActionAdmissionDecision.REJECT_STALE
    }
}
