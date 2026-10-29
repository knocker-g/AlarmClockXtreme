package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.domain.RecoveryDecision

/**
 * Result value model returned by [DurableReconcileDispatchAdmissionCoordinator] (KNO-154 Phase 0C-B Subtask 4).
 */
data class DurableDispatchResult(
    val outcome: DurableDispatchOutcome,
    val alarmId: Long,
    val intentToken: String,
    val adjudicationDecision: RecoveryDecision?,
    val updatedIntent: DurableOperationIntent?,
    val decisionReason: String = ""
)
