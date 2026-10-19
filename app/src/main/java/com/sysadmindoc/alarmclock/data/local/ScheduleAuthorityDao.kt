package com.sysadmindoc.alarmclock.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta

@Dao
interface ScheduleAuthorityDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAuthority(authority: ScheduleAuthority)

    @Query("SELECT * FROM schedule_authority WHERE alarmId = :alarmId")
    suspend fun getAuthorityByAlarmId(alarmId: Long): ScheduleAuthority?

    @Query("SELECT * FROM schedule_authority")
    suspend fun getAllAuthorities(): List<ScheduleAuthority>

    @Query("DELETE FROM schedule_authority WHERE alarmId = :alarmId")
    suspend fun deleteAuthorityByAlarmId(alarmId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeta(meta: ScheduleAuthorityMeta)

    @Query("SELECT * FROM schedule_authority_meta WHERE id = :id")
    suspend fun getMetaById(id: Int = 1): ScheduleAuthorityMeta?
}
