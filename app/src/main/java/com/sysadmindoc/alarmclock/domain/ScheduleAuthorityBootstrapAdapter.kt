package com.sysadmindoc.alarmclock.domain

import android.util.Log
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapter to safely initialize [ScheduleAuthority] rows for existing enabled [Alarm] definitions lacking authority.
 *
 * Guarantees:
 * - Requires verified restoreEpoch from ScheduleAuthorityMeta (does NOT manufacture restoreEpoch).
 * - Requires verified instanceToken evidence (does NOT manufacture instanceToken).
 * - Recomputes target trigger time from [NextAlarmCalculator] (does NOT trust legacy nextTriggerTime as truth).
 * - Marks bootstrapped enabled alarms as 'RECONCILING' so reconciler evaluates capability and registers OS alarm clock.
 * - Does NOT reconstruct uncommitted SNOOZED sessions from Room alone.
 * - Returns [BootstrapDecision.QUARANTINE] if required definition, instanceToken, or restoreEpoch evidence is missing.
 */
@Singleton
class ScheduleAuthorityBootstrapAdapter @Inject constructor(
    private val authorityDao: ScheduleAuthorityDao,
    private val alarmRepository: AlarmRepository,
    private val calculator: NextAlarmCalculator
) {

    companion object {
        private const val TAG = "ScheduleAuthorityBootstrapAdapter"
    }

    suspend fun bootstrapAuthorityForAlarm(alarmId: Long, instanceToken: String?): BootstrapDecision {
        val alarm = alarmRepository.getById(alarmId)?.sanitized()
            ?: return BootstrapDecision.QUARANTINE

        val meta = authorityDao.getMetaById(1)
        val verifiedRestoreEpoch = meta?.restoreEpoch
            ?: return BootstrapDecision.QUARANTINE // Cannot bootstrap without verified restore epoch!

        if (instanceToken.isNullOrBlank()) {
            return BootstrapDecision.QUARANTINE // Cannot bootstrap without verified instance token!
        }

        val existing = authorityDao.getAuthorityByAlarmId(alarmId)
        if (existing != null) {
            return when (existing.reconcileState) {
                "SETTLED" -> BootstrapDecision.ADOPT
                "RECONCILING" -> BootstrapDecision.RECONCILE
                else -> BootstrapDecision.QUARANTINE
            }
        }

        val now = ZonedDateTime.now()
        val computedTriggerMillis = if (alarm.isEnabled) {
            try {
                calculator.calculate(alarm, now)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to compute next trigger for alarm ${alarm.id}", e)
                null
            }
        } else null

        val desiredOp = if (alarm.isEnabled && computedTriggerMillis != null && computedTriggerMillis > System.currentTimeMillis()) {
            "SET"
        } else {
            "CANCEL"
        }

        val desiredTrigger = if (desiredOp == "SET") computedTriggerMillis else null

        val newAuthority = ScheduleAuthority(
            alarmId = alarm.id,
            instanceToken = instanceToken,
            definitionRevision = 1L,
            scheduleRevision = 1L,
            lifecycle = if (alarm.isEnabled) "LIVE" else "DISABLED",
            desiredOperation = desiredOp,
            desiredTriggerMillis = desiredTrigger,
            reconcileState = "RECONCILING", // Requires reconciliation; NOT assumed SETTLED!
            restoreEpoch = verifiedRestoreEpoch,
            actionKey = null
        )

        authorityDao.insertAuthority(newAuthority)
        Log.i(TAG, "Bootstrapped missing authority for alarm ${alarm.id} as RECONCILING with calculated trigger $desiredTrigger")
        return BootstrapDecision.RECONCILE
    }
}
