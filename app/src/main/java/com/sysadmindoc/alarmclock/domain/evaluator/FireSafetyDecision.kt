package com.sysadmindoc.alarmclock.domain.evaluator

/**
 * Decision result model returned by [NormalAlarmFireSafetyPlanner] (KNO-154 Phase 0C-A).
 */
data class FireSafetyDecision(
    val outcome: FireSafetyOutcome,
    val alarmId: Long,
    val firedTargetEpochMs: Long,
    val firedBaseTriggerTimeEpochMs: Long,
    val firedGeneration: Int,
    val isFireSuppressionRequired: Boolean = false,
    val isFireAdmissionConfirmed: Boolean = false,
    val decisionReason: String = ""
)
