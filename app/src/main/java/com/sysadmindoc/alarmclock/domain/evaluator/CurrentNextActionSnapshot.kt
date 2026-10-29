package com.sysadmindoc.alarmclock.domain.evaluator

/**
 * Immutable snapshot of target occurrence identity and authority fingerprint requested for action (KNO-154).
 */
data class CurrentNextActionSnapshot(
    val alarmId: Long,
    val expectedScheduledTargetEpochMs: Long?,
    val expectedBaseTriggerTimeEpochMs: Long? = null,
    val expectedGeneration: Int? = null,
    val authorityFingerprint: ScheduleAuthorityFingerprint? = null
)
