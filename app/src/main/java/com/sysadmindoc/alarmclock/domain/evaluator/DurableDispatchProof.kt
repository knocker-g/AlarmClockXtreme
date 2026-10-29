package com.sysadmindoc.alarmclock.domain.evaluator

/**
 * Authentic OS registration proof required for settlement confirmation (KNO-154 Phase 0C-B Subtask 4).
 */
data class DurableDispatchProof(
    val operationToken: String,
    val alarmId: Long,
    val authorityInstanceToken: String,
    val scheduleRevision: Long,
    val restoreEpoch: Long,
    val targetTriggerTimeEpochMs: Long
)
