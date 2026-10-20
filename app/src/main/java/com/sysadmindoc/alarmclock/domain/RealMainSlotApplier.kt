package com.sysadmindoc.alarmclock.domain

import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [MainSlotApplier].
 * Maps current [ScheduleAuthority] desired operation and target to actual [AlarmScheduler] OS slot operations.
 */
@Singleton
class RealMainSlotApplier @Inject constructor(
    private val alarmScheduler: AlarmScheduler,
    private val alarmRepository: AlarmRepository
) : MainSlotApplier {

    override suspend fun applySlotOperation(authority: ScheduleAuthority): Boolean {
        return when (authority.desiredOperation) {
            "SET" -> {
                val triggerMillis = authority.desiredTriggerMillis ?: return false
                val alarm = alarmRepository.getById(authority.alarmId)?.sanitized() ?: return false
                alarmScheduler.scheduleAt(alarm, triggerMillis)
            }
            "CANCEL" -> {
                alarmScheduler.cancel(authority.alarmId)
                true
            }
            else -> false
        }
    }
}
