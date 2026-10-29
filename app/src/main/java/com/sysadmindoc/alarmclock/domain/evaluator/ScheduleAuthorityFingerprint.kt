package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority

/**
 * Immutable fingerprint representing active [ScheduleAuthority] state for identity validation (KNO-154).
 */
data class ScheduleAuthorityFingerprint(
    val alarmId: Long,
    val instanceToken: String,
    val restoreEpoch: Long,
    val scheduleRevision: Long,
    val definitionRevision: Long
) {
    companion object {
        fun fromAuthority(authority: ScheduleAuthority): ScheduleAuthorityFingerprint {
            return ScheduleAuthorityFingerprint(
                alarmId = authority.alarmId,
                instanceToken = authority.instanceToken,
                restoreEpoch = authority.restoreEpoch,
                scheduleRevision = authority.scheduleRevision,
                definitionRevision = authority.definitionRevision
            )
        }
    }
}
