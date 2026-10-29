package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.model.Alarm

/**
 * Result value model returned by [DurableReconcileProducer] (KNO-154 Phase 0C-B Subtask 2).
 */
data class DurableReconcileProducerResult(
    val outcome: DurableReconcileProducerOutcome,
    val alarmId: Long,
    val reconcileDecision: ReconcileDecision?,
    val updatedAlarm: Alarm?,
    val issuedOccurrence: IssuedOccurrence?,
    val durableIntent: DurableOperationIntent?,
    val decisionReason: String = ""
)
