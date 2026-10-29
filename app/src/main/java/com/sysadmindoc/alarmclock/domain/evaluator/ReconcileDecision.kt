package com.sysadmindoc.alarmclock.domain.evaluator

/**
 * Decision result model returned by [NormalScheduleReconcilePlanner] (KNO-154 Phase 0C-A).
 */
data class ReconcileDecision(
    val outcome: ReconcileOutcome,
    val alarmId: Long,
    val currentStoredNextTriggerTimeEpochMs: Long,
    val evaluatedPostSkipNextTargetEpochMs: Long?,
    val activeGeneration: Int,
    val isOsRescheduleCandidate: Boolean = false,
    val decisionReason: String = ""
)
