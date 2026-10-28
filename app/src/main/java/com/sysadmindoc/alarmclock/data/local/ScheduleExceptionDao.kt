package com.sysadmindoc.alarmclock.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for schedule exception entities (KNO-152).
 */
@Dao
interface ScheduleExceptionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(exception: ScheduleException): Long

    @Query("SELECT * FROM schedule_exceptions WHERE alarmId = :alarmId ORDER BY baseTriggerTime ASC")
    suspend fun getExceptionsForAlarm(alarmId: Long): List<ScheduleException>

    @Query("SELECT * FROM schedule_exceptions WHERE alarmId = :alarmId AND generation = :generation ORDER BY baseTriggerTime ASC")
    suspend fun getExceptionsForAlarmGeneration(alarmId: Long, generation: Int): List<ScheduleException>

    @Query("SELECT * FROM schedule_exceptions WHERE alarmId = :alarmId ORDER BY baseTriggerTime ASC")
    fun observeExceptionsForAlarm(alarmId: Long): Flow<List<ScheduleException>>

    @Query("SELECT * FROM schedule_exceptions WHERE alarmId = :alarmId AND baseTriggerTime = :baseTriggerTime AND generation = :generation AND revision = :revision")
    suspend fun getExceptionForOccurrence(alarmId: Long, baseTriggerTime: Long, generation: Int, revision: Int): ScheduleException?

    @Query("SELECT * FROM schedule_exceptions WHERE alarmId = :alarmId AND baseTriggerTime = :baseTriggerTime LIMIT 1")
    suspend fun getExceptionForBaseTriggerTime(alarmId: Long, baseTriggerTime: Long): ScheduleException?

    @Query("DELETE FROM schedule_exceptions WHERE alarmId = :alarmId AND baseTriggerTime = :baseTriggerTime AND generation = :generation AND revision = :revision")
    suspend fun deleteForOccurrence(alarmId: Long, baseTriggerTime: Long, generation: Int, revision: Int)

    @Query("DELETE FROM schedule_exceptions WHERE alarmId = :alarmId")
    suspend fun deleteForAlarm(alarmId: Long)

    @Query("DELETE FROM schedule_exceptions WHERE baseTriggerTime < :thresholdEpochMs")
    suspend fun deleteExpiredExceptions(thresholdEpochMs: Long)

    @Query("SELECT * FROM schedule_exceptions ORDER BY alarmId ASC, baseTriggerTime ASC")
    suspend fun getAll(): List<ScheduleException>
}
