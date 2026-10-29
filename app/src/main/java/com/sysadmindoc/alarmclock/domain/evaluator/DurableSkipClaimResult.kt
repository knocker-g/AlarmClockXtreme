package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.AlarmOccurrenceId

/**
 * Result value model returned by [DurableSkipClaimCoordinator] (KNO-154 Phase 0B).
 */
data class DurableSkipClaimResult(
    val outcome: DurableSkipClaimOutcome,
    val alarmId: Long,
    val committedSkipException: ScheduleException?,
    val canonicalOccurrenceId: AlarmOccurrenceId?,
    val nextTargetTriggerTimeEpochMs: Long?,
    val decision: CurrentNextDecision
)
