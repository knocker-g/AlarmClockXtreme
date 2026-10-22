package com.sysadmindoc.alarmclock.domain

import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [MainSlotApplier].
 * Maps current [ScheduleAuthority] desired operation and target to RAW [MAIN_ALARM_SLOT] OS operations.
 *
 * Excludes supporting side effects: does NOT mutate AlarmRepository nextTriggerTime,
 * does NOT touch DirectBoot cache, does NOT trigger widget updates, and does NOT enqueue supporting WorkManager jobs.
 */
@Singleton
class RealMainSlotApplier @Inject constructor(
    private val alarmScheduler: AlarmScheduler
) : MainSlotApplier {

    override suspend fun applySlotOperation(authority: ScheduleAuthority): Boolean {
        return when (authority.desiredOperation) {
            "SET" -> {
                val triggerMillis = authority.desiredTriggerMillis ?: return false
                alarmScheduler.applyRawOsSet(authority.alarmId, triggerMillis)
            }
            "CANCEL" -> {
                alarmScheduler.applyRawOsCancel(authority.alarmId)
            }
            else -> false
        }
    }
}
