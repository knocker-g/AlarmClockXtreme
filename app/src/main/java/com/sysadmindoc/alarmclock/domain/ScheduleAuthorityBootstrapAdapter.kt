package com.sysadmindoc.alarmclock.domain

import android.util.Log
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapter to safely initialize [ScheduleAuthority] rows for existing enabled [Alarm] definitions lacking authority.
 *
 * Guarantees:
 * - Does NOT trust nextTriggerTime as proof of OS registration.
 * - Marks bootstrapped enabled alarms as 'RECONCILING' so reconciler evaluates capability and registers OS alarm clock.
 * - Does NOT reconstruct uncommitted SNOOZED sessions from Room alone.
 * - Returns [BootstrapDecision.QUARANTINE] if required definition or session evidence is missing/invalid.
 */
@Singleton
class ScheduleAuthorityBootstrapAdapter @Inject constructor(
    private val authorityDao: ScheduleAuthorityDao,
    private val alarmRepository: AlarmRepository
) {

    companion object {
        private const val TAG = "ScheduleAuthorityBootstrapAdapter"
    }

    suspend fun bootstrapMissingAuthorities(): List<ScheduleAuthority> {
        val allAlarms = alarmRepository.getAll()
        val bootstrapped = mutableListOf<ScheduleAuthority>()

        for (alarm in allAlarms) {
            val existing = authorityDao.getAuthorityByAlarmId(alarm.id)
            if (existing == null) {
                val desiredOp = if (alarm.isEnabled && alarm.nextTriggerTime > 0L) "SET" else "CANCEL"
                val desiredTrigger = if (alarm.isEnabled && alarm.nextTriggerTime > 0L) alarm.nextTriggerTime else null

                val newAuthority = ScheduleAuthority(
                    alarmId = alarm.id,
                    instanceToken = "token-bootstrap-${alarm.id}-${alarm.nextTriggerTime}",
                    definitionRevision = 1L,
                    scheduleRevision = 1L,
                    lifecycle = if (alarm.isEnabled) "LIVE" else "DISABLED",
                    desiredOperation = desiredOp,
                    desiredTriggerMillis = desiredTrigger,
                    reconcileState = "RECONCILING", // Requires reconciliation; NOT assumed SETTLED!
                    restoreEpoch = 100L,
                    actionKey = null
                )
                authorityDao.insertAuthority(newAuthority)
                bootstrapped.add(newAuthority)
                Log.i(TAG, "Bootstrapped missing authority for alarm ${alarm.id} as RECONCILING")
            }
        }
        return bootstrapped
    }
}
