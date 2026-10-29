package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence

/**
 * Immutable proof of occurrence issuance used to confirm fire admission eligibility (KNO-154 Phase 0C-A).
 */
data class FireIssuanceProof(
    val issueToken: String,
    val alarmId: Long,
    val scheduledAt: Long,
    val fireId: String,
    val alarmGeneration: Int,
    val authorityFingerprint: ScheduleAuthorityFingerprint? = null,
    val status: String = IssuedOccurrence.STATUS_ISSUED
)
