package com.sysadmindoc.alarmclock.data.repository

import com.sysadmindoc.alarmclock.data.local.ScheduleExceptionDao
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository wrapping ScheduleExceptionDao for schedule exception management (KNO-152).
 */
@Singleton
open class ScheduleExceptionRepository @Inject constructor(
    private val dao: ScheduleExceptionDao
) {
    open suspend fun addException(exception: ScheduleException): Long {
        return dao.insert(exception)
    }

    open suspend fun getExceptionsForAlarm(alarmId: Long): List<ScheduleException> {
        return dao.getExceptionsForAlarm(alarmId)
    }

    open suspend fun getExceptionsForAlarmGeneration(alarmId: Long, generation: Int): List<ScheduleException> {
        return dao.getExceptionsForAlarmGeneration(alarmId, generation)
    }

    open fun observeExceptionsForAlarm(alarmId: Long): Flow<List<ScheduleException>> {
        return dao.observeExceptionsForAlarm(alarmId)
    }

    open suspend fun getExceptionForOccurrence(alarmId: Long, baseTriggerTime: Long, generation: Int = 1, revision: Int = 1): ScheduleException? {
        return dao.getExceptionForOccurrence(alarmId, baseTriggerTime, generation, revision)
    }

    open suspend fun getExceptionForBaseTriggerTime(alarmId: Long, baseTriggerTime: Long): ScheduleException? {
        return dao.getExceptionForBaseTriggerTime(alarmId, baseTriggerTime)
    }

    open suspend fun deleteForOccurrence(alarmId: Long, baseTriggerTime: Long, generation: Int = 1, revision: Int = 1) {
        dao.deleteForOccurrence(alarmId, baseTriggerTime, generation, revision)
    }

    open suspend fun deleteForAlarm(alarmId: Long) {
        dao.deleteForAlarm(alarmId)
    }

    open suspend fun deleteExpiredExceptions(thresholdEpochMs: Long) {
        dao.deleteExpiredExceptions(thresholdEpochMs)
    }

    open suspend fun getAll(): List<ScheduleException> {
        return dao.getAll()
    }
}
