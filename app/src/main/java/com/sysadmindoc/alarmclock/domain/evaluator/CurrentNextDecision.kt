package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.model.AlarmOccurrenceId

/**
 * Pure value model representing the Current-Next decision planning result (KNO-154).
 */
data class CurrentNextDecision(
    val outcome: CurrentNextDecisionOutcome,
    val alarmId: Long,
    val expectedTargetEpochMs: Long?,
    val evaluatedOccurrence: EvaluatedOccurrence?,
    val canonicalOccurrenceId: AlarmOccurrenceId?,
    val activeGeneration: Int,
    val activeRevision: Int,
    val isSkipActionEligible: Boolean = false,
    val decisionReason: String = ""
)
